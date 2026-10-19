package com.coderred.andclaw.proroot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.io.File
import java.util.concurrent.TimeUnit

internal class StartupCommandTerminationException(
    message: String = "Startup command tree termination could not be confirmed.",
) : IllegalStateException(message)

/** One startup generation, including its synchronous writes and supervised children. */
class StartupCommandLifetime internal constructor() {
    private val lock = Object()
    private var cancelled = false
    private var terminationUnconfirmed = false
    private var working = false
    private var job: Job? = null
    private var command: SupervisedCommand? = null

    internal fun beginWork() = synchronized(lock) {
        ensureActive()
        check(!working)
        working = true
    }

    internal fun bindJob(value: Job) = synchronized(lock) { job = value }

    internal fun finishWork() = synchronized(lock) {
        working = false
        lock.notifyAll()
    }

    internal fun ensureActive() = synchronized(lock) {
        if (terminationUnconfirmed) throw StartupCommandTerminationException()
        if (cancelled || job?.isCancelled == true) {
            throw CancellationException("Startup command generation was cancelled")
        }
    }

    internal fun launch(builder: ProcessBuilder, receipt: File): SupervisedCommand = synchronized(lock) {
        ensureActive()
        check(command == null) { "Previous startup command termination is unconfirmed." }
        SupervisedCommand(builder.start(), receipt).also { command = it }
    }

    internal fun release(finished: SupervisedCommand): Boolean {
        synchronized(lock) {
            if (command !== finished) return true
            if (!finished.terminationConfirmed()) return false
            command = null
            lock.notifyAll()
        }
        finished.receipt.delete()
        return true
    }

    internal fun invalidate() = synchronized(lock) { cancelled = true }

    internal fun failTermination(): Nothing {
        synchronized(lock) {
            cancelled = true
            terminationUnconfirmed = true
        }
        throw StartupCommandTerminationException()
    }

    internal fun cancelCommand(): Boolean {
        invalidate()
        return terminateCommand()
    }

    internal fun terminateCommand(): Boolean {
        val owned = synchronized(lock) { command } ?: return true
        return runCatching {
            if (owned.terminate()) release(owned)
            else synchronized(lock) { command !== owned }
        }.getOrDefault(false)
    }

    internal fun <T> commitIfActive(action: () -> T): T = synchronized(lock) {
        ensureActive()
        action()
    }

    internal fun awaitQuiescent(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(lock) {
            // Never wait for an unproven command to disappear by assumption.
            // Only release(), backed by a matching native receipt, may clear it.
            if (command != null) return false
            while (working || command != null) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                TimeUnit.NANOSECONDS.timedWait(lock, remaining)
            }
            return true
        }
    }

    internal fun isQuiescent(): Boolean = synchronized(lock) { !working && command == null }
}

internal class SupervisedCommand(val process: Process, val receipt: File) {
    private val receiptBuffer = ByteArray(96)

    @Synchronized
    private fun receiptText(): String = runCatching {
        // The native protocol is two short lines, never arbitrary command output.
        receipt.inputStream().use { input ->
            val count = input.read(receiptBuffer)
            if (count <= 0) "" else String(receiptBuffer, 0, count, Charsets.US_ASCII)
        }
    }.getOrDefault("")

    fun terminationConfirmed(): Boolean {
        if (!process.waitFor(0, TimeUnit.MILLISECONDS)) return false
        return StartupCommandRecovery.parseCompletion(receiptText()) == process.exitValue()
    }

    @Synchronized
    fun terminate(): Boolean {
        if (terminationConfirmed()) return true
        val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (process.isAlive && !receiptText().startsWith("ready-v1\n")) {
            if (System.nanoTime() >= readyDeadline) return false
            process.waitFor(25, TimeUnit.MILLISECONDS)
        }
        if (!process.isAlive) return terminationConfirmed()
        // Native owns escalation and detached descendant adoption. Killing this
        // supervisor forcibly would discard the only proof of writer ownership.
        process.destroy()
        process.waitFor(5_000, TimeUnit.MILLISECONDS)
        return terminationConfirmed()
    }
}
