package com.coderred.andclaw.proot

import android.os.Build
import android.os.FileObserver
import android.os.SystemClock
import android.util.Log
import com.coderred.andclaw.data.ChannelConfig
import com.coderred.andclaw.data.GatewayState
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.GatewaySurvivorMetadata
import com.coderred.andclaw.data.PairingRequest
import com.coderred.andclaw.data.SessionLogEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

internal fun githubCopilotAuthEnv(env: Map<String, String> = System.getenv()): Map<String, String> =
    buildMap {
        listOf("COPILOT_GITHUB_TOKEN", "GH_TOKEN", "GITHUB_TOKEN").forEach { key ->
            env[key]?.takeIf { it.isNotBlank() }?.let { put(key, it) }
        }
    }

private val ANDROID_CHROMIUM_EXTRA_ARGS = listOf("--no-zygote", "--single-process")
private const val LEGACY_CHROMIUM_WRAPPER_NAME = "chromium-proot-wrapper.sh"

internal fun parseListeningSocketInodes(procNetContent: String, port: Int): Set<String> {
    if (port !in 1..65535) return emptySet()

    val targetPortHex = port.toString(16).uppercase().padStart(4, '0')

    return procNetContent
        .lineSequence()
        .drop(1) // 헤더 제외
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split("\\s+".toRegex())
            val localAddress = parts.getOrNull(1) ?: return@mapNotNull null
            val state = parts.getOrNull(3) ?: return@mapNotNull null
            val inode = parts.getOrNull(9) ?: return@mapNotNull null

            if (state != "0A") return@mapNotNull null // LISTEN
            val localPortHex = localAddress.substringAfterLast(':', "").uppercase()
            if (localPortHex != targetPortHex) return@mapNotNull null
            inode.takeIf { it.isNotBlank() && it.all(Char::isDigit) }
        }
        .toSet()
}

internal fun extractSocketInode(fdTarget: String): String? {
    val match = Regex("""socket:\[(\d+)]""").matchEntire(fdTarget.trim()) ?: return null
    return match.groupValues[1]
}

internal fun shouldReattachGatewaySurvivor(
    metadata: GatewaySurvivorMetadata?,
    pidAlive: Boolean,
    healthCheckPassed: Boolean,
    expectedEndpoint: String = "127.0.0.1:18789",
): Boolean {
    if (metadata == null) return false
    if (metadata.startupAttemptActive) return false
    if (!pidAlive || !healthCheckPassed) return false
    val endpoint = metadata.wsEndpoint.trim().lowercase()
    return endpoint == expectedEndpoint || endpoint == "localhost:18789"
}

internal fun buildOpenClawPairingDenyScript(): String = """
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import crypto from "node:crypto";
import { pathToFileURL } from "node:url";

// Inline os.networkInterfaces() patch for proot compatibility (Node.js v24+ --require + --input-type=module conflict)
const _origNI = os.networkInterfaces;
os.networkInterfaces = function() {
  try { return _origNI.call(this); } catch {
    return { lo: [{ address: "127.0.0.1", netmask: "255.0.0.0", family: "IPv4", mac: "00:00:00:00:00:00", internal: true, cidr: "127.0.0.1/8" }] };
  }
};

const channelInput = process.env.DENY_CHANNEL || process.argv.slice(1).filter(a => a !== "-")[0];
const codeInput = process.env.DENY_CODE || process.argv.slice(1).filter(a => a !== "-")[1];

function normalizeChannel(value) {
  const normalized = String(value ?? "").trim().toLowerCase();
  if (!/^[a-z][a-z0-9_-]{0,63}$/.test(normalized)) {
    throw new Error("invalid channel");
  }
  return normalized;
}

function normalizeCode(value) {
  const normalized = String(value ?? "").trim().toUpperCase();
  if (!/^[A-Z0-9-]{4,32}$/.test(normalized)) {
    throw new Error("invalid code");
  }
  return normalized;
}

function resolveHomeDir() {
  const home = typeof process.env.HOME === "string" ? process.env.HOME.trim() : "";
  return home || os.homedir();
}

function resolveCredentialsDir() {
  return path.join(resolveHomeDir(), ".openclaw", "credentials");
}

function safeChannelKey(channel) {
  const raw = String(channel ?? "").trim().toLowerCase();
  if (!raw) throw new Error("invalid pairing channel");
  const safe = raw.replace(/[\\/:*?"<>|]/g, "_").replace(/\.\./g, "_");
  if (!safe || safe === "_") throw new Error("invalid pairing channel");
  return safe;
}

function isValidAccountId(accountId) {
  const raw = String(accountId ?? "").trim().toLowerCase();
  if (!raw) return false;
  const safe = raw.replace(/[\\/:*?"<>|]/g, "_").replace(/\.\./g, "_").trim();
  return Boolean(safe && safe !== "_");
}

function resolvePairingPath(channel) {
  return path.join(resolveCredentialsDir(), safeChannelKey(channel) + "-pairing.json");
}

async function writeJsonAtomic(filePath, value) {
  const dir = path.dirname(filePath);
  await fs.promises.mkdir(dir, { recursive: true, mode: 0o700 });
  const tmpPath = path.join(dir, path.basename(filePath) + "." + crypto.randomUUID() + ".tmp");
  await fs.promises.writeFile(tmpPath, JSON.stringify(value, null, 2) + "\n", "utf-8");
  await fs.promises.chmod(tmpPath, 0o600);
  await fs.promises.rename(tmpPath, filePath);
}

async function readPairingStore(filePath) {
  try {
    const raw = await fs.promises.readFile(filePath, "utf-8");
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === "object" && Array.isArray(parsed.requests)) {
      return parsed;
    }
  } catch {
  }
  return { version: 1, requests: [] };
}

async function ensureJsonFile(filePath, fallback) {
  try {
    await fs.promises.access(filePath);
  } catch {
    await writeJsonAtomic(filePath, fallback);
  }
}

function resolveDistDir() {
  const envDir = typeof process.env.OPENCLAW_DIST_DIR === "string" ? process.env.OPENCLAW_DIST_DIR.trim() : "";
  return envDir || "/usr/local/lib/node_modules/openclaw/dist";
}

function findExportByName(moduleExports, targetName) {
  // minified export에서 원본 이름으로 re-export된 경우 (export { x as originalName })
  if (typeof moduleExports[targetName] === "function") return moduleExports[targetName];
  // minified: export 이름을 모르니 모든 function export를 검사하여 .name으로 매칭
  for (const key of Object.keys(moduleExports)) {
    const val = moduleExports[key];
    if (typeof val === "function" && val.name === targetName) return val;
  }
  return null;
}

async function importDistExport(prefix, exportName) {
  const distDir = resolveDistDir();
  const candidates = fs.readdirSync(distDir).filter((name) => name.startsWith(prefix) && name.endsWith(".js")).sort();
  if (candidates.length === 0) {
    throw new Error("missing openclaw module: " + prefix);
  }
  const errors = [];
  for (const fileName of candidates) {
    const modulePath = path.join(distDir, fileName);
    let loaded;
    try {
      loaded = await import(pathToFileURL(modulePath).href);
    } catch (error) {
      errors.push(fileName + " import failed: " + (error instanceof Error ? error.message : String(error)));
      continue;
    }
    const fn = findExportByName(loaded, exportName);
    if (fn) return fn;
    errors.push(fileName + " missing: " + exportName);
  }
  throw new Error("no module for " + prefix + " with " + exportName + ": " + errors.join("; "));
}

const removeChannelAllowFromStoreEntry = await importDistExport("pairing-store-", "removeChannelAllowFromStoreEntry");
const withFileLock = await importDistExport("file-lock-", "withFileLock");

const lockOptions = {
  retries: {
    retries: 10,
    factor: 2,
    minTimeout: 100,
    maxTimeout: 10000,
    randomize: true
  },
  stale: 30000
};

const channel = normalizeChannel(channelInput);
const code = normalizeCode(codeInput);
const pairingPath = resolvePairingPath(channel);
let removedRequest = null;

await ensureJsonFile(pairingPath, { version: 1, requests: [] });
await withFileLock(pairingPath, lockOptions, async () => {
  const store = await readPairingStore(pairingPath);
  const requests = Array.isArray(store.requests) ? store.requests : [];
  const index = requests.findIndex((request) => String(request?.code ?? "").trim().toUpperCase() === code);
  if (index < 0) return;
  removedRequest = requests[index] ?? null;
  requests.splice(index, 1);
  await writeJsonAtomic(pairingPath, {
    version: 1,
    requests
  });
});

if (!removedRequest) {
  console.error("pairing_not_found");
  process.exit(3);
}

const entryId = String(removedRequest?.id ?? "").trim();
const accountId = String(removedRequest?.meta?.accountId ?? "").trim();

if (entryId) {
  await removeChannelAllowFromStoreEntry({ channel, entry: entryId });
  if (isValidAccountId(accountId)) {
    await removeChannelAllowFromStoreEntry({ channel, entry: entryId, accountId });
  }
}

console.log(JSON.stringify({
  ok: true,
  id: entryId || null,
  accountId: accountId || null
}));
""".trimIndent()

/**
 * proot 환경에서 OpenClaw 게이트웨이 프로세스를 관리한다.
 *
 * - start(): proot 내에서 openclaw 프로세스 시작
 * - stop(): 프로세스 종료
 * - restart(): 재시작
 * - stdout/stderr 스트림을 로그로 수집
 * - 프로세스 상태를 StateFlow 로 실시간 노출
 */
class ProcessManager(
    private val prootManager: ProotManager,
) {
    data class ModelSelectionEntry(
        val id: String,
        val supportsReasoning: Boolean = false,
        val supportsImages: Boolean = false,
        val contextLength: Int = 200000,
        val maxOutputTokens: Int = 4096,
    )

    companion object {
        private const val TAG = "ProcessManager"
        private const val GATEWAY_PORT = 18789
        private const val DEFAULT_MEMORY_SEARCH_PROVIDER = "auto"
        private val REMOTE_MEMORY_SEARCH_PROVIDERS = setOf("auto", "openai", "gemini", "voyage", "mistral")

        private fun normalizeMemorySearchProvider(raw: String): String {
            return when (raw.trim().lowercase()) {
                "", "auto" -> "auto"
                "openai", "gemini", "voyage", "mistral", "local" -> raw.trim().lowercase()
                else -> DEFAULT_MEMORY_SEARCH_PROVIDER
            }
        }

        private fun supportsMemorySearchRemoteApiKey(provider: String): Boolean {
            val normalized = normalizeMemorySearchProvider(provider)
            return normalized in REMOTE_MEMORY_SEARCH_PROVIDERS
        }
    }

    private val openClawPairingDenyScript: String by lazy { buildOpenClawPairingDenyScript() }

    private val _gatewayState = MutableStateFlow(GatewayState())
    val gatewayState: StateFlow<GatewayState> = _gatewayState.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private var process: Process? = null
    private var outputJob: Job? = null
    private var uptimeJob: Job? = null
    private var startTime: Long = 0L
    private var scope: CoroutineScope? = null
    private var startupJob: Job? = null
    private var startupAttemptGeneration: Long = 0L

    private val _dashboardUrl = MutableStateFlow<String?>(null)
    val dashboardUrl: StateFlow<String?> = _dashboardUrl.asStateFlow()

    private val _pairingRequests = MutableStateFlow<List<PairingRequest>>(emptyList())
    val pairingRequests: StateFlow<List<PairingRequest>> = _pairingRequests.asStateFlow()

    private var pairingFileObserver: FileObserver? = null
    private var lastChannelConfig: ChannelConfig = ChannelConfig()
    private var lastApiProvider: String = ""
    private var lastApiKey: String = ""
    private var lastBraveSearchApiKey: String = ""
    private var lastMemorySearchEnabled: Boolean = true
    private var lastMemorySearchProvider: String = DEFAULT_MEMORY_SEARCH_PROVIDER
    private var lastMemorySearchApiKey: String = ""
    private var startupAttemptStartedElapsedMs: Long? = null
    private val openClawConfigLock = Any()

    private fun generateGatewayAuthToken(): String = java.security.SecureRandom().let { sr ->
        ByteArray(24).also { sr.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
    }

    private fun ensureGatewayAuthConfig(json: JSONObject): Boolean {
        val gateway = json.optJSONObject("gateway") ?: JSONObject().also { json.put("gateway", it) }
        val auth = gateway.optJSONObject("auth")

        return when {
            auth == null -> {
                gateway.put(
                    "auth",
                    JSONObject().apply {
                        put("mode", "token")
                        put("token", generateGatewayAuthToken())
                    },
                )
                true
            }
            auth.optString("mode").ifBlank { "token" } == "token" && auth.optString("token").isBlank() -> {
                auth.put("mode", "token")
                auth.put("token", generateGatewayAuthToken())
                true
            }
            else -> false
        }
    }

    val isRunning: Boolean
        get() = _gatewayState.value.status == GatewayStatus.RUNNING

    internal fun markStartupAttemptStarted(nowElapsedMs: Long = SystemClock.elapsedRealtime()) {
        startupAttemptStartedElapsedMs = nowElapsedMs
    }

    internal fun clearStartupAttempt() {
        startupAttemptStartedElapsedMs = null
    }

    internal fun startupAttemptAgeSeconds(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long? {
        val startedAt = startupAttemptStartedElapsedMs ?: return null
        return ((nowElapsedMs - startedAt).coerceAtLeast(0L)) / 1000L
    }

    internal fun hasActiveStartupAttempt(): Boolean = startupAttemptStartedElapsedMs != null

    internal fun beginStartupAttemptGeneration(): Long {
        startupAttemptGeneration += 1
        return startupAttemptGeneration
    }

    internal fun invalidateStartupAttemptGeneration() {
        startupAttemptGeneration += 1
        startupJob?.cancel()
        startupJob = null
    }

    internal fun isStartupAttemptGenerationValid(generation: Long): Boolean {
        return generation == startupAttemptGeneration
    }

    fun setConfigurationError(message: String) {
        clearStartupAttempt()
        addLog("[andClaw] $message")
        _gatewayState.value = _gatewayState.value.copy(
            status = GatewayStatus.ERROR,
            errorMessage = message,
        )
    }

    fun setStoppedNotice(message: String) {
        clearStartupAttempt()
        addLog("[andClaw] $message")
        _gatewayState.value = _gatewayState.value.copy(
            status = GatewayStatus.STOPPED,
            errorMessage = message,
        )
    }

    suspend fun probeGatewayHealth(timeoutMs: Long = 8_000L): Boolean {
        if (_gatewayState.value.status != GatewayStatus.RUNNING) return false
        return probeGatewayHealthDirect(timeoutMs)
    }

    suspend fun probeGatewayHealthDirect(timeoutMs: Long = 8_000L): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                GatewayWsClient(prootManager).probeGatewayHealth(timeoutMs)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * OpenClaw 게이트웨이를 시작한다.
     *
     * @param apiProvider AI 모델 공급자 (anthropic, openai, openrouter)
     * @param apiKey API 키
     * @param selectedModel 사용자가 선택한 모델 ID (빈 문자열이면 기본값 사용)
     */
    fun start(
        apiProvider: String = "",
        apiKey: String = "",
        selectedModel: String = "",
        selectedModels: List<ModelSelectionEntry> = emptyList(),
        primaryModelId: String = "",
        openAiCompatibleBaseUrl: String = "",
        ollamaBaseUrl: String = "",
        channelConfig: ChannelConfig = ChannelConfig(),
        modelReasoning: Boolean = false,
        modelImages: Boolean = false,
        modelContext: Int = 200000,
        modelMaxOutput: Int = 4096,
        braveSearchApiKey: String = "",
        applyMemorySearchConfig: Boolean = true,
        memorySearchEnabled: Boolean = true,
        memorySearchProvider: String = DEFAULT_MEMORY_SEARCH_PROVIDER,
        memorySearchApiKey: String = "",
        survivorMetadata: GatewaySurvivorMetadata? = null,
    ) {
        val status = _gatewayState.value.status
        if (status == GatewayStatus.RUNNING || status == GatewayStatus.STARTING) return

        markStartupAttemptStarted()

        val normalizedMemorySearchProvider = normalizeMemorySearchProvider(memorySearchProvider)
        val normalizedMemorySearchApiKey = memorySearchApiKey.trim()

        lastChannelConfig = channelConfig
        lastApiProvider = apiProvider
        lastApiKey = apiKey
        lastBraveSearchApiKey = braveSearchApiKey
        if (applyMemorySearchConfig) {
            lastMemorySearchEnabled = memorySearchEnabled
            lastMemorySearchProvider = normalizedMemorySearchProvider
            lastMemorySearchApiKey = normalizedMemorySearchApiKey
        }

        _gatewayState.value = _gatewayState.value.copy(
            status = GatewayStatus.STARTING,
            uptime = 0L,
            errorMessage = null,
        )
        addLog("[andClaw] Starting gateway...")

        // 새 scope 생성
        invalidateStartupAttemptGeneration()
        scope?.cancel()
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        val startupGeneration = beginStartupAttemptGeneration()

        startupJob = newScope.launch {
            var launchedProcess: Process? = null
            var localOutputJob: Job? = null
            var localUptimeJob: Job? = null

            suspend fun ensureStartupAttemptStillValid() {
                currentCoroutineContext().ensureActive()
                if (!isStartupAttemptGenerationValid(startupGeneration)) {
                    throw kotlinx.coroutines.CancellationException("Startup attempt invalidated")
                }
            }

            try {
                // 패치 파일이 없으면 생성
                ensurePatchFile()
                ensureStartupAttemptStillValid()

                val survivorPidAlive = survivorMetadata?.pid?.let { pid ->
                    pid > 0 && File("/proc/$pid").exists()
                } == true
                val survivorHealthy = if (survivorPidAlive) {
                    probeGatewayHealthDirect(timeoutMs = 2_500L)
                } else {
                    false
                }
                if (
                    shouldReattachGatewaySurvivor(
                        metadata = survivorMetadata,
                        pidAlive = survivorPidAlive,
                        healthCheckPassed = survivorHealthy,
                    )
                ) {
                    clearStartupAttempt()
                    startTime = System.currentTimeMillis()
                    _gatewayState.value = _gatewayState.value.copy(
                        status = GatewayStatus.RUNNING,
                        uptime = 0L,
                        pid = survivorMetadata?.pid,
                        errorMessage = null,
                        dashboardReady = true,
                    )
                    addLog("[andClaw] Reattached to surviving gateway (PID: ${survivorMetadata?.pid ?: "?"})")
                    startPairingObserver()
                    return@launch
                }

                // 기존 게이트웨이 인스턴스 정리 (supervised + orphan 프로세스)
                stopSupervisedGatewayIfRunning()
                ensureStartupAttemptStillValid()
                killOrphanGatewayProcesses()
                ensureStartupAttemptStillValid()

                // config 파일 생성/갱신 (모델, 게이트웨이, 브라우저 설정)
                ensureOpenClawConfig(
                    apiProvider = apiProvider,
                    apiKey = apiKey,
                    selectedModel = selectedModel,
                    selectedModels = selectedModels,
                    primaryModelId = primaryModelId,
                    openAiCompatibleBaseUrl = openAiCompatibleBaseUrl,
                    ollamaBaseUrl = ollamaBaseUrl,
                    modelReasoning = modelReasoning,
                    modelImages = modelImages,
                    modelContext = modelContext,
                    modelMaxOutput = modelMaxOutput,
                    memorySearchEnabled = memorySearchEnabled.takeIf { applyMemorySearchConfig },
                    memorySearchProvider = normalizedMemorySearchProvider.takeIf { applyMemorySearchConfig },
                    memorySearchApiKey = normalizedMemorySearchApiKey.takeIf { applyMemorySearchConfig },
                )
                ensureStartupAttemptStillValid()

                // 채널 설정 기록
                ensureChannelConfig(channelConfig)
                ensureStartupAttemptStillValid()

                // proot 명령어 구성
                val cmd = prootManager.buildGatewayCommand()

                // 환경변수 구성 (API 키 포함)
                val extraEnv = buildMap {
                    put("HOME", "/root")
                    put("PATH", "/usr/local/bin:/usr/bin:/bin")
                    put("LANG", "C.UTF-8")
                    put("UV_USE_IO_URING", "0")
                    put("PLAYWRIGHT_BROWSERS_PATH", "/root/.cache/ms-playwright")

                    if (apiProvider == "github-copilot") {
                        putAll(githubCopilotAuthEnv())
                    }

                    // API 키를 환경변수로 전달
                    if (apiKey.isNotBlank()) {
                        when (apiProvider) {
                            "anthropic" -> put("ANTHROPIC_API_KEY", apiKey)
                            "openai" -> put("OPENAI_API_KEY", apiKey)
                            "openai-codex" -> { /* OAuth provider: no API key env needed */ }
                            "github-copilot" -> { /* OAuth/env provider: env already resolved above */ }
                            "zai" -> {
                                put("ZAI_API_KEY", apiKey)
                                put("Z_AI_API_KEY", apiKey)
                            }
                            "kimi-coding" -> {
                                put("KIMI_API_KEY", apiKey)
                                put("KIMICODE_API_KEY", apiKey)
                            }
                            "minimax" -> put("MINIMAX_API_KEY", apiKey)
                            "openai-compatible" -> put("OPENAI_COMPAT_API_KEY", apiKey)
                            "ollama" -> put("OLLAMA_API_KEY", apiKey)
                            "ollama-cloud" -> put("OLLAMA_API_KEY", apiKey)
                            "openrouter" -> put("OPENROUTER_API_KEY", apiKey)
                            "google" -> {
                                put("GEMINI_API_KEY", apiKey)
                                put("GOOGLE_API_KEY", apiKey)
                            }
                            else -> put("OPENAI_API_KEY", apiKey)
                        }
                    }

                    // Brave Search API 키
                    if (braveSearchApiKey.isNotBlank()) {
                        put("BRAVE_API_KEY", braveSearchApiKey)
                    }
                    if (applyMemorySearchConfig &&
                        memorySearchEnabled &&
                        supportsMemorySearchRemoteApiKey(normalizedMemorySearchProvider) &&
                        normalizedMemorySearchApiKey.isNotBlank()
                    ) {
                        put("MEMORY_SEARCH_API_KEY", normalizedMemorySearchApiKey)
                    }

                    // 채널 봇 토큰
                    if (channelConfig.telegramEnabled && channelConfig.telegramBotToken.isNotBlank()) {
                        put("TELEGRAM_BOT_TOKEN", channelConfig.telegramBotToken)
                    }
                    if (channelConfig.discordEnabled && channelConfig.discordBotToken.isNotBlank()) {
                        put("DISCORD_BOT_TOKEN", channelConfig.discordBotToken)
                    }
                }
                val env = prootManager.buildEnvironment(extraEnv)

                // 프로세스 시작
                val pb = ProcessBuilder(cmd).redirectErrorStream(true)
                pb.environment().putAll(env)

                addLog("[andClaw] Command: ${cmd.joinToString(" ")}")

                ensureStartupAttemptStillValid()
                launchedProcess = pb.start()
                if (!isStartupAttemptGenerationValid(startupGeneration)) {
                    runCatching { launchedProcess.destroyForcibly() }
                    runCatching { launchedProcess.waitFor(2, TimeUnit.SECONDS) }
                    throw kotlinx.coroutines.CancellationException("Startup attempt invalidated after process launch")
                }
                process = launchedProcess
                startTime = System.currentTimeMillis()

                _gatewayState.value = _gatewayState.value.copy(
                    status = GatewayStatus.STARTING,
                    uptime = 0L,
                    pid = getProcessId(launchedProcess),
                    dashboardReady = false,
                )
                addLog("[andClaw] Gateway process started (PID: ${_gatewayState.value.pid ?: "?"})")

                // stdout/stderr 스트림 읽기
                localOutputJob = newScope.launch {
                    readProcessOutput(launchedProcess)
                }
                outputJob = localOutputJob

                // uptime 카운터
                localUptimeJob = newScope.launch {
                    while (isActive) {
                        delay(1000)
                        val uptime = (System.currentTimeMillis() - startTime) / 1000
                        _gatewayState.value = _gatewayState.value.copy(uptime = uptime)
                    }
                }
                uptimeJob = localUptimeJob

                // 프로세스 종료 대기
                val exitCode = withContext(Dispatchers.IO) {
                    launchedProcess.waitFor()
                }

                localUptimeJob?.cancel()
                addLog("[andClaw] Gateway process exited (exit code: $exitCode)")

                // 예기치 않은 종료 (사용자가 stop()을 호출하지 않았는데 종료된 경우)
                if (_gatewayState.value.status == GatewayStatus.RUNNING ||
                    _gatewayState.value.status == GatewayStatus.STARTING) {
                    clearStartupAttempt()
                    _gatewayState.value = _gatewayState.value.copy(
                        status = GatewayStatus.ERROR,
                        errorMessage = "Process terminated unexpectedly (exit: $exitCode)",
                        pid = null,
                    )
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // 정상적인 취소 (재시작 등)
                // 단, 취소 시 이미 띄운 프로세스가 살아있으면 명시적으로 정리해서 포트 점유를 방지한다.
                localOutputJob?.cancel()
                localUptimeJob?.cancel()
                launchedProcess?.let { proc ->
                    if (proc.isAlive) {
                        addLog("[andClaw] Cancelling in-flight gateway start; killing spawned process")
                        runCatching { proc.destroyForcibly() }
                        runCatching { proc.waitFor(2, TimeUnit.SECONDS) }
                    }
                }
                if (process === launchedProcess) {
                    process = null
                }
            } catch (e: Exception) {
                addLog("[andClaw] Error: ${e.message}")
                clearStartupAttempt()
                _gatewayState.value = _gatewayState.value.copy(
                    status = GatewayStatus.ERROR,
                    errorMessage = e.message,
                    pid = null,
                )
            } finally {
                if (startupJob === currentCoroutineContext()[Job]) {
                    startupJob = null
                }
                if (outputJob === localOutputJob) {
                    outputJob = null
                }
                if (uptimeJob === localUptimeJob) {
                    uptimeJob = null
                }
                if (process === launchedProcess && launchedProcess?.isAlive != true) {
                    process = null
                }
            }
        }
    }

    /**
     * 게이트웨이 프로세스를 중지한다.
     */
    fun stop() {
        clearStartupAttempt()
        invalidateStartupAttemptGeneration()
        scope?.cancel()
        val currentStatus = _gatewayState.value.status
        if (currentStatus != GatewayStatus.RUNNING && currentStatus != GatewayStatus.STARTING) return
        val pidFromState = _gatewayState.value.pid

        _gatewayState.value = _gatewayState.value.copy(status = GatewayStatus.STOPPING)
        addLog("[andClaw] Stopping gateway...")

        outputJob?.cancel()
        uptimeJob?.cancel()
        stopPairingObserver()

        var stoppedByHandle = false

        // 프로세스 핸들이 있는 일반 케이스 종료
        process?.let { proc ->
            proc.destroyForcibly()
            runCatching {
                proc.waitFor(2, TimeUnit.SECONDS)
            }
            stoppedByHandle = true
        }

        // 프로세스-데스 이후 survivor 재부착 케이스: process 핸들이 없으므로 PID 기반 종료 시도
        if (!stoppedByHandle && pidFromState != null && pidFromState > 0) {
            val isAlive = File("/proc/$pidFromState").exists()
            if (isAlive) {
                val killed = runCatching {
                    android.os.Process.killProcess(pidFromState)
                    true
                }.getOrDefault(false)
                if (killed) {
                    stoppedByHandle = true
                    addLog("[andClaw] Stopped reattached gateway by PID: $pidFromState")
                }
            }
        }

        // 최후 fallback: supervised gateway stop 요청
        if (!stoppedByHandle) {
            stopSupervisedGatewayIfRunning()
        }

        process = null

        _gatewayState.value = GatewayState(status = GatewayStatus.STOPPED)
        addLog("[andClaw] Gateway stopped")
    }

    /**
     * 게이트웨이를 재시작한다.
     */
    fun restart(
        apiProvider: String = "",
        apiKey: String = "",
        selectedModel: String = "",
        selectedModels: List<ModelSelectionEntry> = emptyList(),
        primaryModelId: String = "",
        openAiCompatibleBaseUrl: String = "",
        ollamaBaseUrl: String = "",
        channelConfig: ChannelConfig = ChannelConfig(),
        modelReasoning: Boolean = false,
        modelImages: Boolean = false,
        modelContext: Int = 200000,
        modelMaxOutput: Int = 4096,
        braveSearchApiKey: String = "",
        applyMemorySearchConfig: Boolean = true,
        memorySearchEnabled: Boolean = true,
        memorySearchProvider: String = DEFAULT_MEMORY_SEARCH_PROVIDER,
        memorySearchApiKey: String = "",
        survivorMetadata: GatewaySurvivorMetadata? = null,
    ) {
        markStartupAttemptStarted()
        stop()
        start(
            apiProvider = apiProvider,
            apiKey = apiKey,
            selectedModel = selectedModel,
            selectedModels = selectedModels,
            primaryModelId = primaryModelId,
            openAiCompatibleBaseUrl = openAiCompatibleBaseUrl,
            ollamaBaseUrl = ollamaBaseUrl,
            channelConfig = channelConfig,
            modelReasoning = modelReasoning,
            modelImages = modelImages,
            modelContext = modelContext,
            modelMaxOutput = modelMaxOutput,
            braveSearchApiKey = braveSearchApiKey,
            applyMemorySearchConfig = applyMemorySearchConfig,
            memorySearchEnabled = memorySearchEnabled,
            memorySearchProvider = memorySearchProvider,
            memorySearchApiKey = memorySearchApiKey,
            survivorMetadata = survivorMetadata,
        )
    }

    /**
     * 리소스 정리
     */
    fun destroy() {
        stop()
        stopPairingObserver()
        scope?.cancel()
        scope = null
    }

    /**
     * 세션 로그에서 최근 메시지 엔트리를 읽어 반환한다.
     * sessions/ 디렉토리에서 가장 최신 JSONL 파일을 파싱한다.
     */
    fun getSessionLogEntries(): List<SessionLogEntry> {
        val sessionsDir = File(prootManager.rootfsDir, "root/.openclaw/agents/main/sessions")
        if (!sessionsDir.exists()) return emptyList()

        try {
            // 가장 최근 수정된 .jsonl 파일 찾기
            val jsonlFiles = sessionsDir.listFiles { f -> f.extension == "jsonl" }
                ?.sortedByDescending { it.lastModified() }
                ?: return emptyList()

            val entries = mutableListOf<SessionLogEntry>()

            for (file in jsonlFiles) {
                if (entries.size >= 50) break

                val lines = file.readLines().filter { it.isNotBlank() }
                for (line in lines.reversed()) {
                    if (entries.size >= 50) break
                    try {
                        val json = JSONObject(line)
                        val type = json.optString("type", "")
                        if (type != "message") continue

                        val msg = json.optJSONObject("message") ?: continue
                        val role = msg.optString("role", "")
                        if (role.isBlank()) continue

                        val model = msg.optString("model", "").ifBlank { null }
                        val stopReason = msg.optString("stopReason", "").ifBlank { null }
                        val errorMessage = msg.optString("errorMessage", "").ifBlank { null }
                        val timestamp = json.optString("timestamp", "").ifBlank {
                            json.optString("ts", "")
                        }

                        // content 미리보기 추출
                        val contentPreview = extractContentPreview(msg)

                        // 토큰 사용량
                        val usage = msg.optJSONObject("usage")
                        val tokenUsage = if (usage != null) {
                            usage.optInt("totalTokens", 0).let {
                                if (it > 0) it else usage.optInt("outputTokens", 0) + usage.optInt("inputTokens", 0)
                            }
                        } else 0

                        entries.add(
                            SessionLogEntry(
                                timestamp = timestamp,
                                role = role,
                                model = model,
                                contentPreview = contentPreview,
                                errorMessage = errorMessage,
                                stopReason = stopReason,
                                tokenUsage = tokenUsage,
                            )
                        )
                    } catch (_: Exception) {
                        // 파싱 실패한 줄은 무시
                    }
                }
            }

            return entries
        } catch (e: Exception) {
            addLog("[andClaw] Failed to read session logs: ${e.message}")
            return emptyList()
        }
    }

    /**
     * 메시지 JSON에서 content 미리보기를 추출한다.
     * content가 문자열이면 직접, 배열이면 첫 text 블록을 사용.
     */
    private fun extractContentPreview(msg: JSONObject): String? {
        // content가 문자열인 경우
        val contentStr = msg.optString("content", "")
        if (contentStr.isNotBlank()) {
            return contentStr.take(100)
        }

        // content가 배열인 경우
        val contentArr = msg.optJSONArray("content")
        if (contentArr != null) {
            for (i in 0 until contentArr.length()) {
                val block = contentArr.optJSONObject(i) ?: continue
                if (block.optString("type", "") == "text") {
                    val text = block.optString("text", "")
                    if (text.isNotBlank()) return text.take(100)
                }
            }
        }

        return null
    }

    // ── 내부 헬퍼 ──

    /**
     * OpenClaw config에서 모델을 사용자의 provider에 맞게 설정한다.
     *
     * @param apiProvider AI 모델 공급자 (anthropic, openai, openrouter)
     * @param selectedModel 사용자가 선택한 모델 ID (빈 문자열이면 기본값 사용)
     * @param modelReasoning 모델 reasoning 지원 여부
     * @param modelImages 모델 이미지 입력 지원 여부
     * @param modelContext 모델 컨텍스트 윈도우 크기
     * @param modelMaxOutput 모델 최대 출력 토큰 수
     */
    fun ensureOpenClawConfig(
        apiProvider: String,
        apiKey: String = "",
        selectedModel: String = "",
        selectedModels: List<ModelSelectionEntry> = emptyList(),
        primaryModelId: String = "",
        openAiCompatibleBaseUrl: String = "",
        ollamaBaseUrl: String = "",
        modelReasoning: Boolean = false,
        modelImages: Boolean = false,
        modelContext: Int = 200000,
        modelMaxOutput: Int = 4096,
        memorySearchEnabled: Boolean? = null,
        memorySearchProvider: String? = null,
        memorySearchApiKey: String? = null,
    ) {
        val configFile = File(prootManager.rootfsDir, "root/.openclaw/openclaw.json")

        synchronized(openClawConfigLock) {
            try {
                val json = if (configFile.exists()) {
                    JSONObject(configFile.readText())
                } else {
                    configFile.parentFile?.mkdirs()
                    addLog("[andClaw] Creating minimal openclaw config")
                    JSONObject().apply {
                        put("agents", JSONObject().put("defaults", JSONObject()))
                        put("gateway", JSONObject().apply {
                            put("auth", JSONObject().apply {
                                put("mode", "token")
                                put("token", generateGatewayAuthToken())
                            })
                        })
                    }
                }

                // agents.defaults가 없으면 생성
                val agents = json.optJSONObject("agents") ?: JSONObject().also { json.put("agents", it) }
                val defaults = agents.optJSONObject("defaults") ?: JSONObject().also { agents.put("defaults", it) }

            // 현재 모델/목록 확인
            val modelObj = defaults.optJSONObject("model")
            val currentModel = modelObj?.optString("primary", "") ?: ""
            val currentModels = defaults.optJSONObject("models")
            val currentModelKeys = buildList {
                if (currentModels == null) return@buildList
                val iterator = currentModels.keys()
                while (iterator.hasNext()) {
                    add(iterator.next())
                }
            }

            val normalizedSelectedEntries = selectedModels
                .mapNotNull { entry ->
                    val modelId = entry.id.trim()
                    if (modelId.isBlank()) return@mapNotNull null
                    entry.copy(
                        id = modelId,
                        contextLength = entry.contextLength.coerceAtLeast(1),
                        maxOutputTokens = entry.maxOutputTokens.coerceAtLeast(1),
                    )
                }
                .distinctBy { it.id }
                .toMutableList()

            if (normalizedSelectedEntries.isEmpty()) {
                val fallbackId = selectedModel.trim()
                if (fallbackId.isNotBlank()) {
                    normalizedSelectedEntries += ModelSelectionEntry(
                        id = fallbackId,
                        supportsReasoning = modelReasoning,
                        supportsImages = modelImages,
                        contextLength = modelContext,
                        maxOutputTokens = modelMaxOutput,
                    )
                }
            }

            if (normalizedSelectedEntries.isEmpty()) {
                val defaultModelId = when (apiProvider) {
                    "openrouter" -> "openrouter/free"
                    "anthropic" -> "claude-sonnet-4-5"
                    "openai" -> "gpt-5-mini"
                    "openai-codex" -> "gpt-5.3-codex"
                    "github-copilot" -> "gpt-4o"
                    "zai" -> "glm-5"
                    "kimi-coding" -> "k2p5"
                    "minimax" -> "MiniMax-M2.5"
                "openai-compatible" -> ""
                "ollama" -> ""
                "ollama-cloud" -> ""
                    "google" -> "gemini-2.5-flash"
                    else -> "openrouter/free"
                }
                normalizedSelectedEntries += ModelSelectionEntry(
                    id = defaultModelId,
                    supportsReasoning = modelReasoning,
                    supportsImages = modelImages,
                    contextLength = modelContext,
                    maxOutputTokens = modelMaxOutput,
                )
            }

            fun toProviderScopedModel(modelIdRaw: String): String {
                val modelId = modelIdRaw.trim()
                return when (apiProvider) {
                    "openrouter" -> "openrouter/$modelId"
                    "anthropic" -> {
                        val id = when {
                            modelId.startsWith("anthropic/") -> modelId.removePrefix("anthropic/")
                            modelId.contains("/") -> "claude-sonnet-4-5"
                            else -> modelId
                        }
                        "anthropic/$id"
                    }
                    "openai" -> {
                        val id = when {
                            modelId.startsWith("openai/") -> modelId.removePrefix("openai/")
                            modelId.contains("/") -> "gpt-5-mini"
                            else -> modelId
                        }
                        "openai/$id"
                    }
                    "openai-codex" -> {
                        val id = when {
                            modelId.startsWith("openai/") -> modelId.removePrefix("openai/")
                            modelId.startsWith("openai-codex/") -> modelId.removePrefix("openai-codex/")
                            modelId.isNotBlank() -> modelId
                            else -> "gpt-5.3-codex"
                        }
                        "openai-codex/$id"
                    }
                    "github-copilot" -> {
                        val id = when {
                            modelId.startsWith("github-copilot/") -> modelId.removePrefix("github-copilot/")
                            modelId.contains("/") -> "gpt-4o"
                            else -> modelId
                        }
                        "github-copilot/$id"
                    }
                    "zai" -> {
                        val id = when {
                            modelId.startsWith("zai/") -> modelId.removePrefix("zai/")
                            modelId.contains("/") -> "glm-5"
                            else -> modelId
                        }
                        "zai/$id"
                    }
                    "kimi-coding" -> {
                        val id = when {
                            modelId.startsWith("kimi-coding/") -> modelId.removePrefix("kimi-coding/")
                            modelId.contains("/") -> "k2p5"
                            else -> modelId
                        }
                        "kimi-coding/$id"
                    }
                    "minimax" -> {
                        val id = when {
                            modelId.startsWith("minimax/") -> modelId.removePrefix("minimax/")
                            modelId.contains("/") -> "MiniMax-M2.5"
                            else -> modelId
                        }
                        "minimax/$id"
                    }
                    "openai-compatible" -> {
                        val id = modelId.removePrefix("openai-compatible/")
                        "openai-compatible/$id"
                    }
                    "ollama" -> {
                        val id = modelId.removePrefix("ollama/").removeSuffix(":latest")
                        "ollama/$id"
                    }
                    "ollama-cloud" -> {
                        val id = modelId.removePrefix("ollama/").removePrefix("ollama-cloud/").removeSuffix(":latest")
                        "ollama/$id"
                    }
                    "google" -> {
                        val id = when {
                            modelId.startsWith("google/") -> modelId.removePrefix("google/")
                            modelId.contains("/") -> "gemini-2.5-flash"
                            else -> modelId
                        }
                        "google/$id"
                    }
                    else -> "openrouter/$modelId"
                }
            }

            val selectedIdSet = normalizedSelectedEntries.map { it.id }.toSet()
            val requestedPrimary = primaryModelId.trim().ifBlank { selectedModel.trim() }
            val effectivePrimaryId = when {
                requestedPrimary.isNotBlank() && selectedIdSet.contains(requestedPrimary) -> requestedPrimary
                else -> normalizedSelectedEntries.first().id
            }
            val targetModel = toProviderScopedModel(effectivePrimaryId)
            val targetModels = normalizedSelectedEntries
                .map { toProviderScopedModel(it.id) }
                .distinct()
            fun modelPrefixesForProvider(provider: String): Set<String> = when (provider) {
                "openrouter" -> setOf("openrouter/", "nvidia/")
                "anthropic" -> setOf("anthropic/")
                "openai" -> setOf("openai/")
                "openai-codex" -> setOf("openai-codex/")
                "github-copilot" -> setOf("github-copilot/")
                "zai" -> setOf("zai/")
                "kimi-coding" -> setOf("kimi-coding/")
                "minimax" -> setOf("minimax/")
                "openai-compatible" -> setOf("openai-compatible/")
                "ollama" -> setOf("ollama/")
                "ollama-cloud" -> setOf("ollama/")
                "google" -> setOf("google/")
                else -> setOf("${provider.trim().lowercase()}/")
            }
            val providerModelPrefixes = modelPrefixesForProvider(apiProvider)
            val mergedModelKeys = buildList {
                addAll(
                    currentModelKeys.filterNot { existingModelId ->
                        providerModelPrefixes.any { prefix -> existingModelId.startsWith(prefix) }
                    },
                )
                addAll(targetModels)
            }.distinct()
            val registeredCustomModelIds = readRegisteredCustomModelIdsByProvider()
            val builtInOpenRouterModelIds = getBuiltInOpenRouterModelIds()
            fun isResolvableModelKey(modelKey: String): Boolean {
                return when {
                    modelKey.startsWith("openai-compatible/") -> {
                        val id = modelKey.removePrefix("openai-compatible/")
                        val currentTarget = apiProvider == "openai-compatible" && modelKey in targetModels
                        currentTarget || registeredCustomModelIds["openai-compatible"].orEmpty().contains(id)
                    }

                    modelKey.startsWith("openrouter/") -> {
                        val id = modelKey.removePrefix("openrouter/")
                        val currentTarget = apiProvider == "openrouter" && modelKey in targetModels
                        currentTarget ||
                            builtInOpenRouterModelIds.contains(id) ||
                            registeredCustomModelIds["openrouter"].orEmpty().contains(id)
                    }

                    modelKey.startsWith("nvidia/") -> {
                        val id = modelKey.removePrefix("nvidia/")
                        builtInOpenRouterModelIds.contains(id) ||
                            registeredCustomModelIds["openrouter"].orEmpty().contains(id)
                    }

                    modelKey.startsWith("ollama/") -> {
                        val id = modelKey.removePrefix("ollama/").removeSuffix(":latest")
                        val currentTarget = (apiProvider == "ollama" || apiProvider == "ollama-cloud") && modelKey in targetModels
                        currentTarget || registeredCustomModelIds["ollama"].orEmpty().contains(id)
                    }

                    else -> true
                }
            }
            val resolvedModelKeys = mergedModelKeys.filter(::isResolvableModelKey)
            var changed = sanitizeKnownIncompatibleConfigKeys(json)

            val shouldApplyMemorySearchConfig =
                memorySearchEnabled != null || memorySearchProvider != null || memorySearchApiKey != null
            if (shouldApplyMemorySearchConfig) {
                val memorySearch = defaults.optJSONObject("memorySearch")
                    ?: JSONObject().also { defaults.put("memorySearch", it) }
                val normalizedProvider = memorySearchProvider?.let(::normalizeMemorySearchProvider)
                val enabled = memorySearchEnabled ?: memorySearch.optBoolean("enabled", true)
                val existingProvider = normalizeMemorySearchProvider(
                    memorySearch.optString("provider").ifBlank { DEFAULT_MEMORY_SEARCH_PROVIDER }
                )
                val effectiveProvider = normalizedProvider ?: existingProvider

                if (memorySearch.optBoolean("enabled", true) != enabled || !memorySearch.has("enabled")) {
                    memorySearch.put("enabled", enabled)
                    changed = true
                }

                if (enabled) {
                    if (normalizedProvider != null) {
                        if (normalizedProvider == "auto") {
                            if (memorySearch.has("provider")) {
                                memorySearch.remove("provider")
                                changed = true
                            }
                        } else if (memorySearch.optString("provider") != normalizedProvider) {
                            memorySearch.put("provider", normalizedProvider)
                            changed = true
                        }
                    }

                    val normalizedApiKey = memorySearchApiKey?.trim().orEmpty()
                    val shouldUseRemoteApiKeyRef =
                        normalizedApiKey.isNotBlank() &&
                            supportsMemorySearchRemoteApiKey(effectiveProvider)
                    val remoteConfig = memorySearch.optJSONObject("remote")
                    if (effectiveProvider == "auto" && normalizedApiKey.isNotBlank()) {
                        addLog(
                            "[andClaw] Memory Search override key is applied in auto mode. " +
                                "For non-OpenAI keys, choose an explicit provider."
                        )
                    }
                    if (shouldUseRemoteApiKeyRef) {
                        val remote = remoteConfig ?: JSONObject().also {
                            memorySearch.put("remote", it)
                            changed = true
                        }
                        val targetRef = "\${MEMORY_SEARCH_API_KEY}"
                        if (remote.optString("apiKey") != targetRef) {
                            remote.put("apiKey", targetRef)
                            changed = true
                        }
                    } else if (remoteConfig != null && remoteConfig.has("apiKey")) {
                        remoteConfig.remove("apiKey")
                        changed = true
                        if (remoteConfig.length() == 0) {
                            memorySearch.remove("remote")
                        }
                    }
                } else {
                    if (memorySearch.has("provider")) {
                        memorySearch.remove("provider")
                        changed = true
                    }
                    if (memorySearch.has("remote")) {
                        memorySearch.remove("remote")
                        changed = true
                    }
                }
            }

            val chromePath = prootManager.detectChromiumExecutableProotPath()

            // 브라우저 설정
            val browserObj = json.optJSONObject("browser")
            if (browserObj == null || !browserObj.has("defaultProfile")) {
                json.put("browser", JSONObject().apply {
                    put("headless", true)
                    put("noSandbox", true)
                    put("defaultProfile", "openclaw")
                    if (chromePath != null) {
                        put("executablePath", chromePath)
                        put("extraArgs", JSONArray(ANDROID_CHROMIUM_EXTRA_ARGS))
                    }
                })
                addLog("[andClaw] Browser config added (executablePath=$chromePath)")
                changed = true
            } else {
                val currentExe = browserObj.optString("executablePath", "")
                val hasLegacyWrapperPath = currentExe.endsWith("/$LEGACY_CHROMIUM_WRAPPER_NAME")
                if (chromePath != null && currentExe != chromePath) {
                    browserObj.put("executablePath", chromePath)
                    addLog("[andClaw] Browser executablePath updated: $chromePath")
                    changed = true
                } else if (chromePath == null && hasLegacyWrapperPath) {
                    browserObj.remove("executablePath")
                    addLog("[andClaw] Legacy Chromium wrapper path removed; falling back to native discovery")
                    changed = true
                }
                if (chromePath != null) {
                    val mergedExtraArgs = mergeRequiredExtraArgs(browserObj.optJSONArray("extraArgs"))
                    if (!jsonArrayStringListEquals(browserObj.optJSONArray("extraArgs"), mergedExtraArgs)) {
                        browserObj.put("extraArgs", JSONArray(mergedExtraArgs))
                        addLog("[andClaw] Browser extraArgs updated for Android Chromium")
                        changed = true
                    }
                }
            }

            val shouldUpdatePrimary = currentModel.isBlank() || currentModel != targetModel
            val shouldUpdateModels = currentModelKeys.toSet() != resolvedModelKeys.toSet()
            if (shouldUpdatePrimary || shouldUpdateModels) {
                addLog("[andClaw] Model config updated: primary=$targetModel, count=${resolvedModelKeys.size}")
                val newModelObj = JSONObject().apply {
                    put("primary", targetModel)
                }
                defaults.put("model", newModelObj)

                val modelsObj = JSONObject().apply {
                    resolvedModelKeys.forEach { modelKey ->
                        val existingModelObject = currentModels?.optJSONObject(modelKey)
                        if (existingModelObject != null) {
                            put(modelKey, JSONObject(existingModelObject.toString()))
                        } else {
                            put(modelKey, JSONObject())
                        }
                    }
                }
                defaults.put("models", modelsObj)
                changed = true
            }

            // OpenRouter 모델 등록:
            // 내장 모델(레거시 레지스트리에서 식별)은 compat 설정 포함 정확한 정의를 갖고 있으므로
            // 커스텀 등록으로 덮어쓰면 안 된다. 비내장 모델만 models.json에 등록.
            if (apiProvider == "openrouter") {
                val modelEntriesById = normalizedSelectedEntries.associateBy { it.id }
                val selectedModelIds = modelEntriesById.keys.ifEmpty { setOf("openrouter/free") }
                val builtInIds = builtInOpenRouterModelIds

                val customEntries = selectedModelIds
                    .filterNot { builtInIds.contains(it) }
                    .map { modelId ->
                        modelEntriesById[modelId] ?: ModelSelectionEntry(
                            id = modelId,
                            supportsReasoning = modelReasoning,
                            supportsImages = modelImages,
                            contextLength = modelContext,
                            maxOutputTokens = modelMaxOutput,
                        )
                    }

                if (customEntries.isEmpty()) {
                    removeProviderModelsJson("openrouter")
                    addLog("[andClaw] OpenRouter selected models are built-in - using native definitions")
                } else {
                    writeModelsJson(customEntries)
                    addLog("[andClaw] Registered ${customEntries.size} custom OpenRouter model(s) in models.json")
                }
                // openclaw.json에서 models.providers 섹션 제거 (models.json으로 이관)
                json.optJSONObject("models")?.remove("providers")
                changed = true
            } else if (apiProvider == "openai-compatible") {
                val compatEntries = normalizedSelectedEntries
                    .map { entry ->
                        entry.copy(id = entry.id.removePrefix("openai-compatible/"))
                    }
                val normalizedBaseUrl = normalizeOpenAiCompatibleBaseUrl(openAiCompatibleBaseUrl)
                writeOpenAiCompatibleModelsJson(
                    modelEntries = compatEntries,
                    baseUrl = normalizedBaseUrl,
                    apiKey = apiKey,
                )
                json.optJSONObject("models")?.remove("providers")
                addLog(
                    "[andClaw] OpenAI-compatible provider configured: " +
                        "models=${compatEntries.map { it.id }}, baseUrl='$normalizedBaseUrl'"
                )
                changed = true
            } else if (apiProvider == "ollama" || apiProvider == "ollama-cloud") {
                val ollamaEntries = normalizedSelectedEntries
                    .map { entry -> entry.copy(id = entry.id.removePrefix("ollama-cloud/").removePrefix("ollama/").removeSuffix(":latest")) }
                val normalizedBaseUrl = if (apiProvider == "ollama-cloud") {
                    "https://ollama.com"
                } else {
                    ollamaBaseUrl.trim().trimEnd('/').ifBlank { "http://127.0.0.1:11434" }
                }
                val models = json.optJSONObject("models") ?: JSONObject().also { json.put("models", it) }
                val providers = models.optJSONObject("providers") ?: JSONObject().also { models.put("providers", it) }
                providers.put("ollama", JSONObject().apply {
                    put("baseUrl", normalizedBaseUrl)
                    put("api", "ollama")
                    put("apiKey", when {
                        apiProvider == "ollama-cloud" && apiKey.isNotBlank() -> "OLLAMA_API_KEY"
                        apiProvider == "ollama-cloud" -> ""
                        else -> "ollama-local"
                    })
                    put("models", org.json.JSONArray().apply {
                        ollamaEntries.forEach { entry ->
                            put(buildModelEntryJson(entry, api = "ollama"))
                        }
                    })
                })
                writeOllamaModelsJson(
                    modelEntries = ollamaEntries,
                    baseUrl = normalizedBaseUrl,
                    apiKey = apiKey,
                    apiProvider = apiProvider,
                )
                addLog(
                    "[andClaw] Ollama provider configured: " +
                        "models=${ollamaEntries.map { it.id }}, baseUrl='$normalizedBaseUrl'"
                )
                changed = true
            }

                // gateway 설정 (mode 및 controlUi.allowedOrigins)
                val gateway = json.optJSONObject("gateway") ?: JSONObject().also { json.put("gateway", it) }
                if (ensureGatewayAuthConfig(json)) {
                    changed = true
                }

            // gateway.mode 설정 (필수 - 미설정 시 게이트웨이 시작 불가)
            if (!gateway.has("mode")) {
                gateway.put("mode", "local")
                changed = true
            }

            // gateway.controlUi.allowedOrigins 설정 (앱 내 WebSocket 연결 허용)
            val controlUi = gateway.optJSONObject("controlUi") ?: JSONObject().also { gateway.put("controlUi", it) }
            if (!controlUi.has("allowedOrigins")) {
                controlUi.put("allowedOrigins", org.json.JSONArray().apply { put("*") })
                changed = true
            }

            // 현재 번들에 없는 플러그인 엔트리 정리 (게이트웨이 부팅 실패 방지)
            val plugins = json.optJSONObject("plugins")
            val entries = plugins?.optJSONObject("entries")
            if (entries != null) {
                if (entries.has("codex-cli")) {
                    entries.remove("codex-cli")
                    changed = true
                }
                if (entries.has("openai-codex")) {
                    entries.remove("openai-codex")
                    changed = true
                }
            }

                if (changed) {
                    configFile.writeText(json.toString(2))
                }
            } catch (e: Exception) {
                addLog("[andClaw] Config update failed: ${e.message}")
            }
        }
    }

    /**
     * openclaw.json에 채널 설정 블록을 기록한다.
     */
    fun ensureChannelConfig(channelConfig: ChannelConfig) {
        val configFile = File(prootManager.rootfsDir, "root/.openclaw/openclaw.json")

        synchronized(openClawConfigLock) {
            try {
                val json = if (configFile.exists()) {
                    JSONObject(configFile.readText())
                } else {
                    configFile.parentFile?.mkdirs()
                    addLog("[andClaw] Creating minimal openclaw config for channel setup")
                    JSONObject().apply {
                        put("agents", JSONObject().put("defaults", JSONObject()))
                        put("gateway", JSONObject().apply {
                            put("auth", JSONObject().apply {
                                put("mode", "token")
                                put("token", generateGatewayAuthToken())
                            })
                        })
                    }
                }
                sanitizeKnownIncompatibleConfigKeys(json)
                ensureGatewayAuthConfig(json)
                val channels = json.optJSONObject("channels") ?: JSONObject().also { json.put("channels", it) }
                val managedChannels = mutableListOf<String>()

            if (channelConfig.whatsappEnabled) {
                val whatsapp = channels.optJSONObject("whatsapp") ?: JSONObject().also { channels.put("whatsapp", it) }
                whatsapp.put("dmPolicy", "pairing")
                val accounts = whatsapp.optJSONObject("accounts") ?: JSONObject().also { whatsapp.put("accounts", it) }
                val defaultAccount = accounts.optJSONObject("default") ?: JSONObject().also {
                    accounts.put("default", it)
                }
                defaultAccount.put("enabled", true)
                managedChannels += "whatsapp"
            } else if (channels.has("whatsapp")) {
                channels.remove("whatsapp")
            }

            if (channelConfig.telegramEnabled && channelConfig.telegramBotToken.isNotBlank()) {
                val telegram = channels.optJSONObject("telegram") ?: JSONObject().also { channels.put("telegram", it) }
                if (telegram.has("accounts")) {
                    telegram.remove("accounts")
                }
                if (telegram.has("tokenFile")) {
                    telegram.remove("tokenFile")
                }
                telegram.put("enabled", true)
                telegram.put("botToken", "\${TELEGRAM_BOT_TOKEN}")
                telegram.put("dmPolicy", "pairing")
                managedChannels += "telegram"
            } else if (channels.has("telegram")) {
                channels.remove("telegram")
            }

            if (channelConfig.discordEnabled && channelConfig.discordBotToken.isNotBlank()) {
                val guildAllowlist = parseDiscordGuildAllowlist(channelConfig.discordGuildAllowlist)
                val discord = channels.optJSONObject("discord") ?: JSONObject().also { channels.put("discord", it) }
                if (discord.has("accounts")) {
                    discord.remove("accounts")
                }
                discord.put("enabled", true)
                discord.put("token", "\${DISCORD_BOT_TOKEN}")
                discord.put("dmPolicy", "pairing")
                // Explicitly disable guild handling when allowlist is empty (no implicit open fallback).
                discord.put("groupPolicy", if (guildAllowlist.isNotEmpty()) "allowlist" else "disabled")
                if (guildAllowlist.isNotEmpty()) {
                    val guilds = JSONObject()
                    guildAllowlist.forEach { guildId ->
                        guilds.put(guildId, JSONObject().apply {
                            put("requireMention", channelConfig.discordRequireMention)
                        })
                    }
                    discord.put("guilds", guilds)
                } else if (discord.has("guilds")) {
                    discord.remove("guilds")
                }
                managedChannels += "discord"
                if (guildAllowlist.isEmpty()) {
                    addLog("[andClaw] Discord guild allowlist is empty: guild messages are blocked")
                } else {
                    addLog("[andClaw] Discord guild allowlist applied: ${guildAllowlist.size} guild(s)")
                }
            } else if (channels.has("discord")) {
                channels.remove("discord")
            }

            if (channels.length() > 0) {
                val managedSummary = managedChannels.joinToString(", ").ifBlank { "none" }
                addLog("[andClaw] Channel config applied (managed=$managedSummary)")
            } else {
                json.remove("channels")
                addLog("[andClaw] Channel config cleared")
            }

                // 채널에 맞는 플러그인 활성화/비활성화
                val plugins = json.optJSONObject("plugins") ?: JSONObject().also { json.put("plugins", it) }
                val entries = plugins.optJSONObject("entries") ?: JSONObject().also { plugins.put("entries", it) }

                // Telegram
                val tgPlugin = entries.optJSONObject("telegram") ?: JSONObject()
                tgPlugin.put("enabled", channelConfig.telegramEnabled && channelConfig.telegramBotToken.isNotBlank())
                entries.put("telegram", tgPlugin)

                // Discord
                val dcPlugin = entries.optJSONObject("discord") ?: JSONObject()
                dcPlugin.put("enabled", channelConfig.discordEnabled && channelConfig.discordBotToken.isNotBlank())
                entries.put("discord", dcPlugin)

                // WhatsApp은 코어 채널이므로 plugins.entries 불필요 (stale entry 정리)
                if (entries.has("whatsapp")) {
                    entries.remove("whatsapp")
                }

                configFile.writeText(json.toString(2))
            } catch (e: Exception) {
                addLog("[andClaw] Channel config failed: ${e.message}")
            }
        }
    }

    /**
     * 번들/버전 불일치로 주입될 수 있는 known-invalid 키를 정리한다.
     * - channels.whatsapp.enabled (OpenClaw WhatsApp schema와 충돌)
     * - agents.defaults.session (현행 schema 미지원)
     */
    private fun sanitizeKnownIncompatibleConfigKeys(root: JSONObject): Boolean {
        var changed = false

        val agents = root.optJSONObject("agents")
        val defaults = agents?.optJSONObject("defaults")
        if (defaults != null && defaults.has("session")) {
            defaults.remove("session")
            changed = true
            addLog("[andClaw] Removed incompatible config key: agents.defaults.session")
        }

        val channels = root.optJSONObject("channels")
        val whatsapp = channels?.optJSONObject("whatsapp")
        if (whatsapp != null && whatsapp.has("enabled")) {
            whatsapp.remove("enabled")
            changed = true
            addLog("[andClaw] Removed incompatible config key: channels.whatsapp.enabled")
        }

        return changed
    }

    /**
     * 이전 세션에서 남은 좀비 게이트웨이 프로세스를 찾아서 죽인다.
     * 앱 재설치/강제종료 시 proot 프로세스가 남아 포트를 점유할 수 있다.
     */
    private fun killOrphanGatewayProcesses() {
        var killedCount = 0

        try {
            val ps = ProcessBuilder("ps", "-ef").start()
            val lines = ps.inputStream.bufferedReader().readLines()
            ps.waitFor()

            for (line in lines) {
                if (line.contains("openclaw") || line.contains("libproot.so")) {
                    // PID 추출 (ps -ef 형식: UID PID PPID ...)
                    val parts = line.trim().split("\\s+".toRegex())
                    val pid = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    if (pid == android.os.Process.myPid()) continue
                    try {
                        android.os.Process.killProcess(pid)
                        addLog("[andClaw] Killing orphan process: PID $pid")
                        killedCount++
                    } catch (_: Exception) {
                        // 권한 없는 프로세스는 무시
                    }
                }
            }
        } catch (e: Exception) {
            addLog("[andClaw] Process cleanup error (ignored): ${e.message}")
        }

        killedCount += killProcessesHoldingPort(GATEWAY_PORT)

        if (killedCount > 0) {
            // 포트 해제 대기
            Thread.sleep(500)
        }

        if (!waitForPortRelease(GATEWAY_PORT, timeoutMs = 2_500L)) {
            addLog("[andClaw] Warning: port $GATEWAY_PORT is still occupied after cleanup")
        }
    }

    private fun stopSupervisedGatewayIfRunning() {
        try {
            val result = prootManager.executeWithResult(
                command =
                    "export NODE_OPTIONS='--require /root/.openclaw-patch.js' && " +
                        "openclaw gateway stop >/dev/null 2>&1 || true",
                timeoutMs = 15_000L,
            ) ?: return
            if (!result.timedOut) {
                addLog("[andClaw] Requested existing supervised gateway stop")
            }
        } catch (_: Exception) {
            // 실패해도 fallback cleanup로 계속 진행
        }
    }

    private fun killProcessesHoldingPort(port: Int): Int {
        return try {
            val myUid = android.os.Process.myUid()
            val myPid = android.os.Process.myPid()
            val socketInodes = findListeningSocketInodes(port)
            if (socketInodes.isEmpty()) return 0

            val targetPids = findPidsHoldingSocketInodes(socketInodes, myUid)
                .filter { it != myPid }

            var killed = 0
            for (pid in targetPids) {
                try {
                    android.os.Process.killProcess(pid)
                    addLog("[andClaw] Killed process holding port $port: PID $pid")
                    killed++
                } catch (_: Exception) {
                    // 권한 없는 프로세스는 무시
                }
            }

            killed
        } catch (e: Exception) {
            addLog("[andClaw] Port cleanup error (ignored): ${e.message}")
            0
        }
    }

    private fun findListeningSocketInodes(port: Int): Set<String> {
        return buildSet {
            listOf("/proc/net/tcp", "/proc/net/tcp6").forEach { path ->
                val content = runCatching { File(path).readText() }.getOrNull() ?: return@forEach
                addAll(parseListeningSocketInodes(content, port))
            }
        }
    }

    private fun findPidsHoldingSocketInodes(socketInodes: Set<String>, uid: Int): Set<Int> {
        if (socketInodes.isEmpty()) return emptySet()

        return buildSet {
            val procDir = File("/proc")
            val pidDirs = procDir.listFiles() ?: return@buildSet
            for (pidDir in pidDirs) {
                val pid = pidDir.name.toIntOrNull() ?: continue
                if (!isSameUidProcess(pid, uid)) continue

                val fdDir = File(pidDir, "fd")
                val fdEntries = fdDir.listFiles() ?: continue
                for (fdEntry in fdEntries) {
                    val target = runCatching { android.system.Os.readlink(fdEntry.absolutePath) }.getOrNull() ?: continue
                    val inode = extractSocketInode(target) ?: continue
                    if (inode in socketInodes) {
                        add(pid)
                        break
                    }
                }
            }
        }
    }

    private fun isSameUidProcess(pid: Int, uid: Int): Boolean {
        return runCatching {
            val statusFile = File("/proc/$pid/status")
            if (!statusFile.exists()) return false
            val uidLine = statusFile.useLines { lines ->
                lines.firstOrNull { it.startsWith("Uid:") }
            } ?: return false
            val processUid = uidLine.split("\\s+".toRegex()).getOrNull(1)?.toIntOrNull() ?: return false
            processUid == uid
        }.getOrDefault(false)
    }

    private fun waitForPortRelease(port: Int, timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (findListeningSocketInodes(port).isEmpty()) return true
            killProcessesHoldingPort(port)
            Thread.sleep(200)
        }
        return findListeningSocketInodes(port).isEmpty()
    }

    private fun jsonArrayStringListEquals(array: JSONArray?, expected: List<String>): Boolean {
        if (array == null || array.length() != expected.size) return false
        return expected.indices.all { index -> array.optString(index) == expected[index] }
    }

    private fun mergeRequiredExtraArgs(array: JSONArray?): List<String> {
        val merged = mutableListOf<String>()
        if (array != null) {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim()
                if (value.isNotEmpty() && value !in merged) {
                    merged += value
                }
            }
        }
        ANDROID_CHROMIUM_EXTRA_ARGS.forEach { arg ->
            if (arg !in merged) {
                merged += arg
            }
        }
        return merged
    }

    /**
     * OpenClaw의 내장 OpenRouter 모델 ID 목록을 레지스트리 파일에서 추출한다.
     * 내장 모델은 ModelRegistry에서 compat 설정까지 포함된 정확한 정의를 갖고 있으므로
     * 커스텀 등록으로 덮어쓰면 안 된다.
     */
    private fun getBuiltInOpenRouterModelIds(): Set<String> {
        return try {
            OpenClawModelCatalogReader.loadOpenRouterModelIds(prootManager.rootfsDir)
        } catch (e: Exception) {
            addLog("[andClaw] Failed to read built-in models: ${e.message}")
            emptySet()
        }
    }

    /**
     * 비내장 모델을 models.json에 등록한다.
     * ModelRegistry가 이 파일을 직접 읽어서 모델을 로드한다.
     * 경로: ~/.openclaw/agents/main/agent/models.json
     */
    private fun writeModelsJson(
        modelEntries: List<ModelSelectionEntry>,
    ) {
        try {
            upsertProviderModelsJson(
                provider = "openrouter",
                providerConfig = JSONObject().apply {
                    put("baseUrl", "https://openrouter.ai/api/v1")
                    put("apiKey", "\${OPENROUTER_API_KEY}")
                    put("models", org.json.JSONArray().apply {
                        modelEntries
                            .map { it.copy(id = it.id.trim()) }
                            .filter { it.id.isNotBlank() }
                            .distinctBy { it.id }
                            .forEach { entry ->
                                put(buildModelEntryJson(entry))
                            }
                    })
                },
            )
        } catch (e: Exception) {
            addLog("[andClaw] Failed to write models.json: ${e.message}")
        }
    }

    private fun normalizeOpenAiCompatibleBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isBlank()) return "https://api.openai.com/v1"
        return trimmed
    }

    private fun writeOpenAiCompatibleModelsJson(
        modelEntries: List<ModelSelectionEntry>,
        baseUrl: String,
        apiKey: String,
    ) {
        try {
            upsertProviderModelsJson(
                provider = "openai-compatible",
                providerConfig = JSONObject().apply {
                    put("baseUrl", baseUrl)
                    put("api", "openai-completions")
                    if (apiKey.isNotBlank()) {
                        put("apiKey", "\${OPENAI_COMPAT_API_KEY}")
                    }
                    put("models", org.json.JSONArray().apply {
                        modelEntries
                            .map { it.copy(id = it.id.trim()) }
                            .filter { it.id.isNotBlank() }
                            .distinctBy { it.id }
                            .forEach { entry ->
                                put(buildModelEntryJson(entry))
                            }
                    })
                },
            )
        } catch (e: Exception) {
            addLog("[andClaw] Failed to write openai-compatible models.json: ${e.message}")
        }
    }

    private fun writeOllamaModelsJson(
        modelEntries: List<ModelSelectionEntry>,
        baseUrl: String,
        apiKey: String,
        apiProvider: String = "ollama",
    ) {
        try {
            val providerConfig = JSONObject().apply {
                put("baseUrl", baseUrl)
                put("api", "ollama")
                put("apiKey", when {
                    apiProvider == "ollama-cloud" && apiKey.isNotBlank() -> "OLLAMA_API_KEY"
                    apiProvider == "ollama-cloud" -> ""
                    apiKey.isNotBlank() -> "OLLAMA_API_KEY"
                    else -> "ollama-local"
                })
                put("models", org.json.JSONArray().apply {
                    modelEntries
                        .map { it.copy(id = it.id.trim()) }
                        .filter { it.id.isNotBlank() }
                        .distinctBy { it.id }
                        .forEach { entry ->
                            put(buildModelEntryJson(entry, api = "ollama"))
                        }
                })
            }
            upsertProviderModelsJson(
                provider = "ollama",
                providerConfig = providerConfig,
            )
            val configPath = File(prootManager.rootfsDir, "root/.openclaw/openclaw.json")
            val config = if (configPath.exists()) {
                runCatching { JSONObject(configPath.readText()) }.getOrElse { JSONObject() }
            } else {
                JSONObject()
            }
            val models = config.optJSONObject("models") ?: JSONObject().also { config.put("models", it) }
            val providers = models.optJSONObject("providers") ?: JSONObject().also { models.put("providers", it) }
            providers.put("ollama", JSONObject(providerConfig.toString()))
            configPath.parentFile?.mkdirs()
            configPath.writeText(config.toString(2))
        } catch (e: Exception) {
            addLog("[andClaw] Failed to write ollama models.json: ${e.message}")
        }
    }

    private fun modelsJsonFile(): File =
        File(prootManager.rootfsDir, "root/.openclaw/agents/main/agent/models.json")

    private fun readRegisteredCustomModelIdsByProvider(): Map<String, Set<String>> {
        return runCatching {
            val file = modelsJsonFile()
            if (!file.exists()) return emptyMap()
            val root = JSONObject(file.readText())
            val providers = root.optJSONObject("providers") ?: return emptyMap()
            buildMap {
                val providerKeys = providers.keys()
                while (providerKeys.hasNext()) {
                    val provider = providerKeys.next()
                    val providerObject = providers.optJSONObject(provider) ?: continue
                    val models = providerObject.optJSONArray("models") ?: continue
                    val ids = buildSet {
                        for (index in 0 until models.length()) {
                            val modelObject = models.optJSONObject(index) ?: continue
                            val id = modelObject.optString("id").trim()
                            if (id.isNotBlank()) add(id)
                        }
                    }
                    if (ids.isNotEmpty()) {
                        put(provider, ids)
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun upsertProviderModelsJson(
        provider: String,
        providerConfig: JSONObject,
    ) {
        val file = modelsJsonFile()
        file.parentFile?.mkdirs()
        val root = if (file.exists()) {
            runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        val providers = root.optJSONObject("providers") ?: JSONObject().also { root.put("providers", it) }
        providers.put(provider, JSONObject(providerConfig.toString()))
        file.writeText(root.toString(2))
    }

    private fun removeProviderModelsJson(provider: String) {
        val file = modelsJsonFile()
        if (!file.exists()) return
        runCatching {
            val root = JSONObject(file.readText())
            val providers = root.optJSONObject("providers") ?: return
            providers.remove(provider)
            if (providers.length() == 0) {
                file.delete()
                return
            }
            file.writeText(root.toString(2))
        }.onFailure { throwable ->
            addLog("[andClaw] Failed to remove $provider from models.json: ${throwable.message}")
        }
    }

    private fun buildModelEntryJson(entry: ModelSelectionEntry, api: String = "openai-completions"): JSONObject {
        val inputTypes = org.json.JSONArray().apply {
            put("text")
            if (entry.supportsImages) put("image")
        }
        return JSONObject().apply {
            put("id", entry.id)
            put("name", entry.id)
            put("api", api)
            put("reasoning", entry.supportsReasoning)
            put("input", inputTypes)
            put("cost", JSONObject().apply {
                put("input", 0)
                put("output", 0)
                put("cacheRead", 0)
                put("cacheWrite", 0)
            })
            put("contextWindow", entry.contextLength.coerceAtLeast(1))
            put("maxTokens", entry.maxOutputTokens.coerceAtLeast(1))
        }
    }

    private fun ensurePatchFile() {
        val patchFile = File(prootManager.rootfsDir, "root/.openclaw-patch.js")
        val needsUpdate = !patchFile.exists() ||
            !patchFile.readText().contains("uncaughtException")
        if (needsUpdate) {
            addLog("[andClaw] Creating Node.js compatibility patch...")
            patchFile.writeText(buildString {
                // Prevent undici TLS null-socket crash (e.g. Telegram fetch fallback)
                appendLine("process.on('uncaughtException', function(err) {")
                appendLine("  if (err && err.message && err.message.includes('setServername')) {")
                appendLine("    console.error('[openclaw-patch] Suppressed TLS crash:', err.message);")
                appendLine("    return;")
                appendLine("  }")
                appendLine("  console.error('[openclaw] Uncaught exception:', err);")
                appendLine("  process.exit(1);")
                appendLine("});")
                appendLine()
                appendLine("const os = require('os');")
                appendLine("const _ni = os.networkInterfaces;")
                appendLine("os.networkInterfaces = function() {")
                appendLine("  try { return _ni.call(this); } catch(e) {")
                appendLine("    return {")
                appendLine("      lo: [{")
                appendLine("        address: '127.0.0.1',")
                appendLine("        netmask: '255.0.0.0',")
                appendLine("        family: 'IPv4',")
                appendLine("        mac: '00:00:00:00:00:00',")
                appendLine("        internal: true,")
                appendLine("        cidr: '127.0.0.1/8'")
                appendLine("      }]")
                appendLine("    };")
                appendLine("  }")
                appendLine("};")
            })
        }
    }

    internal fun appendGatewayDiagnosticLog(line: String) {
        addLog(line)
    }

    private fun addLog(line: String) {
        Log.i(TAG, line)
        val current = _logLines.value.toMutableList()
        current.add(line)
        // 최대 1000줄 유지
        _logLines.value = if (current.size > 1000) current.takeLast(1000) else current
    }

    private suspend fun readProcessOutput(process: Process) = withContext(Dispatchers.IO) {
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let {
                        addLog(it)
                        parseLogLine(it)
                    }
                }
            }
        } catch (_: Exception) {
            // 프로세스 종료 시 자연스럽게 발생
        }
    }

    /**
     * OpenClaw 로그에서 상태 정보를 파싱한다.
     */
    private fun parseLogLine(line: String) {
        val lineLower = line.lowercase()

        // 게이트웨이 ready 상태 감지 (HTTP 서버 리슨 시작)
        val isPortConflict = lineLower.contains("already listening on ws://127.0.0.1:18789") ||
            lineLower.contains("port 18789 is already in use")
        if (isPortConflict) {
            clearStartupAttempt()
            _gatewayState.value = _gatewayState.value.copy(
                status = GatewayStatus.ERROR,
                errorMessage = "Port 18789 is already in use",
            )
            return
        }

        if (
            lineLower.contains("listening on") &&
            lineLower.contains("18789") &&
            !lineLower.contains("already listening")
        ) {
            clearStartupAttempt()
            _gatewayState.value = _gatewayState.value.copy(
                status = GatewayStatus.RUNNING,
                dashboardReady = true,
            )
            addLog("[andClaw] Gateway is ready!")
            startPairingObserver()
        }
    }

    private fun parseDiscordGuildAllowlist(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw
            .split(',', '\n', ';', '\t')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { normalizeDiscordGuildAllowlistEntry(it) }
            .distinct()
            .toList()
    }

    private fun normalizeDiscordGuildAllowlistEntry(value: String): String? {
        val slug = value
            .trim()
            .lowercase()
            .replace("^#".toRegex(), "")
            .replace("[^a-z0-9]+".toRegex(), "-")
            .replace("^-+|-+$".toRegex(), "")

        return slug.ifBlank { null }
    }

    // ── Pairing 관리 (조회: 파일 직접 읽기, 승인/거부: OpenClaw 위임) ──

    private val credentialsDir: File
        get() = File(prootManager.rootfsDir, "root/.openclaw/credentials")

    /**
     * 활성화된 채널의 대기 중인 pairing 요청 목록을 조회한다.
     * credentials/<channel>-pairing.json 파일을 직접 읽는다.
     * CLI 실행 시 새 Node.js 프로세스가 메모리를 초과해 OOM kill이 발생하므로 파일 방식 사용.
     */
    suspend fun listPairingRequests(channelConfig: ChannelConfig): List<PairingRequest> {
        if (!isRunning) return emptyList()
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<PairingRequest>()
            val channels = buildList {
                if (channelConfig.telegramEnabled && channelConfig.telegramBotToken.isNotBlank()) add("telegram")
                if (channelConfig.discordEnabled && channelConfig.discordBotToken.isNotBlank()) add("discord")
                if (channelConfig.whatsappEnabled) add("whatsapp")
            }
            for (channel in channels) {
                try {
                    val file = File(credentialsDir, "$channel-pairing.json")
                    if (!file.exists()) continue
                    val json = JSONObject(file.readText())
                    val requests = json.optJSONArray("requests") ?: continue
                    for (i in 0 until requests.length()) {
                        val req = requests.getJSONObject(i)
                        val code = req.optString("code", "")
                        val meta = req.optJSONObject("meta")
                        val username = meta?.optString("name", "") ?: meta?.optString("tag", "") ?: ""
                        if (code.isNotBlank()) {
                            results.add(PairingRequest(channel = channel, code = code, username = username))
                        }
                    }
                } catch (_: Exception) { }
            }
            results
        }
    }

    /**
     * pairing 요청을 승인한다.
     * OpenClaw CLI 승인 경로를 사용한다.
     */
    suspend fun approvePairing(channel: String, code: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedChannel = channel.trim().lowercase()
                val normalizedCode = code.trim().uppercase()

                if (!normalizedChannel.matches(Regex("^[a-z][a-z0-9_-]{0,63}$"))) {
                    addLog("[andClaw] Pairing approve failed: invalid channel")
                    return@withContext false
                }
                if (!normalizedCode.matches(Regex("^[A-Z0-9-]{4,32}$"))) {
                    addLog("[andClaw] Pairing approve failed: invalid code")
                    return@withContext false
                }

                val command = "export NODE_OPTIONS='--require /root/.openclaw-patch.js' && " +
                    "(openclaw pairing approve '${escapeSingleQuotedShell(normalizedChannel)}' '${escapeSingleQuotedShell(normalizedCode)}' 2>&1 " +
                    "|| openclaw pairing approve --channel '${escapeSingleQuotedShell(normalizedChannel)}' --code '${escapeSingleQuotedShell(normalizedCode)}' 2>&1)"
                val result = prootManager.executeWithResult(
                    command = command,
                    timeoutMs = 120_000,
                    extraEnv = buildOpenClawCliEnv(),
                ) ?: return@withContext false

                val success = !result.timedOut && result.exitCode == 0
                if (success) {
                    addLog("[andClaw] Pairing approved via OpenClaw CLI: $normalizedChannel $normalizedCode")
                } else {
                    val reason = result.output.lineSequence().lastOrNull { it.isNotBlank() }
                        ?: "exit=${result.exitCode}"
                    addLog("[andClaw] Pairing approve failed: $reason")
                }
                success
            } catch (e: Exception) {
                addLog("[andClaw] Pairing approve failed: ${e.message}")
                false
            }
        }
    }

    /**
     * pairing 요청을 거부한다.
     * OpenClaw CLI에 deny 명령이 없어 OpenClaw 내부 store 유틸로 락 기반 제거를 수행한다.
     */
    suspend fun denyPairing(channel: String, code: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val normalizedChannel = channel.trim().lowercase()
                val normalizedCode = code.trim().uppercase()

                if (!normalizedChannel.matches(Regex("^[a-z][a-z0-9_-]{0,63}$"))) {
                    addLog("[andClaw] Pairing deny failed: invalid channel")
                    return@withContext false
                }
                if (!normalizedCode.matches(Regex("^[A-Z0-9-]{4,32}$"))) {
                    addLog("[andClaw] Pairing deny failed: invalid code")
                    return@withContext false
                }

                // heredoc + 환경변수로 stdin 전달 (파일 생성 없이 ESM 스크립트 실행)
                val command = "DENY_CHANNEL='${escapeSingleQuotedShell(normalizedChannel)}' " +
                    "DENY_CODE='${escapeSingleQuotedShell(normalizedCode)}' " +
                    "node --input-type=module 2>&1 <<'__DENY_EOF__'\n${openClawPairingDenyScript}\n__DENY_EOF__"
                val result = prootManager.executeWithResult(
                    command = command,
                    timeoutMs = 120_000,
                    extraEnv = buildOpenClawCliEnv(),
                ) ?: return@withContext false

                val success = !result.timedOut && result.exitCode == 0
                if (!success) {
                    val output = result.output.trim()
                    val reason = output.lineSequence().lastOrNull { it.isNotBlank() && !it.startsWith("Node.js v") }
                        ?: output.lineSequence().lastOrNull { it.isNotBlank() }
                        ?: "exit=${result.exitCode}"
                    addLog("[andClaw] Pairing deny failed: $reason")
                    if (output.lines().size > 1) {
                        addLog("[andClaw] Pairing deny output: ${output.take(500)}")
                    }
                    return@withContext false
                }

                addLog("[andClaw] Pairing denied via OpenClaw store lock: $normalizedChannel $normalizedCode")
                true
            } catch (e: Exception) {
                addLog("[andClaw] Pairing deny failed: ${e.message}")
                false
            }
        }
    }

    private fun startPairingObserver() {
        if (pairingFileObserver != null) return
        val dir = credentialsDir
        dir.mkdirs()

        val eventMask = FileObserver.MODIFY or FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_TO
        pairingFileObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, eventMask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && path.contains("pairing")) {
                        refreshPairingRequests()
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.absolutePath, eventMask) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && path.contains("pairing")) {
                        refreshPairingRequests()
                    }
                }
            }
        }.also { it.startWatching() }

        // 시작 시 한 번 조회
        refreshPairingRequests()
    }

    private fun stopPairingObserver() {
        pairingFileObserver?.stopWatching()
        pairingFileObserver = null
        _pairingRequests.value = emptyList()
    }

    fun refreshPairingRequests() {
        scope?.launch {
            _pairingRequests.value = listPairingRequests(lastChannelConfig)
        }
    }

    private fun buildOpenClawCliEnv(): Map<String, String> {
        fun resolved(value: String): String = value.ifBlank { "__andclaw_env_placeholder__" }

        return buildMap {
            put("OPENROUTER_API_KEY", "__andclaw_env_placeholder__")
            put("OPENAI_API_KEY", "__andclaw_env_placeholder__")
            put("OPENAI_COMPAT_API_KEY", "__andclaw_env_placeholder__")
            put("OLLAMA_API_KEY", "__andclaw_env_placeholder__")
            put("ANTHROPIC_API_KEY", "__andclaw_env_placeholder__")
            put("GOOGLE_API_KEY", "__andclaw_env_placeholder__")
            put("GEMINI_API_KEY", "__andclaw_env_placeholder__")
            githubCopilotAuthEnv().forEach { (key, value) -> put(key, value) }
            if (!containsKey("COPILOT_GITHUB_TOKEN")) put("COPILOT_GITHUB_TOKEN", "__andclaw_env_placeholder__")
            if (!containsKey("GH_TOKEN")) put("GH_TOKEN", "__andclaw_env_placeholder__")
            if (!containsKey("GITHUB_TOKEN")) put("GITHUB_TOKEN", "__andclaw_env_placeholder__")
            put("ZAI_API_KEY", "__andclaw_env_placeholder__")
            put("Z_AI_API_KEY", "__andclaw_env_placeholder__")
            put("KIMI_API_KEY", "__andclaw_env_placeholder__")
            put("KIMICODE_API_KEY", "__andclaw_env_placeholder__")
            put("MINIMAX_API_KEY", "__andclaw_env_placeholder__")
            put("BRAVE_API_KEY", resolved(lastBraveSearchApiKey))
            put("BRAVE_SEARCH_API_KEY", resolved(lastBraveSearchApiKey))
            if (lastMemorySearchEnabled &&
                supportsMemorySearchRemoteApiKey(lastMemorySearchProvider) &&
                lastMemorySearchApiKey.isNotBlank()
            ) {
                put("MEMORY_SEARCH_API_KEY", lastMemorySearchApiKey)
            }
            put("TELEGRAM_BOT_TOKEN", resolved(lastChannelConfig.telegramBotToken))
            put("DISCORD_BOT_TOKEN", resolved(lastChannelConfig.discordBotToken))

            if (lastApiKey.isNotBlank()) {
                when (lastApiProvider) {
                    "anthropic" -> put("ANTHROPIC_API_KEY", lastApiKey)
                    "openai" -> put("OPENAI_API_KEY", lastApiKey)
                    "zai" -> {
                        put("ZAI_API_KEY", lastApiKey)
                        put("Z_AI_API_KEY", lastApiKey)
                    }
                    "kimi-coding" -> {
                        put("KIMI_API_KEY", lastApiKey)
                        put("KIMICODE_API_KEY", lastApiKey)
                    }
                    "minimax" -> put("MINIMAX_API_KEY", lastApiKey)
                    "openai-compatible" -> put("OPENAI_COMPAT_API_KEY", lastApiKey)
                    "ollama" -> put("OLLAMA_API_KEY", lastApiKey)
                    "ollama-cloud" -> put("OLLAMA_API_KEY", lastApiKey)
                    "openrouter" -> put("OPENROUTER_API_KEY", lastApiKey)
                    "google" -> {
                        put("GOOGLE_API_KEY", lastApiKey)
                        put("GEMINI_API_KEY", lastApiKey)
                    }
                }
            }
        }
    }

    private fun escapeSingleQuotedShell(value: String): String = value.replace("'", "'\"'\"'")

    @Suppress("deprecation")
    private fun getProcessId(process: Process?): Int? {
        return try {
            val field = process?.javaClass?.getDeclaredField("pid")
            field?.isAccessible = true
            field?.getInt(process)
        } catch (_: Exception) {
            null
        }
    }
}
