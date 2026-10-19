@file:Suppress("PackageDirectoryMismatch")

package com.coderred.andclaw.data

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.JsonReader
import android.util.JsonToken
import com.coderred.andclaw.BuildConfig
import com.coderred.andclaw.proroot.OpenClawPluginInstallStateStore
import java.io.File
import java.io.StringReader
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class BugReportBundle(
    val generatedAtEpochMs: Long,
    val metadata: BugReportMetadata,
    val gatewayErrorMessage: String? = null,
    val processErrorMessage: String? = null,
    val sessionErrors: List<BugReportSessionErrorEntry> = emptyList(),
    val gatewayLogs: List<String> = emptyList(),
    val attachments: List<BugReportTextAttachment> = emptyList(),
)

data class BugReportTextAttachment(
    val entryName: String,
    val content: String,
)

data class BugReportMetadata(
    val packageName: String,
    val appVersionName: String,
    val appVersionCode: Long,
    val androidSdkInt: Int,
    val deviceModel: String,
    val deviceManufacturer: String,
    val locale: String,
)

data class BugReportSessionErrorEntry(
    val timestamp: String,
    val role: String,
    val model: String?,
    val stopReason: String?,
    val errorMessage: String?,
    val tokenUsage: Int,
)

object BugReportBundleBuilder {
    fun collectSupplementalRuntimeAttachments(rootfsDir: File): List<BugReportTextAttachment> {
        val fileAttachments = collectSupplementalRuntimeLogSpecs(rootfsDir).mapNotNull { spec ->
            val file = resolveSupplementalRuntimeFile(rootfsDir, spec) ?: return@mapNotNull null
            if (!file.isFile) return@mapNotNull null

            BugReportTextAttachment(
                entryName = spec.zipEntryName,
                content = sanitizeSupplementalRuntimeFileContent(file, spec),
            )
        }
        val pluginStateAttachment = OpenClawPluginInstallStateStore.readDiagnostic(rootfsDir)?.let { diagnostic ->
            BugReportTextAttachment(
                entryName = "runtime/openclaw/plugin-install-state.json",
                content = diagnostic.json.toString(2) + "\n",
            )
        }
        return fileAttachments + listOfNotNull(pluginStateAttachment) + collectOwnershipDiagnosticAttachments(rootfsDir)
    }

    fun sanitizeGatewayLogLines(gatewayLogLines: List<String>): List<String> {
        val lastGatewayStart = gatewayLogLines.indexOfLast { line ->
            line.contains(GATEWAY_START_MARKER, ignoreCase = true)
        }
        val currentAttemptLines = if (lastGatewayStart >= 0) {
            gatewayLogLines.subList(lastGatewayStart, gatewayLogLines.size)
        } else {
            gatewayLogLines
        }
        val sanitizedLines = currentAttemptLines
            .asSequence()
            .map { it.sanitizeGatewayLogLine() }
            .filter { it.isNotBlank() }
            .toList()

        return if (sanitizedLines.size <= MAX_GATEWAY_LOG_LINES) {
            sanitizedLines
        } else {
            listOf(sanitizedLines.first()) + sanitizedLines.takeLast(MAX_GATEWAY_LOG_LINES - 1)
        }
    }

    fun build(
        sessionEntries: List<SessionLogEntry>,
        gatewayLogLines: List<String>,
        metadata: BugReportMetadata,
        gatewayErrorMessage: String? = null,
        processErrorMessage: String? = null,
        attachments: List<BugReportTextAttachment> = emptyList(),
        generatedAtEpochMs: Long = System.currentTimeMillis(),
    ): BugReportBundle {
        return BugReportBundle(
            generatedAtEpochMs = generatedAtEpochMs,
            metadata = metadata,
            gatewayErrorMessage = gatewayErrorMessage.normalizeError(),
            processErrorMessage = processErrorMessage.normalizeError(),
            sessionErrors = sessionEntries
                .asSequence()
                .filter { it.isSessionError() }
                .map { entry ->
                    BugReportSessionErrorEntry(
                        timestamp = entry.timestamp,
                        role = entry.role,
                        model = entry.model,
                        stopReason = entry.stopReason,
                        errorMessage = entry.errorMessage,
                        tokenUsage = entry.tokenUsage,
                    )
                }
                .toList(),
            gatewayLogs = sanitizeGatewayLogLines(gatewayLogLines),
            attachments = attachments,
        )
    }

    fun collectMetadata(context: Context): BugReportMetadata {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

        return BugReportMetadata(
            packageName = context.packageName,
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = versionCode,
            androidSdkInt = Build.VERSION.SDK_INT,
            deviceModel = Build.MODEL.orEmpty(),
            deviceManufacturer = Build.MANUFACTURER.orEmpty(),
            locale = Locale.getDefault().toLanguageTag(),
        )
    }
}

fun SessionLogEntry.isSessionError(): Boolean {
    return this.stopReason == "error" || this.errorMessage != null
}

private fun String?.normalizeError(): String? {
    return this?.trim()?.takeIf { it.isNotEmpty() }
}

private fun String.sanitizeGatewayLogLine(): String {
    return sanitizeGatewayLogLineUnbounded(removeTerminalEscapeSequences())
        .truncateGatewayLogLine()
}

private fun String.removeTerminalEscapeSequences(): String {
    return TERMINAL_ESCAPE_SEQUENCE_REGEX.replace(this, "").replace("\u001B", "")
}

private fun String.truncateGatewayLogLine(): String {
    if (length <= MAX_GATEWAY_LOG_LINE_LENGTH) return this

    val omittedCharacters = length - MAX_GATEWAY_LOG_LINE_CONTEXT_CHARS * 2
    return take(MAX_GATEWAY_LOG_LINE_CONTEXT_CHARS) +
        "… [truncated $omittedCharacters chars] …" +
        takeLast(MAX_GATEWAY_LOG_LINE_CONTEXT_CHARS)
}

private const val GATEWAY_START_MARKER = "[andClaw] Starting gateway..."
private const val MAX_GATEWAY_LOG_LINES = 60
private const val MAX_GATEWAY_LOG_LINE_LENGTH = 1_800
private const val MAX_GATEWAY_LOG_LINE_CONTEXT_CHARS = 800
private const val MAX_SUPPLEMENTAL_RUNTIME_LOG_LINES_PER_FILE = 200
private const val MAX_OPENCLAW_RUNTIME_LOG_FILES = 2
private const val MAX_SUPPLEMENTAL_RUNTIME_FILE_BYTES = 1024 * 1024

private val TERMINAL_ESCAPE_SEQUENCE_REGEX = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")

internal fun readTextFileLinesBounded(
    file: File,
    maxBytes: Int,
    maxLines: Int,
    fromEnd: Boolean,
): List<String> {
    require(maxBytes > 0)
    require(maxLines > 0)
    if (!file.isFile) return emptyList()

    val fileLength = file.length()
    if (fileLength <= 0L) return emptyList()

    val bytesToRead = minOf(fileLength, maxBytes.toLong()).toInt()
    val startOffset = if (fromEnd) fileLength - bytesToRead else 0L
    val bytes = ByteArray(bytesToRead)
    var dropsLeadingPartialLine = false

    RandomAccessFile(file, "r").use { input ->
        if (fromEnd && startOffset > 0L) {
            input.seek(startOffset - 1L)
            val previousByte = input.read()
            dropsLeadingPartialLine = previousByte != '\n'.code && previousByte != '\r'.code
        }
        input.seek(startOffset)
        input.readFully(bytes)
    }

    val lines = String(bytes, StandardCharsets.UTF_8).lineSequence().iterator()
    val endsWithLineBreak = bytes.last() == '\n'.code.toByte() || bytes.last() == '\r'.code.toByte()
    if (!fromEnd) {
        val head = ArrayList<String>(maxLines)
        while (lines.hasNext() && head.size < maxLines) {
            val line = lines.next()
            if (endsWithLineBreak && line.isEmpty() && !lines.hasNext()) break
            head += line
        }
        return head
    }

    if (dropsLeadingPartialLine && lines.hasNext()) {
        lines.next()
    }
    val tail = ArrayDeque<String>(maxLines)
    while (lines.hasNext()) {
        val line = lines.next()
        if (endsWithLineBreak && line.isEmpty() && !lines.hasNext()) break
        if (tail.size == maxLines) {
            tail.removeFirst()
        }
        tail.addLast(line)
    }
    return tail.toList()
}

private enum class SupplementalRuntimeBase { ROOTFS, ROOTFS_PARENT }

private data class SupplementalRuntimeLogSpec(
    val base: SupplementalRuntimeBase,
    val sourceRelativePath: String,
    val zipEntryName: String,
    val sanitizeMode: SupplementalRuntimeSanitizeMode = SupplementalRuntimeSanitizeMode.NONE,
) {
    val displayPath: String
        get() = when (base) {
            SupplementalRuntimeBase.ROOTFS -> "/$sourceRelativePath"
            SupplementalRuntimeBase.ROOTFS_PARENT -> "../$sourceRelativePath"
        }
}

private enum class SupplementalRuntimeSanitizeMode {
    NONE,
    LAUNCHER_POSTMORTEM,
    OPENCLAW_LOG_TAIL,
}

private fun resolveSupplementalRuntimeFile(
    rootfsDir: File,
    spec: SupplementalRuntimeLogSpec,
): File? {
    val baseDir = when (spec.base) {
        SupplementalRuntimeBase.ROOTFS -> rootfsDir
        SupplementalRuntimeBase.ROOTFS_PARENT -> rootfsDir.parentFile ?: return null
    }
    return File(baseDir, spec.sourceRelativePath)
}

private val SUPPLEMENTAL_RUNTIME_LOG_FILES = listOf(
    SupplementalRuntimeLogSpec(
        base = SupplementalRuntimeBase.ROOTFS,
        sourceRelativePath = "tmp/andclaw-agent-lease-last.json",
        zipEntryName = "runtime/openclaw/agent-lease-last.json",
    ),
    SupplementalRuntimeLogSpec(
        base = SupplementalRuntimeBase.ROOTFS,
        sourceRelativePath = "tmp/proroot-sigsys-last.txt",
        zipEntryName = "runtime/proroot-sigsys-last.txt",
    ),
    SupplementalRuntimeLogSpec(
        base = SupplementalRuntimeBase.ROOTFS,
        sourceRelativePath = "tmp/proroot-launcher-last.txt",
        zipEntryName = "runtime/proroot-launcher-last.txt",
        sanitizeMode = SupplementalRuntimeSanitizeMode.LAUNCHER_POSTMORTEM,
    ),
    SupplementalRuntimeLogSpec(
        base = SupplementalRuntimeBase.ROOTFS_PARENT,
        sourceRelativePath = "proroot-sigbus-maps.txt",
        zipEntryName = "runtime/proroot-sigbus-maps.txt",
    ),
    SupplementalRuntimeLogSpec(
        base = SupplementalRuntimeBase.ROOTFS,
        sourceRelativePath = "root/.openclaw/agents/main/agent/codex-home/codex-http-metrics.jsonl",
        zipEntryName = "runtime/codex/codex-http-metrics.jsonl",
        sanitizeMode = SupplementalRuntimeSanitizeMode.OPENCLAW_LOG_TAIL,
    ),
    SupplementalRuntimeLogSpec(
        base = SupplementalRuntimeBase.ROOTFS,
        sourceRelativePath = "root/.openclaw/agents/main/agent/codex-home/codex-rust.log",
        zipEntryName = "runtime/codex/codex-rust.log",
        sanitizeMode = SupplementalRuntimeSanitizeMode.OPENCLAW_LOG_TAIL,
    ),
)

private val OPENCLAW_LOG_FILE_REGEX = Regex("""openclaw-\d{4}-\d{2}-\d{2}\.log""")

private fun collectSupplementalRuntimeLogSpecs(rootfsDir: File): List<SupplementalRuntimeLogSpec> {
    return SUPPLEMENTAL_RUNTIME_LOG_FILES + collectOpenClawRuntimeLogSpecs(rootfsDir)
}

private fun collectOpenClawRuntimeLogSpecs(rootfsDir: File): List<SupplementalRuntimeLogSpec> {
    val openClawLogDir = File(rootfsDir, "tmp/openclaw")
    if (!openClawLogDir.isDirectory) return emptyList()

    return openClawLogDir
        .listFiles { file ->
            file.isFile && OPENCLAW_LOG_FILE_REGEX.matches(file.name)
        }
        .orEmpty()
        .sortedWith(
            compareByDescending<File> { it.lastModified() }
                .thenByDescending { it.name }
        )
        .take(MAX_OPENCLAW_RUNTIME_LOG_FILES)
        .map { file ->
            SupplementalRuntimeLogSpec(
                base = SupplementalRuntimeBase.ROOTFS,
                sourceRelativePath = "tmp/openclaw/${file.name}",
                zipEntryName = "runtime/openclaw/${file.name}",
                sanitizeMode = SupplementalRuntimeSanitizeMode.OPENCLAW_LOG_TAIL,
            )
        }
}

private fun sanitizeSupplementalRuntimeFileContent(
    file: File,
    spec: SupplementalRuntimeLogSpec,
): String {
    val lines = sanitizeSupplementalRuntimeLines(
        readTextFileLinesBounded(
            file = file,
            maxBytes = MAX_SUPPLEMENTAL_RUNTIME_FILE_BYTES,
            maxLines = MAX_SUPPLEMENTAL_RUNTIME_LOG_LINES_PER_FILE,
            fromEnd = spec.sanitizeMode == SupplementalRuntimeSanitizeMode.OPENCLAW_LOG_TAIL,
        ),
        spec,
    )
    val postfix = when {
        spec.sanitizeMode == SupplementalRuntimeSanitizeMode.OPENCLAW_LOG_TAIL -> "\n"
        file.inputStream().use { input ->
            if (file.length() <= 0L) {
                false
            } else {
                input.skip(file.length() - 1L)
                input.read() in listOf('\n'.code, '\r'.code)
            }
        } -> "\n"
        else -> ""
    }
    return lines.joinToString("\n", postfix = postfix)
}

private fun sanitizeSupplementalRuntimeLines(
    lines: List<String>,
    spec: SupplementalRuntimeLogSpec,
): List<String> {
    return when (spec.sanitizeMode) {
        SupplementalRuntimeSanitizeMode.NONE -> lines
        SupplementalRuntimeSanitizeMode.LAUNCHER_POSTMORTEM -> sanitizeLauncherPostmortemLines(lines)
        SupplementalRuntimeSanitizeMode.OPENCLAW_LOG_TAIL -> lines
            .takeLast(MAX_SUPPLEMENTAL_RUNTIME_LOG_LINES_PER_FILE)
            .map { sanitizeGatewayLogLineUnbounded(it.trimEnd()) }
    }
}

private fun sanitizeLauncherPostmortemLines(lines: List<String>): List<String> {
    var redactNextArg = false

    return lines.map { line ->
        val trimmed = line.trimEnd()
        val argvPrefix = ARG_LINE_REGEX.matchEntire(trimmed)
        if (argvPrefix != null) {
            val argValue = argvPrefix.groupValues[2]
            val sanitizedValue = when {
                redactNextArg -> "<redacted>"
                isSensitiveLauncherFlag(argValue) -> sanitizeSensitiveLauncherArg(argValue)
                else -> sanitizeGatewayLogLineUnbounded(argValue)
            }
            redactNextArg = argValue in SENSITIVE_LAUNCHER_NEXT_ARG_FLAGS
            return@map "${argvPrefix.groupValues[1]}$sanitizedValue"
        }

        redactNextArg = false
        sanitizeGatewayLogLineUnbounded(trimmed)
    }
}

private fun isSensitiveLauncherFlag(argValue: String): Boolean {
    return argValue in SENSITIVE_LAUNCHER_NEXT_ARG_FLAGS ||
        SENSITIVE_LAUNCHER_INLINE_FLAGS.any { flag -> argValue.startsWith("$flag=") }
}

private fun sanitizeSensitiveLauncherArg(argValue: String): String {
    SENSITIVE_LAUNCHER_INLINE_FLAGS.forEach { flag ->
        if (argValue.startsWith("$flag=")) {
            return "$flag=<redacted>"
        }
    }
    return sanitizeGatewayLogLineUnbounded(argValue)
}
private const val SECRET_KEY_PATTERN =
    "TELEGRAM_BOT_TOKEN|DISCORD_BOT_TOKEN|OPENROUTER_API_KEY|OPENAI_API_KEY|ANTHROPIC_API_KEY|GOOGLE_API_KEY|GEMINI_API_KEY|COPILOT_GITHUB_TOKEN|GH_TOKEN|GITHUB_TOKEN|ZAI_API_KEY|Z_AI_API_KEY|KIMI_API_KEY|KIMICODE_API_KEY|MINIMAX_API_KEY|BRAVE_API_KEY|BRAVE_SEARCH_API_KEY|API_KEY|API-KEY|AUTHORIZATION|COOKIE|PASSWORD|SECRET|TOKEN|ACCESS_TOKEN|REFRESH_TOKEN|ID_TOKEN|X_API_KEY|X-API-KEY"
private val JSON_SECRET_REGEX = Regex(
    "(?i)(\"(?:$SECRET_KEY_PATTERN)\"\\s*:\\s*\")([^\"]+)(\")"
)
private val AUTH_HEADER_REGEX = Regex(
    "(?i)(\\bauthorization\\b\\s*[:=]\\s*)[^,;\\r\\n]+"
)
private val COOKIE_HEADER_REGEX = Regex(
    "(?i)(\\bcookie\\b\\s*[:=]\\s*)[^,;\\r\\n]+"
)
private val KEY_VALUE_SECRET_REGEX = Regex(
    "(?i)\\b($SECRET_KEY_PATTERN)\\b\\s*([=:])\\s*[^\\s,;]+"
)
private val BEARER_REGEX = Regex("(?i)bearer\\s+[a-z0-9._\\-]+")
private val QUERY_SECRET_REGEX = Regex(
    "(?i)([?&](?:token|api_key|key|access_token|refresh_token|id_token|email_token)=)[^&\\s]+"
)
private val RAW_KEY_REGEX = Regex("\\b(?:sk|or|rk)-[A-Za-z0-9_\\-]{8,}\\b")
private val ARG_LINE_REGEX = Regex("""^(\[proroot] launcher: argv\[\d+]=)(.*)$""")
private val SENSITIVE_LAUNCHER_NEXT_ARG_FLAGS = setOf(
    "--api-key",
    "--token",
    "--password",
    "--header",
    "-H",
    "--cookie",
    "--authorization",
)
private val SENSITIVE_LAUNCHER_INLINE_FLAGS = setOf(
    "--api-key",
    "--token",
    "--password",
    "--cookie",
    "--authorization",
)

private fun sanitizeGatewayLogLineUnbounded(line: String): String {
    var sanitized = line
    sanitized = JSON_SECRET_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>${match.groupValues[3]}"
    }
    sanitized = AUTH_HEADER_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>"
    }
    sanitized = COOKIE_HEADER_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>"
    }
    sanitized = KEY_VALUE_SECRET_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
    }
    sanitized = BEARER_REGEX.replace(sanitized, "Bearer <redacted>")
    sanitized = QUERY_SECRET_REGEX.replace(sanitized) { match ->
        "${match.groupValues[1]}<redacted>"
    }
    sanitized = RAW_KEY_REGEX.replace(sanitized, "<redacted>")
    return sanitized
}

private const val MAX_OWNERSHIP_DIRECTORY_ENTRIES = 256
private const val MAX_OWNERSHIP_FILES = 4
private const val MAX_STABILITY_FILES = 3
private const val MAX_OWNERSHIP_BYTES = 256 * 1024
private const val MAX_STABILITY_BYTES = 5 * 1024 * 1024
private const val MAX_OWNERSHIP_ROW_BYTES = 16 * 1024
private const val MAX_OWNERSHIP_ROWS = 200
private const val MAX_DIAGNOSTIC_OUTPUT_BYTES = 128 * 1024
private const val MAX_DIAGNOSTIC_ARRAY_ITEMS = 1_024
private const val MAX_DIAGNOSTIC_STRING_CHARS = 16 * 1024
private const val MAX_DIAGNOSTIC_NODES = 100_000
private const val MAX_DIAGNOSTIC_DEPTH = 12
// Linux O_PATH permits descriptor-pinned traversal of search-only Android ancestors such as /data.
// OsConstants exposes no public O_PATH constant; its Linux ABI value is architecture-independent.
private const val DIAGNOSTIC_O_PATH = 0x200000

private val OWNERSHIP_FILE_NAME = Regex("""andclaw-ownership-(\d{1,10})-(\d{1,10})-[01]\.jsonl""")
private val STABILITY_FILE_NAME = Regex("""openclaw-stability-[A-Za-z0-9_.:-]{1,180}\.json""")
private val DIAGNOSTIC_TIMESTAMP = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z""")
private val DIAGNOSTIC_ERROR_CODES = setOf(
    "EACCES", "EPERM", "ENOENT", "EEXIST", "EIO", "EBADF", "EINVAL", "ELOOP", "ENOSPC",
    "EMFILE", "ENFILE", "ENOMEM", "EBUSY", "ETIMEDOUT", "EAGAIN", "EINTR", "ENOTDIR",
    "ESTALE", "ECANCELED", "ERR_WORKER_NOT_RUNNING", "ERR_WORKER_OUT_OF_MEMORY",
    "ERR_WORKER_INIT_FAILED", "ERR_SCRIPT_EXECUTION_TIMEOUT", "ABORT_ERR", "EFBIG", "UNKNOWN",
)
private val DIAGNOSTIC_ERROR_NAMES = setOf(
    "Error", "TypeError", "RangeError", "AggregateError", "AbortError", "TimeoutError", "SystemError",
)
private val STABILITY_REASONS = setOf(
    "gateway.restart_close_failed", "gateway.stop_close_failed", "gateway.startup_failed",
    "gateway.restart_shutdown_timeout", "gateway.stop_shutdown_timeout",
    "gateway.stop_native_unconfirmed", "uncaughtException", "unhandledRejection",
    "rss_threshold", "heap_threshold", "rss_growth", "unknown",
)
private val SHUTDOWN_STEPS = setOf(
    "restart-failure-recovery", "active-work-drain", "startup-operations",
    "gateway-server-close", "hosted-gateway-stop",
)
private val STABILITY_EVENT_TYPES = setOf(
    "diagnostic.heartbeat", "diagnostic.liveness.warning", "diagnostic.phase.completed",
    "diagnostic.memory.sample", "diagnostic.memory.pressure", "diagnostic.async_queue.dropped",
    "diagnostic.gc", "diagnostic.child_process.spawn", "gateway.event_loop.sample", "gateway.rpc",
)
private val MEMORY_NUMBERS = listOf(
    "rssBytes", "heapTotalBytes", "heapUsedBytes", "externalBytes", "arrayBuffersBytes",
    "workerCount", "workerHeapSampledCount", "workerHeapTotalBytes", "workerHeapUsedBytes",
    "workerExternalBytes", "workerArrayBuffersBytes", "workerArrayBuffersSampledCount",
)
private val STABILITY_EVENT_NUMBERS = listOf(
    "seq", "ts", "durationMs", "count", "thresholdBytes", "rssGrowthBytes", "windowMs", "ageMs",
    "queueDepth", "queueSize", "queueLength", "waitMs", "active", "waiting", "queued",
    "droppedEvents", "droppedTrustedEvents", "droppedUntrustedEvents", "droppedPriorityEvents",
    "maxQueueLength", "drainBatchSize", "cpuCoreRatio",
)
private val OWNERSHIP_NUMBERS = listOf(
    "v", "ts", "pid", "threadId", "workerThreadId", "sequence", "durationMs", "beatAgeMs",
    "intervalMs", "failureMs", "dev", "ino", "nlink", "elapsedBootMs", "lastBeatMs",
    "wallDeltaMs", "monotonicDeltaMs", "clockDeltaMs", "monoMs", "monoDeltaMs", "monitorLagMs",
    "bytes", "fd", "attempt", "nativePid", "exitCode",
)
private val OWNERSHIP_EVENTS = setOf(
    "process-start", "process-exit", "worker-created", "heartbeat-sample", "worker-exit",
    "worker-error", "stage-begin", "stage-end", "logger-error",
    "abort-issued", "abort-observed", "abort-diagnostics-limited",
)
private val OWNERSHIP_LOCK_KINDS = setOf("state", "config", "projection", "schema", "unknown")
private val OWNERSHIP_OPERATIONS = setOf(
    "openSync", "fstatSync", "statSync", "readSync", "futimesSync", "closeSync",
    "AbortController.abort", "AbortSignal.throwIfAborted", "cancellation",
)
private val OWNERSHIP_PHASES = setOf("begin", "end", "error", "issue", "throw", "limited")
private val CANCELLATION_EVENTS = setOf("abort-issued", "abort-observed", "abort-diagnostics-limited")
private val CANCELLATION_REASON_KINDS = setOf("default", "explicit", "unobserved")
private val CANCELLATION_REASON_CATEGORIES = setOf(
    "undefined", "null", "string", "number", "boolean", "bigint", "symbol", "function",
    "error", "abort-error", "timeout-error", "dom-exception", "object",
)
private val CANCELLATION_STAGES = setOf("scope-close", "scheduler-close", "row-preparation", "publication")
private val CANCELLATION_MODULES = setOf(
    "async-work-scope", "gateway-scheduler", "session-row-projection", "session-row-prepared-read",
    "session-event-prepared-row", "server-runtime-subscriptions",
)
private val CANCELLATION_FUNCTIONS = setOf(
    "unknown", "beginClose", "drain", "runWhenAllIdle", "run", "track", "enter", "registerWork",
    "stop", "close", "abort", "closeWork", "withPreparedRows", "assertActive", "withReadySessionRows",
    "select", "consumeCurrent", "withPreparedSessionRows", "consumePreparedSessionRows",
    "withReadyRows", "broadcastSessionActivitySummary", "onChanged",
)
private val OWNERSHIP_IDENTITIES = listOf("dev", "ino", "nlink")
private val WORKER_MEMORY_FIELDS = listOf("threadId", "heapUsed", "heapTotal", "external", "arrayBuffers", "sampleAgeMs")
private val WORKER_THREAD_FIELDS = listOf("threadId")
private val WORKER_MEMORY_SCOPE = setOf("direct")
private val WORKER_MEMORY_COVERAGE = setOf("complete", "partial", "unavailable")
private val WORKER_MEMORY_MISSING_REASONS = setOf("pending", "stale", "unavailable")
private val ERROR_CODE_FIELDS = listOf("code")
private val OWNERSHIP_CLOCKS = setOf("epoch", "unknown")
private const val NATIVE_OWNERSHIP_PREFIX = "[proroot-link-metadata] "
private val NATIVE_OWNERSHIP_PHASES = setOf("wait", "acquired", "failed", "release")
private val NATIVE_OWNERSHIP_OPERATIONS = setOf(
    "alias", "membership", "link", "unlink", "rename_replace", "rename_move", "stat", "statx",
)
private val NATIVE_OWNERSHIP_NUMBERS = listOf("v", "pid", "tid", "lockFd", "monotonicNs", "durationNs", "code")
private val NATIVE_CHILD_EXIT = Regex("""^\[proroot] launcher: child exited with code (\d{1,3})$""")
private val NATIVE_OWNERSHIP_PREFIX_BYTES = NATIVE_OWNERSHIP_PREFIX.toByteArray(StandardCharsets.US_ASCII)
private val NATIVE_CHILD_EXIT_PREFIX_BYTES = "[proroot] launcher: child exited with code ".toByteArray(StandardCharsets.US_ASCII)
// Unknown values are still strictly traversed/budgeted, but are not materialized in the report heap.
private val DIAGNOSTIC_INPUT_FIELDS = (
    OWNERSHIP_NUMBERS + MEMORY_NUMBERS + STABILITY_EVENT_NUMBERS + NATIVE_OWNERSHIP_NUMBERS +
        WORKER_MEMORY_FIELDS + listOf(
            "version", "event", "type", "generatedAt", "reason", "process", "uptimeMs", "error",
            "name", "message", "evidence", "shutdown", "step", "errors", "memoryPressure", "level",
            "activeResources", "total", "byType", "heapStatistics", "totalHeapSizeBytes",
            "totalHeapSizeExecutableBytes", "totalPhysicalSizeBytes", "totalAvailableSizeBytes",
            "usedHeapSizeBytes", "heapSizeLimitBytes", "mallocedMemoryBytes", "externalMemoryBytes",
            "snapshot", "capacity", "count", "dropped", "firstSeq", "lastSeq", "events", "summary",
            "memory", "latest", "maxRssBytes", "maxHeapUsedBytes", "pressureCount", "workerMemoryScope",
            "workerMemoryCoverage", "workerHeaps", "workerMemoryMissing", "phase", "operation",
            "lockKind", "lockId", "pendingOperation", "workerAlive", "clock",
            "signalId", "issuedMonoMs", "reasonKind", "reasonCategory", "stage", "frames",
            "module", "function", "line", "column", "limit", "windowMs",
            "FSReqCallback", "FSReqPromise", "FileHandle", "FileHandleCloseReq", "TCPServerWrap",
            "TCPSocketWrap", "TLSWrap", "PipeWrap", "ProcessWrap", "Timeout", "Immediate",
            "MessagePort", "Worker", "SignalWrap", "UDPWrap",
        )
    ).toSet()

private class DiagnosticCollectionFailure(val status: String) : Exception()

private fun collectOwnershipDiagnosticAttachments(rootfsDir: File): List<BugReportTextAttachment> {
    val attachments = ArrayList<BugReportTextAttachment>(MAX_OWNERSHIP_FILES + MAX_STABILITY_FILES + 1)
    val results = JSONArray()
    collectDiagnosticFamily(rootfsDir, false, attachments, results)
    collectDiagnosticFamily(rootfsDir, true, attachments, results)
    collectNativeOwnershipDiagnostic(rootfsDir, attachments, results)
    if (results.length() > 0) {
        attachments += BugReportTextAttachment(
            "runtime/openclaw/ownership-diagnostic-collection.json",
            JSONObject().put("version", 1).put("files", results).toString() + "\n",
        )
    }
    return attachments
}
// The caller's framework-provided app-private parent is the trusted anchor. Canonicalize only
// that prefix (Android may alias /data/user/0), then pin rootfs itself and every child nofollow.
private fun openDiagnosticDirectory(rootfsDir: File, relativeComponents: List<String>): ParcelFileDescriptor? {
    val rootfs = rootfsDir.absoluteFile
    val namedRoot = try { Os.lstat(rootfs.path) } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) return null
        throw DiagnosticCollectionFailure("directory_refused")
    }
    if (!OsConstants.S_ISDIR(namedRoot.st_mode)) throw DiagnosticCollectionFailure("directory_refused")
    val parent = rootfs.parentFile?.canonicalFile ?: throw DiagnosticCollectionFailure("directory_refused")
    val rootComponents = File(parent, rootfs.name).toPath().toString().split('/').filter { it.isNotEmpty() }
    val components = rootComponents + relativeComponents
    if (components.any { it == "." || it == ".." }) throw DiagnosticCollectionFailure("directory_refused")
    if (components.size > 64) throw DiagnosticCollectionFailure("directory_depth_limit")
    val flags = DIAGNOSTIC_O_PATH or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC
    fun openDirectory(path: String): ParcelFileDescriptor {
        val descriptor = Os.open(path, flags, 0)
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(descriptor).st_mode)) {
                throw DiagnosticCollectionFailure("directory_refused")
            }
            return ParcelFileDescriptor.dup(descriptor)
        } finally {
            Os.close(descriptor)
        }
    }
    var directory = openDirectory("/")
    try {
        for (index in components.indices) {
            val component = components[index]
            val next = openDirectory("/proc/self/fd/${directory.fd}/$component")
            val previous = directory
            directory = next
            previous.close()
            if (index == rootComponents.lastIndex) {
                val openedRoot = Os.fstat(next.fileDescriptor)
                if (openedRoot.st_dev != namedRoot.st_dev || openedRoot.st_ino != namedRoot.st_ino) {
                    throw DiagnosticCollectionFailure("directory_refused")
                }
            }
        }
        return directory
    } catch (error: Exception) {
        directory.close()
        if (error is ErrnoException && error.errno == OsConstants.ENOENT) return null
        throw DiagnosticCollectionFailure("directory_refused")
    }
}

private fun collectDiagnosticFamily(
    rootfsDir: File,
    stability: Boolean,
    attachments: MutableList<BugReportTextAttachment>,
    results: JSONArray,
) {
    val family = if (stability) "stability" else "ownership"
    try {
        val components = listOf("root", ".openclaw", "logs", if (stability) "stability" else "andclaw-ownership")
        val directory = openDiagnosticDirectory(rootfsDir, components) ?: return
        directory.use { pinned ->
            val directoryPath = File("/proc/self/fd/${pinned.fd}").toPath()
            class Candidate(val name: String, val modified: Long, val pid: String, val threadId: Long)
            val candidates = ArrayList<Candidate>()
            var visited = 0
            var scanLimited = false
            Files.newDirectoryStream(directoryPath).use { entries ->
                for (entry in entries) {
                    if (++visited > MAX_OWNERSHIP_DIRECTORY_ENTRIES) {
                        scanLimited = true
                        break
                    }
                    val name = entry.fileName.toString()
                    val match = (if (stability) STABILITY_FILE_NAME else OWNERSHIP_FILE_NAME)
                        .matchEntire(name) ?: continue
                    val modified = runCatching { Os.lstat(entry.toString()).st_mtime }.getOrDefault(0L)
                    candidates += Candidate(name, modified,
                        if (stability) "" else match.groupValues[1],
                        if (stability) 0L else match.groupValues[2].toLong())
                }
            }
            if (scanLimited) results.put(JSONObject().put("family", family).put("status", "directory_entry_limit"))
            candidates.sortWith(compareByDescending<Candidate> { it.modified }.thenByDescending { it.name })
            val maximum = if (stability) MAX_STABILITY_FILES else MAX_OWNERSHIP_FILES
            if (candidates.size > maximum) {
                results.put(JSONObject().put("family", family).put("status", "file_count_limit")
                    .put("omittedFiles", candidates.size - maximum))
            }
            val selected = ArrayList<Candidate>(maximum)
            if (!stability) {
                // Only ownership Workers emit nonzero-thread files. Preserve
                // their last operation plus the same process's main observer;
                // later short-lived CLI helpers must not crowd out both.
                for (candidate in candidates) {
                    if (selected.size == maximum / 2) break
                    if (candidate.threadId > 0) selected.add(candidate)
                }
                val workerCount = selected.size
                for (candidate in candidates) {
                    if (selected.size == maximum) break
                    if (candidate.threadId != 0L) continue
                    for (workerIndex in 0 until workerCount) {
                        if (candidate.pid == selected[workerIndex].pid) {
                            selected.add(candidate)
                            break
                        }
                    }
                }
            }
            for (candidate in candidates) {
                if (selected.size == maximum) break
                if (candidate !in selected) selected.add(candidate)
            }
            selected.forEachIndexed { index, candidate ->
                // Original filenames/reason suffixes are not report content: they can carry user text.
                val entryName = if (stability) "runtime/openclaw/stability-${index + 1}.json"
                else "runtime/openclaw/andclaw-ownership-${index + 1}.jsonl"
                val result = JSONObject().put("family", family).put("attachment", entryName)
                try {
                    val bytes = readDiagnosticBytes(pinned, candidate.name, stability, result)
                    val content = if (stability) projectStabilityBundle(parseDiagnosticJson(decodeDiagnosticBytes(bytes)))
                        .toString() + "\n"
                    else projectOwnershipRows(bytes, result)
                    if (content.toByteArray(StandardCharsets.UTF_8).size > MAX_DIAGNOSTIC_OUTPUT_BYTES) {
                        throw DiagnosticCollectionFailure("output_byte_limit")
                    }
                    attachments += BugReportTextAttachment(entryName, content)
                    result.put("status", "collected")
                } catch (error: Exception) {
                    result.put("status", (error as? DiagnosticCollectionFailure)?.status ?: "read_failed")
                }
                results.put(result)
            }
        }
    } catch (error: Exception) {
        results.put(JSONObject().put("family", family)
            .put("status", (error as? DiagnosticCollectionFailure)?.status ?: "directory_read_failed"))
    }
}

private fun sameDiagnosticFile(left: StructStat, right: StructStat): Boolean =
    OsConstants.S_ISREG(right.st_mode) && right.st_nlink == 1L &&
        left.st_dev == right.st_dev && left.st_ino == right.st_ino

private fun readDiagnosticBytes(
    directory: ParcelFileDescriptor,
    name: String,
    stability: Boolean,
    result: JSONObject,
): ByteArray {
    val path = "/proc/self/fd/${directory.fd}/$name"
    val before = Os.lstat(path)
    if (!OsConstants.S_ISREG(before.st_mode) || before.st_nlink != 1L) {
        throw DiagnosticCollectionFailure("file_refused")
    }
    val limit = if (stability) MAX_STABILITY_BYTES else MAX_OWNERSHIP_BYTES
    if (stability && before.st_size > limit) throw DiagnosticCollectionFailure("input_byte_limit")
    val descriptor = Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
        OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK, 0)
    try {
        val opened = Os.fstat(descriptor)
        if (!sameDiagnosticFile(before, opened) || opened.st_size < 0) {
            throw DiagnosticCollectionFailure("file_changed")
        }
        if (stability && opened.st_size > limit) throw DiagnosticCollectionFailure("input_byte_limit")
        result.put("sourceBytes", opened.st_size)
        val start = if (stability) 0L else maxOf(0L, opened.st_size - limit)
        val bytes = ByteArray(minOf(opened.st_size - start, limit.toLong()).toInt())
        var partialHead = false
        if (start > 0) {
            result.put("inputTailTruncated", true)
            Os.lseek(descriptor, start - 1, OsConstants.SEEK_SET)
            val previous = ByteArray(1)
            if (Os.read(descriptor, previous, 0, 1) != 1) throw DiagnosticCollectionFailure("file_changed")
            partialHead = previous[0] != '\n'.code.toByte()
        }
        Os.lseek(descriptor, start, OsConstants.SEEK_SET)
        var read = 0
        while (read < bytes.size) {
            val count = Os.read(descriptor, bytes, read, bytes.size - read)
            if (count <= 0) throw DiagnosticCollectionFailure("file_changed")
            read += count
        }
        val after = Os.fstat(descriptor)
        if (!sameDiagnosticFile(opened, after) || !sameDiagnosticFile(opened, Os.lstat(path)) ||
            stability && (opened.st_size != after.st_size || opened.st_mtime != after.st_mtime) ||
            !stability && after.st_size < opened.st_size
        ) throw DiagnosticCollectionFailure("file_changed")
        if (partialHead) {
            val firstBreak = bytes.indexOf('\n'.code.toByte())
            result.put("partialHeadDropped", true)
            return if (firstBreak < 0) ByteArray(0) else bytes.copyOfRange(firstBreak + 1, bytes.size)
        }
        return bytes
    } finally {
        Os.close(descriptor)
    }
}

// Strict token parsing with budgets before recursive construction, including unknown fields.
// JSONObject(String) alone is permissive and can recurse without a depth bound.
private fun parseDiagnosticJson(text: String): JSONObject {
    try {
        JsonReader(StringReader(text)).use { reader ->
            reader.isLenient = false
            var nodes = 0
            fun value(depth: Int, retain: Boolean): Any {
                if (depth > MAX_DIAGNOSTIC_DEPTH) throw DiagnosticCollectionFailure("json_depth_limit")
                if (++nodes > MAX_DIAGNOSTIC_NODES) throw DiagnosticCollectionFailure("json_node_limit")
                return when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        val result = if (retain) JSONObject() else null
                        val seenKeys = HashSet<String>()
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val key = reader.nextName()
                            if (key.length > MAX_DIAGNOSTIC_STRING_CHARS) throw DiagnosticCollectionFailure("json_string_limit")
                            if (!seenKeys.add(key)) throw DiagnosticCollectionFailure("invalid_json")
                            val keep = retain && key in DIAGNOSTIC_INPUT_FIELDS
                            val child = value(depth + 1, keep)
                            if (keep) result?.put(key, child)
                        }
                        reader.endObject()
                        result ?: JSONObject.NULL
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        val result = if (retain) JSONArray() else null
                        var count = 0
                        reader.beginArray()
                        while (reader.hasNext()) {
                            if (++count > MAX_DIAGNOSTIC_ARRAY_ITEMS) {
                                throw DiagnosticCollectionFailure("json_array_limit")
                            }
                            val child = value(depth + 1, retain)
                            result?.put(child)
                        }
                        reader.endArray()
                        result ?: JSONObject.NULL
                    }
                    JsonToken.STRING -> if (retain) {
                        reader.nextString().also {
                            if (it.length > MAX_DIAGNOSTIC_STRING_CHARS) throw DiagnosticCollectionFailure("json_string_limit")
                        }
                    } else { reader.skipValue(); JSONObject.NULL }
                    JsonToken.NUMBER -> if (!retain) {
                        reader.skipValue()
                        JSONObject.NULL
                    } else {
                        val number = reader.nextString()
                        number.toLongOrNull() ?: number.toDoubleOrNull()?.takeIf { it.isFinite() }
                            ?: throw DiagnosticCollectionFailure("invalid_json")
                    }
                    JsonToken.BOOLEAN -> reader.nextBoolean()
                    JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
                    else -> throw DiagnosticCollectionFailure("invalid_json")
                }
            }
            val result = value(0, true) as? JSONObject ?: throw DiagnosticCollectionFailure("invalid_json")
            if (reader.peek() != JsonToken.END_DOCUMENT) throw DiagnosticCollectionFailure("invalid_json")
            return result
        }
    } catch (error: DiagnosticCollectionFailure) {
        throw error
    } catch (_: Exception) {
        throw DiagnosticCollectionFailure("invalid_json")
    }
}

private fun diagnosticNumbers(source: JSONObject, keys: List<String>): JSONObject {
    val result = JSONObject()
    for (key in keys) {
        val value = source.opt(key) as? Number ?: continue
        if (value.toDouble().isFinite()) result.put(key, value)
    }
    return result
}

private fun diagnosticEnum(source: JSONObject, target: JSONObject, key: String, allowed: Set<String>) {
    val value = source.opt(key) as? String ?: return
    if (value in allowed) target.put(key, value)
}

private fun diagnosticTimestamp(source: JSONObject, target: JSONObject, key: String) {
    val value = source.opt(key) as? String ?: return
    if (DIAGNOSTIC_TIMESTAMP.matches(value)) target.put(key, value)
}

private fun projectDiagnosticError(source: JSONObject): JSONObject =
    diagnosticNumbers(source, ERROR_CODE_FIELDS).also { result ->
        diagnosticEnum(source, result, "code", DIAGNOSTIC_ERROR_CODES)
        diagnosticEnum(source, result, "name", DIAGNOSTIC_ERROR_NAMES)
        val message = source.opt("message") as? String
        val category = when {
            message?.startsWith("Plugin runtime still owns resources") == true -> "plugin_resources_retained"
            message?.startsWith("utimes heartbeat renewal did not complete within 60 seconds") == true -> "heartbeat_renewal_deadline"
            else -> null
        }
        if (category != null) result.put("errorCategory", category)
        // Message, stack, cause and arbitrary nested errors can contain credentials or conversation.
        result.put("errorPresent", true)
    }

private fun projectDiagnosticMemory(source: JSONObject): JSONObject =
    diagnosticNumbers(source, MEMORY_NUMBERS).also { result ->
        diagnosticEnum(source, result, "workerMemoryScope", WORKER_MEMORY_SCOPE)
        diagnosticEnum(source, result, "workerMemoryCoverage", WORKER_MEMORY_COVERAGE)
        source.optJSONArray("workerHeaps")?.let { workers ->
            val projected = JSONArray()
            for (index in 0 until minOf(workers.length(), 32)) {
                val worker = workers.optJSONObject(index) ?: continue
                projected.put(diagnosticNumbers(worker, WORKER_MEMORY_FIELDS))
            }
            result.put("workerHeaps", projected).put("workerHeapsOmitted", maxOf(0, workers.length() - 32))
        }
        source.optJSONArray("workerMemoryMissing")?.let { workers ->
            val projected = JSONArray()
            for (index in 0 until minOf(workers.length(), 32)) {
                val worker = workers.optJSONObject(index) ?: continue
                val entry = diagnosticNumbers(worker, WORKER_THREAD_FIELDS)
                diagnosticEnum(worker, entry, "reason", WORKER_MEMORY_MISSING_REASONS)
                projected.put(entry)
            }
            result.put("workerMemoryMissing", projected)
                .put("workerMemoryMissingOmitted", maxOf(0, workers.length() - 32))
        }
    }

private fun projectStabilityBundle(source: JSONObject): JSONObject {
    if ((source.opt("version") as? Number)?.toDouble() != 1.0) throw DiagnosticCollectionFailure("unsupported_version")
    val process = source.optJSONObject("process") ?: throw DiagnosticCollectionFailure("invalid_schema")
    val snapshot = source.optJSONObject("snapshot") ?: throw DiagnosticCollectionFailure("invalid_schema")
    val events = snapshot.optJSONArray("events") ?: throw DiagnosticCollectionFailure("invalid_schema")
    val result = JSONObject().put("version", 1)
    diagnosticTimestamp(source, result, "generatedAt")
    diagnosticEnum(source, result, "reason", STABILITY_REASONS)
    result.put("process", diagnosticNumbers(process, listOf("pid", "uptimeMs")))
    source.optJSONObject("error")?.let { result.put("error", projectDiagnosticError(it)) }
    source.optJSONObject("evidence")?.let { evidence ->
        val projected = JSONObject()
        evidence.optJSONObject("shutdown")?.let { shutdown ->
            val target = JSONObject()
            diagnosticEnum(shutdown, target, "step", SHUTDOWN_STEPS)
            shutdown.optJSONArray("errors")?.let { errors ->
                val safeErrors = JSONArray()
                for (index in 0 until minOf(errors.length(), 32)) {
                    errors.optJSONObject(index)?.let { safeErrors.put(projectDiagnosticError(it)) }
                }
                target.put("errors", safeErrors).put("errorsOmitted", maxOf(0, errors.length() - 32))
            }
            projected.put("shutdown", target)
        }
        evidence.optJSONObject("memoryPressure")?.let { pressure ->
            val target = diagnosticNumbers(pressure, listOf("thresholdBytes", "rssGrowthBytes", "windowMs"))
            diagnosticEnum(pressure, target, "level", setOf("warning", "critical"))
            diagnosticEnum(pressure, target, "reason", setOf("rss_threshold", "heap_threshold", "rss_growth"))
            pressure.optJSONObject("memory")?.let { target.put("memory", projectDiagnosticMemory(it)) }
            pressure.optJSONObject("activeResources")?.let { resources ->
                val active = diagnosticNumbers(resources, listOf("total"))
                resources.optJSONObject("byType")?.let { types ->
                    active.put("byType", diagnosticNumbers(types, listOf(
                        "FSReqCallback", "FSReqPromise", "FileHandle", "FileHandleCloseReq",
                        "TCPServerWrap", "TCPSocketWrap", "TLSWrap", "PipeWrap", "ProcessWrap",
                        "Timeout", "Immediate", "MessagePort", "Worker", "SignalWrap", "UDPWrap",
                    )))
                }
                target.put("activeResources", active)
            }
            pressure.optJSONObject("heapStatistics")?.let { statistics ->
                target.put("heapStatistics", diagnosticNumbers(statistics, listOf(
                    "totalHeapSizeBytes", "totalHeapSizeExecutableBytes", "totalPhysicalSizeBytes",
                    "totalAvailableSizeBytes", "usedHeapSizeBytes", "heapSizeLimitBytes",
                    "mallocedMemoryBytes", "externalMemoryBytes",
                )))
            }
            projected.put("memoryPressure", target)
        }
        result.put("evidence", projected)
    }
    val projectedSnapshot = diagnosticNumbers(snapshot, listOf("capacity", "count", "dropped", "firstSeq", "lastSeq"))
    diagnosticTimestamp(snapshot, projectedSnapshot, "generatedAt")
    snapshot.optJSONObject("summary")?.optJSONObject("memory")?.let { memory ->
        val target = diagnosticNumbers(memory, listOf("maxRssBytes", "maxHeapUsedBytes", "pressureCount"))
        memory.optJSONObject("latest")?.let { target.put("latest", projectDiagnosticMemory(it)) }
        projectedSnapshot.put("summary", JSONObject().put("memory", target))
    }
    projectedSnapshot.put("events", JSONArray()).put("eventsOmitted", events.length())
    val eventBudget = MAX_DIAGNOSTIC_OUTPUT_BYTES - result.put("snapshot", projectedSnapshot).toString().length - 128
    val retainedEvents = ArrayDeque<Pair<JSONObject, Int>>(MAX_OWNERSHIP_ROWS)
    var eventBytes = 0
    for (index in 0 until events.length()) {
        val event = events.optJSONObject(index) ?: continue
        val type = event.opt("type") as? String ?: continue
        if (type !in STABILITY_EVENT_TYPES) continue
        val target = diagnosticNumbers(event, STABILITY_EVENT_NUMBERS).put("type", type)
        diagnosticEnum(event, target, "level", setOf("info", "warning", "critical"))
        diagnosticEnum(event, target, "reason", setOf("rss_threshold", "heap_threshold", "rss_growth"))
        diagnosticEnum(event, target, "phase", setOf(
            "startup.maintenance", "gateway.startup", "gateway.close", "plugin.runtime.close",
            "gateway.restart", "gateway.state-owner", "gateway.state-lock",
        ))
        event.optJSONObject("memory")?.let { target.put("memory", projectDiagnosticMemory(it)) }
        val bytes = target.toString().length + 1 // Structural projection is ASCII-only.
        if (bytes > eventBudget) continue
        while (retainedEvents.isNotEmpty() && (retainedEvents.size >= MAX_OWNERSHIP_ROWS ||
            eventBytes + bytes > eventBudget)) {
            eventBytes -= retainedEvents.removeFirst().second
        }
        retainedEvents.addLast(target to bytes)
        eventBytes += bytes
    }
    val projectedEvents = JSONArray()
    retainedEvents.forEach { projectedEvents.put(it.first) }
    projectedSnapshot.put("events", projectedEvents).put("eventsOmitted", events.length() - projectedEvents.length())
    return result.put("snapshot", projectedSnapshot)
}

private fun projectOwnershipRows(bytes: ByteArray, result: JSONObject): String {
    val rows = decodeDiagnosticBytes(bytes).lineSequence().filter { it.isNotBlank() }
    val retained = ArrayDeque<String>(MAX_OWNERSHIP_ROWS)
    var invalid = 0
    var oversized = 0
    var rejected = 0
    var outputBytes = 0
    var omitted = 0
    for (row in rows) {
        if (row.toByteArray(StandardCharsets.UTF_8).size > MAX_OWNERSHIP_ROW_BYTES) {
            oversized++
            continue
        }
        val source = try { parseDiagnosticJson(row) } catch (_: DiagnosticCollectionFailure) {
            invalid++
            continue
        }
        if ((source.opt("v") as? Number)?.toDouble() != 1.0) { rejected++; continue }
        val projected = projectOwnershipRow(source)
        if (!projected.has("event")) { rejected++; continue }
        val text = projected.toString() + "\n"
        val size = text.length // Projected keys, enums, timestamps and numeric values are ASCII only.
        while (retained.isNotEmpty() && (retained.size >= MAX_OWNERSHIP_ROWS ||
            outputBytes + size > MAX_DIAGNOSTIC_OUTPUT_BYTES)) {
            outputBytes -= retained.removeFirst().length
            omitted++
        }
        retained.addLast(text)
        outputBytes += size
    }
    result.put("rowsCollected", retained.size).put("invalidRows", invalid).put("oversizedRows", oversized)
        .put("rejectedRows", rejected).put("rowsOmitted", omitted)
    return retained.joinToString("")
}

private fun projectOwnershipRow(source: JSONObject): JSONObject {
    val target = diagnosticNumbers(source, OWNERSHIP_NUMBERS)
    diagnosticTimestamp(source, target, "ts")
    diagnosticEnum(source, target, "event", OWNERSHIP_EVENTS)
    diagnosticEnum(source, target, "lockKind", OWNERSHIP_LOCK_KINDS)
    diagnosticEnum(source, target, "operation", OWNERSHIP_OPERATIONS)
    diagnosticEnum(source, target, "pendingOperation", OWNERSHIP_OPERATIONS)
    diagnosticEnum(source, target, "phase", OWNERSHIP_PHASES)
    diagnosticEnum(source, target, "code", DIAGNOSTIC_ERROR_CODES)
    diagnosticEnum(source, target, "clock", OWNERSHIP_CLOCKS)
    for (key in OWNERSHIP_IDENTITIES) {
        val value = source.opt(key) as? String ?: continue
        if (value.length in 1..20 && value.all { it in '0'..'9' }) target.put(key, value)
    }
    (source.opt("lockId") as? String)?.let { value ->
        if (value.length == 16 && value.all { it in '0'..'9' || it in 'a'..'f' }) target.put("lockId", value)
    }
    (source.opt("workerAlive") as? Boolean)?.let { target.put("workerAlive", it) }
    if (target.optString("event") in CANCELLATION_EVENTS) projectCancellationDiagnostic(source, target)
    return target
}

private fun diagnosticBoundedInteger(source: JSONObject, target: JSONObject, key: String, maximum: Double) {
    val number = source.opt(key) as? Number ?: return
    val value = number.toDouble()
    if (value.isFinite() && value >= 1 && value <= maximum && value % 1.0 == 0.0) target.put(key, number)
}

private fun projectCancellationDiagnostic(source: JSONObject, target: JSONObject) {
    diagnosticBoundedInteger(source, target, "signalId", 9007199254740991.0)
    (source.opt("issuedMonoMs") as? Number)?.let { value ->
        val milliseconds = value.toDouble()
        if (milliseconds.isFinite() && milliseconds >= 0) target.put("issuedMonoMs", value)
    }
    diagnosticEnum(source, target, "reasonKind", CANCELLATION_REASON_KINDS)
    diagnosticEnum(source, target, "reasonCategory", CANCELLATION_REASON_CATEGORIES)
    diagnosticEnum(source, target, "stage", CANCELLATION_STAGES)
    if (target.optString("event") == "abort-diagnostics-limited") {
        if ((source.opt("limit") as? Number)?.toDouble() == 128.0) target.put("limit", 128)
        if ((source.opt("windowMs") as? Number)?.toDouble() == 60000.0) target.put("windowMs", 60000)
    }
    source.optJSONArray("frames")?.let { frames ->
        val projected = JSONArray()
        for (index in 0 until minOf(frames.length(), 8)) {
            val frame = frames.optJSONObject(index) ?: continue
            val module = frame.opt("module") as? String ?: continue
            if (module !in CANCELLATION_MODULES) continue
            val entry = JSONObject().put("module", module)
            diagnosticEnum(frame, entry, "function", CANCELLATION_FUNCTIONS)
            diagnosticBoundedInteger(frame, entry, "line", 10000000.0)
            diagnosticBoundedInteger(frame, entry, "column", 10000000.0)
            if (entry.has("line") && entry.has("column")) projected.put(entry)
        }
        target.put("frames", projected).put("framesOmitted", frames.length() - projected.length())
    }
}

private fun collectNativeOwnershipDiagnostic(
    rootfsDir: File,
    attachments: MutableList<BugReportTextAttachment>,
    results: JSONArray,
) {
    val result = JSONObject().put("family", "native").put("attachment", "runtime/proroot-gateway.log")
    try {
        val directory = openDiagnosticDirectory(rootfsDir, listOf("tmp")) ?: return
        directory.use { pinned ->
            val bytes = readDiagnosticBytes(pinned, "proroot-gateway.log", false, result)
            val content = projectNativeOwnershipRows(bytes, result)
            attachments += BugReportTextAttachment("runtime/proroot-gateway.log", content)
            result.put("status", "collected")
        }
    } catch (error: Exception) {
        if (error is ErrnoException && error.errno == OsConstants.ENOENT) return
        result.put("status", (error as? DiagnosticCollectionFailure)?.status ?: "read_failed")
    }
    results.put(result)
}

private fun decodeDiagnosticBytes(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): String = try {
    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, offset, length)).toString()
} catch (_: Exception) {
    throw DiagnosticCollectionFailure("invalid_utf8")
}

private fun projectNativeOwnershipRows(bytes: ByteArray, result: JSONObject): String {
    val retained = ArrayDeque<String>(MAX_SUPPLEMENTAL_RUNTIME_LOG_LINES_PER_FILE)
    var invalid = 0
    var rejected = 0
    var legacy = 0
    var omitted = 0
    var outputBytes = 0
    var nextRow = 0
    while (nextRow < bytes.size) {
        val start = nextRow
        var end = start
        while (end < bytes.size && bytes[end] != '\n'.code.toByte()) end++
        nextRow = end + 1
        if (end > start && bytes[end - 1] == '\r'.code.toByte()) end--
        if (end == start) continue
        if (!diagnosticBytesHavePrefix(bytes, start, end, NATIVE_OWNERSHIP_PREFIX_BYTES) &&
            !diagnosticBytesHavePrefix(bytes, start, end, NATIVE_CHILD_EXIT_PREFIX_BYTES)
        ) { legacy++; continue }
        if (end - start > MAX_OWNERSHIP_ROW_BYTES) { invalid++; continue }
        val line = try { decodeDiagnosticBytes(bytes, start, end - start) }
        catch (_: DiagnosticCollectionFailure) { invalid++; continue }
        val exit = NATIVE_CHILD_EXIT.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull()
        val projected = when {
            exit != null -> JSONObject().put("v", 1).put("event", "child-exit").put("exitCode", exit)
            line.startsWith(NATIVE_OWNERSHIP_PREFIX) -> {
                val source = try { parseDiagnosticJson(line.removePrefix(NATIVE_OWNERSHIP_PREFIX)) }
                catch (_: DiagnosticCollectionFailure) { invalid++; continue }
                if ((source.opt("v") as? Number)?.toDouble() != 1.0) { rejected++; continue }
                val target = diagnosticNumbers(source, NATIVE_OWNERSHIP_NUMBERS)
                diagnosticEnum(source, target, "phase", NATIVE_OWNERSHIP_PHASES)
                diagnosticEnum(source, target, "operation", NATIVE_OWNERSHIP_OPERATIONS)
                if (!target.has("phase") || !target.has("operation")) { rejected++; continue }
                target
            }
            else -> { rejected++; continue } // A recognized terminal prefix with an invalid code.
        }
        val row = projected.toString() + "\n"
        while (retained.isNotEmpty() && (retained.size >= MAX_SUPPLEMENTAL_RUNTIME_LOG_LINES_PER_FILE ||
            outputBytes + row.length > MAX_DIAGNOSTIC_OUTPUT_BYTES)) {
            outputBytes -= retained.removeFirst().length
            omitted++
        }
        retained.addLast(row)
        outputBytes += row.length
    }
    result.put("rowsCollected", retained.size).put("invalidRows", invalid).put("rejectedRows", rejected)
        .put("legacyLinesExcluded", legacy).put("rowsOmitted", omitted)
    return retained.joinToString("")
}

private fun diagnosticBytesHavePrefix(bytes: ByteArray, start: Int, end: Int, prefix: ByteArray): Boolean {
    if (end - start < prefix.size) return false
    for (index in prefix.indices) {
        if (bytes[start + index] != prefix[index]) return false
    }
    return true
}
