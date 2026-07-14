package com.coderred.andclaw.ui.screen.dashboard

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coderred.andclaw.AndClawApp
import com.coderred.andclaw.R
import com.coderred.andclaw.data.ChannelConfig
import com.coderred.andclaw.data.GatewayState
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.LaunchApiKeyWarning
import com.coderred.andclaw.data.PairingRequest
import com.coderred.andclaw.data.SetupState
import com.coderred.andclaw.data.SessionLogEntry
import com.coderred.andclaw.service.GatewayService
import com.coderred.andclaw.service.OpenAiConnectionTransitionState
import com.coderred.andclaw.service.OpenAiConnectionTransitionUiState
import com.coderred.andclaw.data.OpenRouterModel
import com.coderred.andclaw.data.parseOpenRouterModels
import com.coderred.andclaw.proroot.BundleUpdateFailureState
import com.coderred.andclaw.proroot.BundleUpdateOutcome
import com.coderred.andclaw.proroot.GatewayWsClient
import com.coderred.andclaw.proroot.WhatsAppLoginCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "DashboardViewModel"
        private const val WHATSAPP_QR_EXPIRES_MS = 120_000L
        private const val WHATSAPP_LOGIN_GUARD_TIMEOUT_MS = 4L * 60L * 1000L
        private const val WHATSAPP_LOGIN_GUARD_REFRESH_INTERVAL_MS = 60_000L
        private const val WHATSAPP_STABLE_CONNECTED_REQUIRED_COUNT = 4
        private const val WHATSAPP_GATEWAY_RESTART_READY_TIMEOUT_MS = 20_000L
        private const val WHATSAPP_GATEWAY_RESTART_RETRY_INTERVAL_MS = 250L
        private const val DASHBOARD_TOKEN_READ_ATTEMPTS = 8
        private const val DASHBOARD_TOKEN_READ_DELAY_MS = 250L
    }

    private val app = application as AndClawApp

    val gatewayState: StateFlow<GatewayState> = app.processManager.gatewayState
        .stateIn(viewModelScope, SharingStarted.Eagerly, GatewayState())

    val gatewayUiState: StateFlow<DashboardGatewayUiState> = combine(
        gatewayState,
        OpenAiConnectionTransitionState.state,
    ) { state, transition ->
        resolveDashboardGatewayUiState(state, transition)
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, DashboardGatewayUiState())

    val logLines: StateFlow<List<String>> = app.processManager.logLines
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val apiProvider: StateFlow<String> = app.preferencesManager.apiProvider
        .stateIn(viewModelScope, SharingStarted.Eagerly, "openrouter")

    val apiKey: StateFlow<String> = app.preferencesManager.apiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val selectedModel: StateFlow<String> = app.preferencesManager.selectedModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val channelConfig: StateFlow<ChannelConfig> = app.preferencesManager.channelConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChannelConfig())

    val isLogSectionUnlocked: StateFlow<Boolean> = app.preferencesManager.logSectionUnlocked
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _batteryLevel = MutableStateFlow(0)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _memoryUsageMb = MutableStateFlow(0L)
    val memoryUsageMb: StateFlow<Long> = _memoryUsageMb.asStateFlow()

    private val _dashboardLoading = MutableStateFlow(false)
    val dashboardLoading: StateFlow<Boolean> = _dashboardLoading.asStateFlow()

    private val _bundleUpdateFailure = MutableStateFlow<BundleUpdateFailureState?>(null)
    val bundleUpdateFailure: StateFlow<BundleUpdateFailureState?> = _bundleUpdateFailure.asStateFlow()

    private val _bundleActionInProgress = MutableStateFlow(false)
    val bundleActionInProgress: StateFlow<Boolean> = _bundleActionInProgress.asStateFlow()

    private val _bundleActionMessage = MutableStateFlow<String?>(null)
    val bundleActionMessage: StateFlow<String?> = _bundleActionMessage.asStateFlow()

    private val _bundleActionType = MutableStateFlow<BundleActionType?>(null)
    val bundleActionType: StateFlow<BundleActionType?> = _bundleActionType.asStateFlow()

    private val _runtimeChangeGuidanceDialog = MutableStateFlow<RuntimeChangeGuidanceDialogState?>(null)
    val runtimeChangeGuidanceDialog: StateFlow<RuntimeChangeGuidanceDialogState?> =
        _runtimeChangeGuidanceDialog.asStateFlow()
    private var lastShownRuntimeChangeGuidanceError: String? = null

    private val _availableModels = MutableStateFlow<List<OpenRouterModel>>(emptyList())
    val availableModels: StateFlow<List<OpenRouterModel>> = _availableModels.asStateFlow()

    private val _isLoadingModels = MutableStateFlow(false)
    val isLoadingModels: StateFlow<Boolean> = _isLoadingModels.asStateFlow()

    private val _modelLoadError = MutableStateFlow<String?>(null)
    val modelLoadError: StateFlow<String?> = _modelLoadError.asStateFlow()

    private val _sessionLogs = MutableStateFlow<List<SessionLogEntry>>(emptyList())
    val sessionLogs: StateFlow<List<SessionLogEntry>> = _sessionLogs.asStateFlow()

    private val _isLoadingSessionLogs = MutableStateFlow(false)
    val isLoadingSessionLogs: StateFlow<Boolean> = _isLoadingSessionLogs.asStateFlow()

    val pairingRequests: StateFlow<List<PairingRequest>> = app.processManager.pairingRequests
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val setupState: StateFlow<SetupState> = app.setupManager.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, SetupState())

    private val _pairingActionProgress = MutableStateFlow<PairingActionProgress?>(null)
    val pairingActionProgress: StateFlow<PairingActionProgress?> = _pairingActionProgress.asStateFlow()

    // ── WhatsApp QR ──
    private val _whatsappQrState = MutableStateFlow<WhatsAppQrState>(WhatsAppQrState.Idle)
    val whatsappQrState: StateFlow<WhatsAppQrState> = _whatsappQrState.asStateFlow()

    private var wsClient: GatewayWsClient? = null
    private var whatsappQrJob: Job? = null
    private var restartJob: Job? = null
    private var whatsappLoginGuardKeepAliveJob: Job? = null
    private val whatsappLoginGuardLock = Any()
    private var isWhatsAppLoginCoordinatorOwner: Boolean = false
    private var isWhatsAppLoginGuardActive: Boolean = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            app.preferencesManager.backfillSelectedModelProviderIfMissing()
            // 앱 시작 시 로그 섹션은 기본 잠금 상태로 초기화한다.
            app.preferencesManager.setLogSectionUnlocked(false)
        }

        viewModelScope.launch {
            while (isActive) {
                updateBatteryInfo()
                updateMemoryInfo()
                refreshBundleUpdateFailure()
                delay(5000)
            }
        }
    }

    fun startGateway() {
        resetRuntimeChangeGuidance()
        GatewayService.start(getApplication(), source = "dashboard:manual_start")
    }

    fun getLaunchApiKeyWarning(onResult: (LaunchApiKeyWarning) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val warning = app.preferencesManager.getLaunchApiKeyWarning()
            withContext(Dispatchers.Main) {
                onResult(warning)
            }
        }
    }

    fun stopGatewayForMissingModelSelection() {
        GatewayService.stopForMissingModelSelection(getApplication(), source = "dashboard:missing_model_selection")
    }

    fun stopGateway() {
        GatewayService.stop(getApplication(), source = "dashboard:manual_stop")
    }

    fun restartGateway() {
        resetRuntimeChangeGuidance()
        GatewayService.restart(
            getApplication(),
            userInitiated = true,
            source = "dashboard:manual_restart",
        )
    }

    fun acknowledgeError(observedError: DashboardErrorUiState) {
        acknowledgeDashboardError(
            observedError = observedError,
            currentVisibleError = gatewayUiState.value.error,
        )
    }

    fun openDashboard(context: Context) {
        viewModelScope.launch {
            val configFile = java.io.File(app.prorootManager.rootfsDir, "root/.openclaw/openclaw.json")
            val url = withContext(Dispatchers.IO) {
                resolveDashboardUrl(
                    readToken = { readGatewayAuthTokenFromConfig(configFile) },
                    attempts = DASHBOARD_TOKEN_READ_ATTEMPTS,
                    delayMs = DASHBOARD_TOKEN_READ_DELAY_MS,
                    usesTls = app.processManager.gatewayUsesTls,
                )
            }
            if (url != null) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } else {
                Log.w(TAG, "Skipping dashboard open: gateway auth token unavailable")
            }
        }
    }

    fun setSelectedModel(model: OpenRouterModel) {
        viewModelScope.launch(Dispatchers.IO) {
            app.preferencesManager.setSelectedModel(model)
            val provider = app.preferencesManager.apiProvider.first()
            val openAiCompatBaseUrl = app.preferencesManager.openAiCompatibleBaseUrl.first()
            val openAiConnectionMode = app.preferencesManager.openAiConnectionMode.first()
            app.processManager.ensureOpenClawConfig(
                apiProvider = provider,
                selectedModel = model.id,
                openAiCompatibleBaseUrl = openAiCompatBaseUrl,
                modelReasoning = model.supportsReasoning,
                modelImages = model.supportsImages,
                modelContext = model.contextLength,
                modelMaxOutput = model.maxOutputTokens,
                openAiConnectionMode = openAiConnectionMode,
            )
            restartGatewayIfRunning(source = "dashboard:selected_model_changed")
        }
    }

    private fun restartGatewayIfRunning(
        delayMs: Long = 700L,
        source: String = "dashboard:restart_if_running",
    ) {
        if (!app.processManager.isRunning) return
        restartJob?.cancel()
        restartJob = viewModelScope.launch {
            delay(delayMs)
            val context = getApplication<Application>()
            GatewayService.restart(context, userInitiated = false, source = source)
        }
    }

    private suspend fun awaitWhatsAppConnectedWithGrace(
        client: GatewayWsClient,
        timeoutMs: Long = 18_000L,
        intervalMs: Long = 1_500L,
        requiredConnectedCount: Int = WHATSAPP_STABLE_CONNECTED_REQUIRED_COUNT,
        probe: Boolean = true,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var consecutiveConnected = 0
        while (System.currentTimeMillis() < deadline) {
            val remainingMs = deadline - System.currentTimeMillis()
            if (remainingMs <= 0L) break
            val probeTimeoutMs = minOf(remainingMs, 4_000L)
            val connected = withTimeoutOrNull(probeTimeoutMs) {
                withContext(Dispatchers.IO) {
                    client.isWhatsAppChannelConnected(
                        probe = probe,
                        statusTimeoutMs = probeTimeoutMs.coerceAtMost(5_000L),
                    )
                }
            }
            if (connected == true) {
                consecutiveConnected += 1
                if (consecutiveConnected >= requiredConnectedCount) return true
            } else {
                consecutiveConnected = 0
            }
            delay(intervalMs)
        }
        return false
    }

    private suspend fun waitForGatewayRestartReady(
        timeoutMs: Long = WHATSAPP_GATEWAY_RESTART_READY_TIMEOUT_MS,
        intervalMs: Long = WHATSAPP_GATEWAY_RESTART_RETRY_INTERVAL_MS,
        requireTransition: Boolean = false,
        previousPid: Int? = null,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        val deadline = System.currentTimeMillis() + timeoutMs
        var sawRestartTransition = !requireTransition
        while (System.currentTimeMillis() < deadline) {
            val state = app.processManager.gatewayState.value
            val pidChanged = previousPid != null && state.pid != null && previousPid != state.pid
            when (state.status) {
                GatewayStatus.RUNNING -> {
                    if (sawRestartTransition) return true
                    if (!requireTransition) return true
                    if (pidChanged) return true
                    val elapsedMs = System.currentTimeMillis() - startedAt
                    if (elapsedMs >= 3_000L) {
                        // 상태 폴링 간격/StateFlow conflation으로 STOPPING/STARTING 전이를 놓칠 수 있다.
                        // 그 경우에도 RUNNING이 충분히 지속되면 재시작 준비 완료로 간주한다.
                        return true
                    }
                }
                GatewayStatus.STARTING, GatewayStatus.STOPPED, GatewayStatus.STOPPING, GatewayStatus.ERROR -> {
                    sawRestartTransition = true
                }
            }
            delay(intervalMs)
        }
        return false
    }

    fun loadSessionLogs() {
        if (_isLoadingSessionLogs.value) return
        _isLoadingSessionLogs.value = true

        viewModelScope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    app.processManager.getSessionLogEntries()
                }
                _sessionLogs.value = entries
            } catch (_: Exception) {
                _sessionLogs.value = emptyList()
            } finally {
                _isLoadingSessionLogs.value = false
            }
        }
    }

    fun fetchModels() {
        if (_isLoadingModels.value) return
        _isLoadingModels.value = true
        _modelLoadError.value = null

        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    val url = URL("https://openrouter.ai/api/v1/models")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.setRequestProperty("Accept", "application/json")
                    try {
                        if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        parseOpenRouterModels(body)
                    } finally {
                        conn.disconnect()
                    }
                }
                _availableModels.value = models
            } catch (e: Exception) {
                _modelLoadError.value = e.message
            } finally {
                _isLoadingModels.value = false
            }
        }
    }

    // ── Pairing ──

    fun approvePairing(request: PairingRequest) {
        if (_pairingActionProgress.value != null) return
        _pairingActionProgress.value = PairingActionProgress(
            action = PairingActionType.APPROVE,
            request = request,
        )
        viewModelScope.launch {
            try {
                app.processManager.approvePairing(request.channel, request.code)
                app.processManager.refreshPairingRequests()
            } finally {
                _pairingActionProgress.value = null
            }
        }
    }

    fun denyPairing(request: PairingRequest) {
        if (_pairingActionProgress.value != null) return
        _pairingActionProgress.value = PairingActionProgress(
            action = PairingActionType.DENY,
            request = request,
        )
        viewModelScope.launch {
            try {
                app.processManager.denyPairing(request.channel, request.code)
                app.processManager.refreshPairingRequests()
            } finally {
                _pairingActionProgress.value = null
            }
        }
    }

    // ── WhatsApp QR ──

    fun startWhatsAppQr() {
        if (whatsappQrJob?.isActive == true) return
        if (!WhatsAppLoginCoordinator.tryAcquire()) {
            _whatsappQrState.value = WhatsAppQrState.Error(
                "WhatsApp login is already running in another screen. Please wait."
            )
            return
        }
        isWhatsAppLoginCoordinatorOwner = true
        whatsappQrJob?.cancel()
        // 게이트웨이가 꺼져 있으면 시작 확인을 요청한다.
        if (app.processManager.gatewayState.value.status != GatewayStatus.RUNNING &&
            app.processManager.gatewayState.value.status != GatewayStatus.STARTING
        ) {
            _whatsappQrState.value = WhatsAppQrState.GatewayNotRunning
            return
        }
        _whatsappQrState.value = WhatsAppQrState.Loading
        whatsappQrJob = viewModelScope.launch {
            val appContext = getApplication<Application>().applicationContext
            try {
                var client = GatewayWsClient(app.prorootManager, app.processManager.gatewayUsesTls)
                wsClient = client
                // WebSocket 연결을 먼저 수립하여 이후 RPC 호출을 빠르게 한다.
                withContext(Dispatchers.IO) { client.connect(openTimeoutMs = 5_000L, handshakeTimeoutMs = 5_000L) }
                var qrAttempt = 0

                val snapshot = withContext(Dispatchers.IO) {
                    client.getWhatsAppChannelSnapshot(probe = false)
                }
                val wasWhatsAppProviderRunning = snapshot?.running == true

                // 401/logged out 루프 감지 → 선제적으로 creds 정리 + 재시작
                if (snapshot?.is401Loop == true) {
                    app.processManager.appendGatewayDiagnosticLog("[andClaw][Diag] detected WhatsApp 401 loop; preemptive creds purge + restart")
                    // creds 파일이 있으면 삭제, 없어도 메모리의 stale session 정리를 위해 restart
                    withContext(Dispatchers.IO) {
                        client.purgeStaleWhatsAppCredsIfNeeded(force = true)
                    }
                    if (app.processManager.isRunning) {
                        val preRestartPid = app.processManager.gatewayState.value.pid
                        restartGatewayIfRunning(delayMs = 0L, source = "dashboard:whatsapp_401_recovery")
                        val restartReady = waitForGatewayRestartReady(
                            requireTransition = true,
                            previousPid = preRestartPid,
                        )
                        if (!restartReady) {
                            _whatsappQrState.value = WhatsAppQrState.Error("Gateway restarting. Tap Connect again.")
                            client.close()
                            wsClient = null
                            return@launch
                        }
                        client.close()
                        client = GatewayWsClient(app.prorootManager, app.processManager.gatewayUsesTls)
                        wsClient = client
                    }
                } else {
                    val purgedStaleCreds = withContext(Dispatchers.IO) {
                        client.purgeStaleWhatsAppCredsIfNeeded()
                    }
                    if (purgedStaleCreds && wasWhatsAppProviderRunning) {
                        val preRestartPid = app.processManager.gatewayState.value.pid
                        restartGatewayIfRunning(delayMs = 0L, source = "dashboard:whatsapp_qr_restart")
                        val restartReady = waitForGatewayRestartReady(
                            requireTransition = true,
                            previousPid = preRestartPid,
                        )
                        if (!restartReady) {
                            _whatsappQrState.value = WhatsAppQrState.Error("Gateway restarting. Tap Connect again.")
                            client.close()
                            wsClient = null
                            return@launch
                        }
                        client.close()
                        client = GatewayWsClient(app.prorootManager, app.processManager.gatewayUsesTls)
                        wsClient = client
                    }
                }

                startWhatsAppLoginGuardKeepAlive(appContext)

                val isolated = withContext(Dispatchers.IO) {
                    client.ensureWhatsAppLoginIsolation()
                }
                if (isolated) {
                    delay(500)
                }

                var qrData = withContext(Dispatchers.IO) { client.startWhatsAppLogin() }
                if (qrData == null) {
                    if (client.isLastCallWhatsAppAlreadyLinked()) {
                        val connected = awaitWhatsAppConnectedWithGrace(client)
                        if (connected) {
                            _whatsappQrState.value = WhatsAppQrState.Connected
                            delay(1500)
                            _whatsappQrState.value = WhatsAppQrState.Idle
                            client.close()
                            wsClient = null
                            return@launch
                        }
                        qrData = withContext(Dispatchers.IO) { client.startWhatsAppLogin(force = true) }
                    }
                }
                if (qrData == null) {
                    val reason = client.getLastCallErrorMessage()
                    val message = if (reason.isNullOrBlank()) {
                        "Failed to get QR code"
                    } else {
                        "Failed to get QR code: $reason"
                    }
                    _whatsappQrState.value = WhatsAppQrState.Error(message)
                    client.close()
                    wsClient = null
                    return@launch
                }

                val isDataUrl = qrData.startsWith("data:image/")
                qrAttempt += 1
                val issuedAtMs = System.currentTimeMillis()
                _whatsappQrState.value = WhatsAppQrState.QrReady(
                    qrData = qrData,
                    isDataUrl = isDataUrl,
                    attempt = qrAttempt,
                    issuedAtMs = issuedAtMs,
                    expiresAtMs = issuedAtMs + WHATSAPP_QR_EXPIRES_MS,
                )

                // 로그인 완료 대기
                val success = withContext(Dispatchers.IO) { client.waitWhatsAppLogin() }
                if (success) {
                    _whatsappQrState.value = WhatsAppQrState.Connected
                    delay(3000)
                    _whatsappQrState.value = WhatsAppQrState.Idle
                } else {
                    if (client.isLastCallWhatsAppRestartRequired()) {
                        val connectedAfterGrace = awaitWhatsAppConnectedWithGrace(
                            client = client,
                            timeoutMs = 90_000L,
                            intervalMs = 1_500L,
                            requiredConnectedCount = 3,
                            probe = false,
                        )
                        if (connectedAfterGrace) {
                            _whatsappQrState.value = WhatsAppQrState.Connected
                            delay(1500)
                            _whatsappQrState.value = WhatsAppQrState.Idle
                        } else {
                            _whatsappQrState.value = WhatsAppQrState.Error(
                                "Login verification timed out after scan (515). WhatsApp session did not finalize."
                            )
                        }
                        client.close()
                        wsClient = null
                        return@launch
                    }

                    val reason = client.getLastCallErrorMessage()
                    val message = if (reason.isNullOrBlank()) {
                        "Login timed out"
                    } else {
                        "Login failed: $reason"
                    }
                    _whatsappQrState.value = WhatsAppQrState.Error(message)
                }

                client.close()
                wsClient = null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _whatsappQrState.value = WhatsAppQrState.Error(e.message ?: "Unknown error")
                wsClient?.close()
                wsClient = null
            } finally {
                finishWhatsAppLoginSession()
            }
        }
    }

    fun startGatewayAndRetryWhatsAppQr() {
        startGateway()
        _whatsappQrState.value = WhatsAppQrState.Loading
        viewModelScope.launch {
            val ready = waitForGatewayRestartReady(timeoutMs = 60_000L)
            if (ready) {
                _whatsappQrState.value = WhatsAppQrState.Idle
                startWhatsAppQr()
            } else {
                _whatsappQrState.value = WhatsAppQrState.Error(
                    "Gateway failed to start. Please try again."
                )
            }
        }
    }

    fun cancelWhatsAppQr() {
        whatsappQrJob?.cancel()
        whatsappQrJob = null
        finishWhatsAppLoginSession()
        _whatsappQrState.value = WhatsAppQrState.Idle
    }

    private fun finishWhatsAppLoginSession() {
        wsClient?.close()
        wsClient = null
        whatsappLoginGuardKeepAliveJob?.cancel()
        whatsappLoginGuardKeepAliveJob = null
        synchronized(whatsappLoginGuardLock) {
            if (isWhatsAppLoginCoordinatorOwner && isWhatsAppLoginGuardActive) {
                GatewayService.stopWhatsAppLoginGuard()
            }
            isWhatsAppLoginGuardActive = false
        }
        if (isWhatsAppLoginCoordinatorOwner) {
            WhatsAppLoginCoordinator.release()
        }
        isWhatsAppLoginCoordinatorOwner = false
    }

    private fun startWhatsAppLoginGuardKeepAlive(appContext: Context) {
        synchronized(whatsappLoginGuardLock) {
            GatewayService.startWhatsAppLoginGuard(
                context = appContext,
                timeoutMs = WHATSAPP_LOGIN_GUARD_TIMEOUT_MS,
            )
            isWhatsAppLoginGuardActive = true
        }

        whatsappLoginGuardKeepAliveJob?.cancel()
        whatsappLoginGuardKeepAliveJob = viewModelScope.launch {
            while (isActive) {
                delay(WHATSAPP_LOGIN_GUARD_REFRESH_INTERVAL_MS)
                if (!isActive) break
                val refreshed = synchronized(whatsappLoginGuardLock) {
                    if (!isWhatsAppLoginCoordinatorOwner || !isWhatsAppLoginGuardActive) {
                        false
                    } else {
                        GatewayService.startWhatsAppLoginGuard(
                            context = appContext,
                            timeoutMs = WHATSAPP_LOGIN_GUARD_TIMEOUT_MS,
                        )
                        true
                    }
                }
                if (!refreshed) break
            }
        }
    }

    fun retryBundleUpdate() {
        if (_bundleActionInProgress.value) return
        _bundleActionInProgress.value = true
        _bundleActionType.value = BundleActionType.RETRY
        _bundleActionMessage.value = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    app.setupManager.updateBundleIfNeededWithPolicy(
                        manualRetry = true,
                        includeOpenClawAssetUpdate = true,
                    )
                }
                _bundleActionMessage.value = when (result.outcome) {
                    BundleUpdateOutcome.UPDATED,
                    BundleUpdateOutcome.SKIPPED_NOT_REQUIRED,
                    -> getApplication<Application>().getString(R.string.dashboard_update_action_done)
                    BundleUpdateOutcome.SKIPPED_MANUAL_RETRY_EXHAUSTED -> getApplication<Application>().getString(R.string.dashboard_update_action_manual_exhausted)
                    BundleUpdateOutcome.SKIPPED_COOLDOWN -> getApplication<Application>().getString(R.string.dashboard_update_action_cooldown)
                    BundleUpdateOutcome.FAILED -> result.errorMessage ?: getApplication<Application>().getString(R.string.dashboard_update_action_failed)
                }
            } finally {
                refreshBundleUpdateFailure()
                _bundleActionInProgress.value = false
                _bundleActionType.value = null
            }
        }
    }

    fun recoverBundleInstall() {
        if (_bundleActionInProgress.value) return
        _bundleActionInProgress.value = true
        _bundleActionType.value = BundleActionType.RECOVERY
        _bundleActionMessage.value = null
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    app.setupManager.runRecoveryInstall()
                }
                _bundleActionMessage.value = when (result.outcome) {
                    BundleUpdateOutcome.UPDATED,
                    BundleUpdateOutcome.SKIPPED_NOT_REQUIRED,
                    -> getApplication<Application>().getString(R.string.dashboard_update_recovery_done)
                    BundleUpdateOutcome.SKIPPED_MANUAL_RETRY_EXHAUSTED -> getApplication<Application>().getString(R.string.dashboard_update_action_manual_exhausted)
                    BundleUpdateOutcome.SKIPPED_COOLDOWN -> getApplication<Application>().getString(R.string.dashboard_update_action_cooldown)
                    BundleUpdateOutcome.FAILED -> result.errorMessage ?: getApplication<Application>().getString(R.string.dashboard_update_recovery_failed)
                }
            } finally {
                refreshBundleUpdateFailure()
                _bundleActionInProgress.value = false
                _bundleActionType.value = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cancelWhatsAppQr()
    }

    private fun updateBatteryInfo() {
        val context: Context = getApplication()
        val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, intentFilter)
        batteryStatus?.let {
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            _batteryLevel.value = if (scale > 0) (level * 100 / scale) else 0

            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            _isCharging.value = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
    }

    private fun updateMemoryInfo() {
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        _memoryUsageMb.value = usedMem
    }

    private suspend fun refreshBundleUpdateFailure() {
        val bundleFailure = withContext(Dispatchers.IO) {
            app.setupManager.getBundleUpdateFailureState()
        }?.takeIf { failure ->
            failure.failCountForCurrentVersion > 0 && failure.lastError?.isNotBlank() == true
        }

        if (bundleFailure != null) {
            _bundleUpdateFailure.value = bundleFailure
            return
        }

        val runtimeFailure = RuntimeRecoverableFailureDetector.detect(
            state = gatewayState.value,
            logs = logLines.value,
        )
        _bundleUpdateFailure.value = RuntimeRecoverableFailureDetector.stabilizeWithPrevious(
            previous = _bundleUpdateFailure.value,
            current = runtimeFailure,
        )
        val guidance = shouldShowRuntimeChangeGuidanceDialog(
            failure = _bundleUpdateFailure.value,
            lastShownError = lastShownRuntimeChangeGuidanceError,
            currentVisibleError = _runtimeChangeGuidanceDialog.value?.errorSignature,
        )
        if (guidance != null) {
            _runtimeChangeGuidanceDialog.value = guidance
            lastShownRuntimeChangeGuidanceError = guidance.errorSignature
        }
    }

    fun consumeRuntimeChangeGuidanceDialog() {
        _runtimeChangeGuidanceDialog.value = null
    }

    private fun resetRuntimeChangeGuidance() {
        lastShownRuntimeChangeGuidanceError = null
        _runtimeChangeGuidanceDialog.value = null
    }
}

sealed class WhatsAppQrState {
    data object Idle : WhatsAppQrState()
    data object Loading : WhatsAppQrState()
    data object Waiting : WhatsAppQrState()
    data class QrReady(
        val qrData: String,
        val isDataUrl: Boolean,
        val attempt: Int,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
    ) : WhatsAppQrState()
    data object Connected : WhatsAppQrState()
    data object GatewayNotRunning : WhatsAppQrState()
    data class Error(val message: String) : WhatsAppQrState()
}

enum class PairingActionType {
    APPROVE,
    DENY,
}

data class PairingActionProgress(
    val action: PairingActionType,
    val request: PairingRequest,
)

enum class BundleActionType {
    RETRY,
    RECOVERY,
}

enum class DashboardErrorSource {
    GATEWAY,
    OPEN_AI_TRANSITION,
}

data class DashboardErrorUiState(
    val message: String,
    val source: DashboardErrorSource,
    val transitionFailureId: Long? = null,
)

data class DashboardGatewayUiState(
    val status: GatewayStatus = GatewayStatus.STOPPED,
    val error: DashboardErrorUiState? = null,
    val dashboardReady: Boolean = false,
    val actionsEnabled: Boolean = true,
)

internal fun resolveDashboardGatewayUiState(
    state: GatewayState,
    transition: OpenAiConnectionTransitionUiState,
): DashboardGatewayUiState {
    val error = when {
        state.errorMessage != null -> DashboardErrorUiState(
            message = state.errorMessage,
            source = DashboardErrorSource.GATEWAY,
        )
        transition.failure != null -> DashboardErrorUiState(
            message = transition.failure.message,
            source = DashboardErrorSource.OPEN_AI_TRANSITION,
            transitionFailureId = transition.failure.failureId,
        )
        else -> null
    }

    return DashboardGatewayUiState(
        status = state.status,
        error = error,
        dashboardReady = state.dashboardReady && !transition.isPending,
        actionsEnabled = !transition.isPending,
    )
}

internal fun acknowledgeDashboardError(
    observedError: DashboardErrorUiState,
    currentVisibleError: DashboardErrorUiState?,
) {
    val failureId = observedError.transitionFailureId ?: return
    if (
        observedError.source == DashboardErrorSource.OPEN_AI_TRANSITION &&
        currentVisibleError == observedError
    ) {
        OpenAiConnectionTransitionState.acknowledgeFailure(failureId)
    }
}

data class RuntimeChangeGuidanceDialogState(
    val errorSignature: String,
)

internal fun shouldShowRuntimeChangeGuidanceDialog(
    failure: BundleUpdateFailureState?,
    lastShownError: String?,
    currentVisibleError: String?,
): RuntimeChangeGuidanceDialogState? {
    val errorSignature = failure?.lastError?.takeIf { it.isNotBlank() } ?: return null
    if (failure.lastFailureType != RuntimeRecoverableFailureDetector.FAILURE_TYPE) return null
    if (errorSignature == lastShownError || errorSignature == currentVisibleError) return null
    return RuntimeChangeGuidanceDialogState(errorSignature = errorSignature)
}

internal fun readGatewayAuthTokenFromConfig(configFile: java.io.File): String? {
    return runCatching {
        if (!configFile.exists()) return null
        val json = org.json.JSONObject(configFile.readText())
        json.optJSONObject("gateway")
            ?.optJSONObject("auth")
            ?.optString("token", "")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}

internal suspend fun resolveDashboardUrl(
    readToken: suspend () -> String?,
    attempts: Int,
    delayMs: Long,
    usesTls: Boolean = false,
): String? {
    repeat(attempts.coerceAtLeast(1)) { index ->
        val token = readToken()
        if (!token.isNullOrBlank()) {
            val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8.toString())
            val scheme = if (usesTls) "https" else "http"
            return "$scheme://127.0.0.1:18789/#token=$encodedToken"
        }
        if (index < attempts - 1) {
            delay(delayMs)
        }
    }
    return null
}
