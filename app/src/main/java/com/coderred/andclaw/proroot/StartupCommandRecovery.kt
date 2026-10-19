package com.coderred.andclaw.proroot

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.TimeUnit

/** Reconstruct the native writer barrier after the Java process has died. */
internal class StartupCommandRecovery(
    receiptDirectory: File,
    private val appUid: Int,
    private val bootId: String,
    private val procDirectory: File = File("/proc"),
) {
    private val receiptDirectory = receiptDirectory.absoluteFile
    private data class Supervisor(val pid: Int, val receiptName: String?, val exited: Boolean)

    private class Receipt(
        val file: File,
        val descriptor: FileDescriptor,
        val identity: StructStat,
        val directoryIdentity: StructStat,
    ) {
        private val buffer = ByteArray(97)

        fun matchesPath(): Boolean = runCatching {
            val directory = Os.lstat(checkNotNull(file.parent))
            if (!isPrivateDirectory(directory, identity.st_uid) ||
                directory.st_dev != directoryIdentity.st_dev || directory.st_ino != directoryIdentity.st_ino
            ) return@runCatching false
            val current = Os.lstat(file.path)
            OsConstants.S_ISREG(current.st_mode) && current.st_dev == identity.st_dev &&
                current.st_ino == identity.st_ino && current.st_uid == identity.st_uid && current.st_nlink == 1L
        }.getOrDefault(false)

        fun text(): String {
            if (!matchesPath()) throw StartupCommandTerminationException("Startup receipt identity changed.")
            Os.lseek(descriptor, 0L, OsConstants.SEEK_SET)
            var size = 0
            while (size < buffer.size) {
                val count = Os.read(descriptor, buffer, size, buffer.size - size)
                if (count <= 0) break
                size += count
            }
            if (size == buffer.size) throw StartupCommandTerminationException("Startup receipt is oversized.")
            return String(buffer, 0, size, Charsets.US_ASCII)
        }

        fun claim() {
            // Pin before changing permissions. A later native O_WRONLY open now
            // fails; a native writer that already opened this inode must still
            // be found in /proc and finish its normal receipt/reap protocol.
            Os.fchmod(descriptor, OsConstants.S_IRUSR)
            if (!matchesPath()) throw StartupCommandTerminationException("Startup receipt was replaced during recovery.")
        }

        fun delete() {
            if (!matchesPath()) throw StartupCommandTerminationException("Startup receipt was replaced before cleanup.")
            java.nio.file.Files.delete(file.toPath())
        }
    }

    fun awaitQuiescent(timeoutMs: Long = 5_000L): Set<Int> {
        var directoryDescriptor: FileDescriptor? = null
        val receipts = mutableListOf<Receipt>()
        try {
            directoryDescriptor = Os.open(receiptDirectory.path, DIRECTORY_OPEN_FLAGS, 0)
            val directoryIdentity = Os.fstat(directoryDescriptor)
            if (!isPrivateDirectory(directoryIdentity, appUid)) {
                throw StartupCommandTerminationException("Startup receipt directory is not private and owned.")
            }
            requireDirectoryIdentity(receiptDirectory, directoryIdentity)
            val files = receiptDirectory.listFiles { file -> isReceiptName(file.name) }
                ?: throw StartupCommandTerminationException("Cannot inspect previous startup command receipts.")
            for (file in files) {
                val descriptor = Os.open(
                    file.path,
                    OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK,
                    0,
                )
                var retained = false
                try {
                    val identity = Os.fstat(descriptor)
                    if (!OsConstants.S_ISREG(identity.st_mode) || identity.st_uid != appUid ||
                        identity.st_nlink != 1L || identity.st_mode and 0x3f != 0 || identity.st_size > 96L
                    ) throw StartupCommandTerminationException("Startup receipt is not an owned private regular file.")
                    val receipt = Receipt(file, descriptor, identity, directoryIdentity)
                    receipt.claim()
                    receipts.add(receipt)
                    retained = true
                } finally {
                    if (!retained) Os.close(descriptor)
                }
            }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            val protectedPids = mutableSetOf<Int>()
            while (true) {
                requireDirectoryIdentity(receiptDirectory, directoryIdentity)
                val supervisors = supervisors()
                supervisors.forEach { protectedPids.add(it.pid) }
                val contents = receipts.associate { it.file.name to it.text() }
                val allSupervisorsExited = supervisors.all {
                    it.exited && it.receiptName?.let { name -> parseCompletion(contents[name].orEmpty()) != null } == true
                }
                val allReceiptsResolved = receipts.all { receipt ->
                    val text = checkNotNull(contents[receipt.file.name])
                    parseCompletion(text) != null ||
                        receiptBootId(receipt.file.name)?.let { it != bootId } == true ||
                        (text != READY && READY.startsWith(text))
                }
                if (allSupervisorsExited && allReceiptsResolved) {
                    // Re-read pinned content after the process census; readonly
                    // claims close the late-open race for empty/partial records.
                    receipts.forEach { receipt ->
                        val text = receipt.text()
                        if (parseCompletion(text) == null &&
                            receiptBootId(receipt.file.name)?.let { it != bootId } != true &&
                            !(text != READY && READY.startsWith(text))
                        ) throw StartupCommandTerminationException("Startup receipt changed before cleanup.")
                    }
                    requireDirectoryIdentity(receiptDirectory, directoryIdentity)
                    receipts.forEach { it.delete() }
                    return protectedPids
                }
                if (System.nanoTime() >= deadline) {
                    throw StartupCommandTerminationException(
                        "Previous startup command tree termination could not be confirmed; " +
                            "native supervisor receipts and exit proof are still required.",
                    )
                }
                Thread.sleep(25L)
            }
        } catch (failure: StartupCommandTerminationException) {
            throw failure
        } catch (failure: Exception) {
            throw StartupCommandTerminationException("Cannot prove previous startup command termination: ${failure.message}")
        } finally {
            receipts.forEach { runCatching { Os.close(it.descriptor) } }
            directoryDescriptor?.let { runCatching { Os.close(it) } }
        }
    }

    private fun supervisors(): List<Supervisor> {
        val entries = procDirectory.listFiles()
            ?: throw StartupCommandTerminationException("Cannot inspect previous startup supervisors.")
        val found = mutableListOf<Supervisor>()
        for (directory in entries) {
            val pid = directory.name.toIntOrNull() ?: continue
            val uid = runCatching {
                File(directory, "status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("Uid:") }
                        ?.removePrefix("Uid:")?.trim()?.substringBefore('\t')
                        ?.substringBefore(' ')?.toIntOrNull()
                }
            }.getOrNull() ?: runCatching { Os.stat(directory.path).st_uid }.getOrNull()
            if (uid != null && uid != appUid) continue
            val argv = readCommandLine(directory)
            val supervisor = argv?.let(::isSupervisorCommand) == true
            val commandName = if (argv.isNullOrEmpty()) runCatching {
                File(directory, "comm").readText().trim()
            }.getOrNull() else null
            val unreadableLauncher = argv.isNullOrEmpty() &&
                (commandName == null || commandName == ProrootManager.PROROOT_LAUNCHER_LIB)
            if (!supervisor && !unreadableLauncher) continue
            val receipt = argv?.getOrNull(3)?.let(::File)?.takeIf {
                isReceiptName(it.name) && it.parentFile?.canonicalFile == receiptDirectory
            }
            // Other private roots under a shared development UID are not ours;
            // legacy cleanup still never signals any supervisor argv.
            if (supervisor && receipt == null && argv.orEmpty().none { argument ->
                    runCatching { File(argument).parentFile?.canonicalFile == receiptDirectory }.getOrDefault(false)
                }
            ) continue
            val validArguments = argv != null && argv.size >= 6 &&
                argv[2].toIntOrNull()?.let { owner -> owner > 1 } == true && argv[4] == "--"
            val exited = runCatching {
                val stat = File(directory, "stat").readText()
                stat.substringAfterLast(')').trimStart().firstOrNull() == 'Z'
            }.getOrDefault(false)
            val gone = try {
                Os.lstat(directory.path)
                false
            } catch (error: ErrnoException) {
                error.errno == OsConstants.ENOENT
            }
            if (gone) continue
            found.add(Supervisor(pid, receipt?.name?.takeIf { validArguments }, exited))
        }
        return found
    }

    companion object {
        const val RECEIPT_PREFIX = "proroot-command-"
        private const val READY = "ready-v1\n"
        private val BOOT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        // Android's public SDK omits O_DIRECTORY; validate the pinned FD instead.
        // Nonblocking open prevents a replaced FIFO from stalling before fstat.
        private val DIRECTORY_OPEN_FLAGS = OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or
            OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC

        private fun isPrivateDirectory(stat: StructStat, uid: Int): Boolean =
            OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == uid && stat.st_mode and 0x1ff == 0x1c0

        private fun requireDirectoryIdentity(directory: File, identity: StructStat) {
            val current = Os.lstat(directory.path)
            if (!isPrivateDirectory(current, identity.st_uid) ||
                current.st_dev != identity.st_dev || current.st_ino != identity.st_ino
            ) throw StartupCommandTerminationException("Startup receipt directory identity changed.")
        }

        internal fun ownedReceiptDirectory(filesDirectory: File, appUid: Int): File {
            var descriptor: FileDescriptor? = null
            try {
                val parent = Os.lstat(filesDirectory.absolutePath)
                if (!OsConstants.S_ISDIR(parent.st_mode) || parent.st_uid != appUid) {
                    throw StartupCommandTerminationException("App files directory is not an owned directory.")
                }
                val directory = File(filesDirectory.canonicalFile, "startup-command-receipts")
                try {
                    Os.mkdir(directory.path, 0x1c0) // 0700 from creation, never a shared temporary directory.
                } catch (failure: ErrnoException) {
                    if (failure.errno != OsConstants.EEXIST) throw failure
                }
                descriptor = Os.open(directory.path, DIRECTORY_OPEN_FLAGS, 0)
                val identity = Os.fstat(descriptor)
                if (!OsConstants.S_ISDIR(identity.st_mode) || identity.st_uid != appUid) {
                    throw StartupCommandTerminationException("Startup receipt directory is not owned.")
                }
                Os.fchmod(descriptor, 0x1c0)
                val current = Os.lstat(directory.path)
                if (!isPrivateDirectory(current, appUid) ||
                    current.st_dev != identity.st_dev || current.st_ino != identity.st_ino
                ) throw StartupCommandTerminationException("Startup receipt directory identity changed.")
                return directory
            } catch (failure: StartupCommandTerminationException) {
                throw failure
            } catch (failure: Exception) {
                throw StartupCommandTerminationException("Cannot secure startup receipt directory: ${failure.message}")
            } finally {
                descriptor?.let { runCatching { Os.close(it) } }
            }
        }


        internal fun readBootId(procDirectory: File = File("/proc")): String = try {
            File(procDirectory, "sys/kernel/random/boot_id").inputStream().use { input ->
                val bytes = ByteArray(64)
                val count = input.read(bytes)
                val value = if (count > 0 && count < bytes.size) String(bytes, 0, count, Charsets.US_ASCII).trim() else ""
                if (!BOOT_ID.matches(value)) throw StartupCommandTerminationException("Current host boot identity is unavailable.")
                value
            }
        } catch (failure: StartupCommandTerminationException) {
            throw failure
        } catch (failure: Exception) {
            throw StartupCommandTerminationException("Cannot read current host boot identity: ${failure.message}")
        }

        private fun isReceiptName(name: String): Boolean = name.startsWith(RECEIPT_PREFIX) && name.endsWith(".receipt")

        private fun receiptBootId(name: String): String? {
            val suffix = name.removePrefix(RECEIPT_PREFIX)
            return suffix.take(36).takeIf { suffix.getOrNull(36) == '-' && BOOT_ID.matches(it) }
        }

        internal fun parseCompletion(text: String): Int? {
            val status = text.removePrefix(READY).removePrefix("reaped-v1:")
                .removeSuffix("\n").toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            val done = "reaped-v1:$status\n"
            return status.takeIf { text == done || text == "$READY$done" }
        }

        internal fun readCommandLine(processDirectory: File): List<String>? = runCatching {
            File(processDirectory, "cmdline").inputStream().use { input ->
                val bytes = ByteArray(4096)
                val count = input.read(bytes)
                if (count <= 0) emptyList() else String(bytes, 0, count, Charsets.UTF_8).split('\u0000')
            }
        }.getOrNull()

        internal fun isSupervisorCommand(argv: List<String>): Boolean =
            argv.size > 1 && File(argv[0]).name == ProrootManager.PROROOT_LAUNCHER_LIB && argv[1] == "--supervise-command"
    }
}
