@file:Suppress("PackageDirectoryMismatch")

package com.coderred.andclaw.ui.screen.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coderred.andclaw.R
import com.coderred.andclaw.AndClawApp
import com.coderred.andclaw.BuildConfig
import com.coderred.andclaw.data.BugReportBundleBuilder
import com.coderred.andclaw.data.BugReportZipArtifact
import com.coderred.andclaw.data.BugReportZipWriter
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.OpenRouterModel
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.data.SelectedModelConfigEntry
import com.coderred.andclaw.data.SessionLogEntry
import com.coderred.andclaw.data.SetupState
import com.coderred.andclaw.data.GlobalDefaultModelOption
import com.coderred.andclaw.data.transfer.DefaultSettingsTransferManager
import com.coderred.andclaw.data.transfer.SettingsTransferExportRequest
import com.coderred.andclaw.data.transfer.SettingsTransferExportResult
import com.coderred.andclaw.data.transfer.SettingsTransferFailureReason
import com.coderred.andclaw.data.transfer.SettingsTransferImportRequest
import com.coderred.andclaw.data.transfer.SettingsTransferImportResult
import com.coderred.andclaw.data.transfer.SettingsTransferManager
import com.coderred.andclaw.data.transfer.TransferExportRequest
import com.coderred.andclaw.data.transfer.TransferGatewayQuiesceController
import com.coderred.andclaw.data.transfer.TransferGatewayRestoreVerification
import com.coderred.andclaw.data.transfer.TransferPreferencesRestoreData
import com.coderred.andclaw.data.transfer.TransferPreferencesRestorer
import com.coderred.andclaw.data.transfer.TransferRestoreRequest
import com.coderred.andclaw.data.transfer.TransferVersionExpectation
import com.coderred.andclaw.data.hasGitHubCopilotEnvAuth
import com.coderred.andclaw.data.hasOpenClawModelsStatusAuth
import com.coderred.andclaw.data.hasOpenClawSecretRef
import com.coderred.andclaw.proot.BundleUpdateOutcome
import com.coderred.andclaw.proot.GatewayWsClient
import com.coderred.andclaw.proot.OpenClawModelCatalogReader
import com.coderred.andclaw.proot.ProcessManager
import com.coderred.andclaw.proot.WhatsAppLoginCoordinator
import com.coderred.andclaw.service.GatewayService
import com.coderred.andclaw.ui.screen.dashboard.WhatsAppQrState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SettingsViewModel(
    application: Application,
    private val transferManager: SettingsTransferManager = DefaultSettingsTransferManager(),
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, DefaultSettingsTransferManager())

    data class DoctorFixResult(
        val success: Boolean,
        val output: String,
    )

    data class RecoveryInstallResult(
        val success: Boolean,
        val output: String,
    )

    data class OpenClawUpdateResult(
        val success: Boolean,
        val output: String,
    )

    data class BugReportPreview(
        val sessionErrorCount: Int = 0,
        val hasGatewayError: Boolean = false,
        val hasProcessError: Boolean = false,
        val gatewayLogCount: Int = 0,
    )

    data class BugReportArtifactInfo(
        val fileName: String,
        val sizeBytes: Long,
    )

    data class BugReportUiState(
        val isVisible: Boolean = false,
        val hasConsent: Boolean = false,
        val isGenerating: Boolean = false,
        val preview: BugReportPreview = BugReportPreview(),
        val artifact: BugReportZipArtifact? = null,
        val artifactInfo: BugReportArtifactInfo? = null,
        val generationErrorMessage: String? = null,
    )

    enum class TransferFailureUiReason {
        WRONG_PASSWORD,
        VERSION_MISMATCH,
        TRANSIENT_RUNTIME,
        UNKNOWN,
    }

    enum class TransferActionPhase {
        IDLE,
        IN_PROGRESS,
        SUCCESS,
        ERROR,
    }

    data class TransferActionUiState(
        val phase: TransferActionPhase = TransferActionPhase.IDLE,
        val message: String? = null,
        val failureReason: TransferFailureUiReason? = null,
        val artifactPath: String? = null,
    )

    data class TransferPasswordUiState(
        val exportPassword: String = "",
        val importPassword: String = "",
    )

    data class TransferOverwriteConfirmationState(
        val isRequired: Boolean = false,
        val pendingArtifactPath: String? = null,
    )

    data class TransferUiState(
        val passwords: TransferPasswordUiState = TransferPasswordUiState(),
        val overwriteConfirmation: TransferOverwriteConfirmationState = TransferOverwriteConfirmationState(),
        val exportAction: TransferActionUiState = TransferActionUiState(),
        val importAction: TransferActionUiState = TransferActionUiState(),
    )

    companion object {
        private const val TAG = "AndClawCodexAuth"
        private const val GITHUB_COPILOT_CLIENT_ID = "Iv1.b507a08c87ecfe98"
        private const val GITHUB_COPILOT_DEVICE_CODE_URL = "https://github.com/login/device/code"
        private const val GITHUB_COPILOT_ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
        private const val WHATSAPP_LOG_TAG = "AndClawWhatsAppLogin"
        private const val OAUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val OAUTH_AUTHORIZE_URL = "https://auth.openai.com/oauth/authorize"
        private const val OAUTH_TOKEN_URL_PRIMARY = "https://auth.openai.com/oauth/token"
        private const val OAUTH_REDIRECT_URI = "http://localhost:1455/auth/callback"
        private const val OAUTH_SCOPE = "openid profile email offline_access"
        private const val OAUTH_JWT_CLAIM_PATH = "https://api.openai.com/auth"
        private const val LOG_UNLOCK_TAP_COUNT = 7
        private const val LOG_UNLOCK_TAP_WINDOW_MS = 2000L
        private const val WHATSAPP_QR_EXPIRES_MS = 120_000L
        private const val WHATSAPP_LOGIN_GUARD_TIMEOUT_MS = 4L * 60L * 1000L
        private const val WHATSAPP_LOGIN_GUARD_REFRESH_INTERVAL_MS = 60_000L
        private const val WHATSAPP_STABLE_CONNECTED_REQUIRED_COUNT = 4
        private const val WHATSAPP_WAIT_TIMEOUT_MS = 120_000L
        private const val WHATSAPP_WAIT_MAX_ATTEMPTS = 2
        private const val WHATSAPP_QR_START_TIMEOUT_MS = 70_000L
        private const val WHATSAPP_QR_FORCE_TIMEOUT_MS = 70_000L
        private const val WHATSAPP_GATEWAY_RESTART_READY_TIMEOUT_MS = 20_000L
        private const val WHATSAPP_GATEWAY_RESTART_RETRY_INTERVAL_MS = 250L
        private const val TRANSFER_EXPORT_DIR = "transfers"
        private const val MIN_TRANSFER_PASSWORD_LENGTH = 4
        private const val TRANSFER_IMPORT_VERIFY_TIMEOUT_MS = 20_000L
        private const val TRANSFER_IMPORT_VERIFY_POLL_MS = 250L

    }
    private val prefs = (application as AndClawApp).preferencesManager
    private val prootManager = (application as AndClawApp).prootManager
    private val processManager = (application as AndClawApp).processManager
    private val setupManager = (application as AndClawApp).setupManager

    val autoStartOnBoot: StateFlow<Boolean> = prefs.autoStartOnBoot
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val setupState: StateFlow<SetupState> = setupManager.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, SetupState())

    val chargeOnlyMode: StateFlow<Boolean> = prefs.chargeOnlyMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val apiProvider: StateFlow<String> = prefs.apiProvider
        .stateIn(viewModelScope, SharingStarted.Eagerly, "openrouter")

    val apiKey: StateFlow<String> = prefs.apiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val openAiCompatibleBaseUrl: StateFlow<String> = prefs.openAiCompatibleBaseUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "https://api.openai.com/v1")

    val openAiCompatibleModelId: StateFlow<String> = prefs.openAiCompatibleModelId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val ollamaBaseUrl: StateFlow<String> = prefs.ollamaBaseUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "http://127.0.0.1:11434")

    val ollamaModelId: StateFlow<String> = prefs.ollamaModelId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val ollamaCloudModelId: StateFlow<String> = prefs.ollamaCloudModelId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val openClawVersion: StateFlow<String> = prefs.openClawVersion
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val selectedModel: StateFlow<String> = prefs.selectedModel
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val selectedModelProvider: StateFlow<String> = prefs.selectedModelProvider
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val currentProviderSelectedModelIds: StateFlow<List<String>> = prefs.currentProviderSelectedModelIds
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val currentProviderPrimaryModelId: StateFlow<String> = prefs.currentProviderPrimaryModelId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val currentProviderGlobalPrimaryModelId: StateFlow<String> = prefs.currentProviderGlobalPrimaryModelId
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val globalDefaultModelOptions: StateFlow<List<GlobalDefaultModelOption>> = prefs.globalDefaultModelOptions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val braveSearchApiKey: StateFlow<String> = prefs.braveSearchApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val memorySearchEnabled: StateFlow<Boolean> = prefs.memorySearchEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val memorySearchProvider: StateFlow<String> = prefs.memorySearchProvider
        .stateIn(viewModelScope, SharingStarted.Eagerly, "auto")
    val memorySearchApiKey: StateFlow<String> = prefs.memorySearchApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val isGatewayActive: Boolean
        get() = processManager.gatewayState.value.status.let {
            it == GatewayStatus.RUNNING || it == GatewayStatus.STARTING || it == GatewayStatus.ERROR
        }

    val isLogSectionUnlocked: StateFlow<Boolean> = prefs.logSectionUnlocked
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val whatsappEnabled: StateFlow<Boolean> = prefs.whatsappEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val telegramEnabled: StateFlow<Boolean> = prefs.telegramEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val telegramBotToken: StateFlow<String> = prefs.telegramBotToken
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val discordEnabled: StateFlow<Boolean> = prefs.discordEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val discordBotToken: StateFlow<String> = prefs.discordBotToken
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val discordGuildAllowlist: StateFlow<String> = prefs.discordGuildAllowlist
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val discordRequireMention: StateFlow<Boolean> = prefs.discordRequireMention
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _availableModels = MutableStateFlow<List<OpenRouterModel>>(emptyList())
    val availableModels: StateFlow<List<OpenRouterModel>> = _availableModels.asStateFlow()

    private val _isLoadingModels = MutableStateFlow(false)
    val isLoadingModels: StateFlow<Boolean> = _isLoadingModels.asStateFlow()

    private val _modelLoadError = MutableStateFlow<String?>(null)
    val modelLoadError: StateFlow<String?> = _modelLoadError.asStateFlow()

    private val _hasLoadedModels = MutableStateFlow(false)
    val hasLoadedModels: StateFlow<Boolean> = _hasLoadedModels.asStateFlow()

    private val _whatsappQrState = MutableStateFlow<WhatsAppQrState>(WhatsAppQrState.Idle)
    val whatsappQrState: StateFlow<WhatsAppQrState> = _whatsappQrState.asStateFlow()
    private val _isWhatsAppLinked = MutableStateFlow(false)
    val isWhatsAppLinked: StateFlow<Boolean> = _isWhatsAppLinked.asStateFlow()
    private val _isChannelDisconnecting = MutableStateFlow(false)
    val isChannelDisconnecting: StateFlow<Boolean> = _isChannelDisconnecting.asStateFlow()
    private val _disconnectingChannelLabel = MutableStateFlow<String?>(null)
    val disconnectingChannelLabel: StateFlow<String?> = _disconnectingChannelLabel.asStateFlow()
    private val _channelDisconnectError = MutableStateFlow<String?>(null)
    val channelDisconnectError: StateFlow<String?> = _channelDisconnectError.asStateFlow()

    private val _isCodexAuthInProgress = MutableStateFlow(false)
    val isCodexAuthInProgress: StateFlow<Boolean> = _isCodexAuthInProgress.asStateFlow()

    private val _isCodexAuthenticated = MutableStateFlow(false)
    val isCodexAuthenticated: StateFlow<Boolean> = _isCodexAuthenticated.asStateFlow()

    private val _codexAuthUrl = MutableStateFlow<String?>(null)
    val codexAuthUrl: StateFlow<String?> = _codexAuthUrl.asStateFlow()

    private val _codexAuthDebugLine = MutableStateFlow<String?>(null)
    val codexAuthDebugLine: StateFlow<String?> = _codexAuthDebugLine.asStateFlow()

    private val _isGitHubCopilotAuthInProgress = MutableStateFlow(false)
    val isGitHubCopilotAuthInProgress: StateFlow<Boolean> = _isGitHubCopilotAuthInProgress.asStateFlow()

    private val _isGitHubCopilotAuthenticated = MutableStateFlow(false)
    val isGitHubCopilotAuthenticated: StateFlow<Boolean> = _isGitHubCopilotAuthenticated.asStateFlow()

    private val _gitHubCopilotAuthUrl = MutableStateFlow<String?>(null)
    val gitHubCopilotAuthUrl: StateFlow<String?> = _gitHubCopilotAuthUrl.asStateFlow()
    private val _gitHubCopilotVerificationUrl = MutableStateFlow<String?>(null)
    val gitHubCopilotVerificationUrl: StateFlow<String?> = _gitHubCopilotVerificationUrl.asStateFlow()

    private val _gitHubCopilotAuthCode = MutableStateFlow<String?>(null)
    val gitHubCopilotAuthCode: StateFlow<String?> = _gitHubCopilotAuthCode.asStateFlow()

    private val _gitHubCopilotAuthDebugLine = MutableStateFlow<String?>(null)
    val gitHubCopilotAuthDebugLine: StateFlow<String?> = _gitHubCopilotAuthDebugLine.asStateFlow()
    private val _gitHubCopilotAuthRestartHintNonce = MutableStateFlow(0L)
    val gitHubCopilotAuthRestartHintNonce: StateFlow<Long> = _gitHubCopilotAuthRestartHintNonce.asStateFlow()
    private val _isDoctorFixRunning = MutableStateFlow(false)
    val isDoctorFixRunning: StateFlow<Boolean> = _isDoctorFixRunning.asStateFlow()
    private val _doctorFixResult = MutableStateFlow<DoctorFixResult?>(null)
    val doctorFixResult: StateFlow<DoctorFixResult?> = _doctorFixResult.asStateFlow()
    private val _isRecoveryInstallRunning = MutableStateFlow(false)
    val isRecoveryInstallRunning: StateFlow<Boolean> = _isRecoveryInstallRunning.asStateFlow()
    private val _recoveryInstallResult = MutableStateFlow<RecoveryInstallResult?>(null)
    val recoveryInstallResult: StateFlow<RecoveryInstallResult?> = _recoveryInstallResult.asStateFlow()
    private val _isOpenClawUpdateRunning = MutableStateFlow(false)
    val isOpenClawUpdateRunning: StateFlow<Boolean> = _isOpenClawUpdateRunning.asStateFlow()
    private val _openClawUpdateResult = MutableStateFlow<OpenClawUpdateResult?>(null)
    val openClawUpdateResult: StateFlow<OpenClawUpdateResult?> = _openClawUpdateResult.asStateFlow()
    private val _isOpenClawUpdateAvailable = MutableStateFlow(false)
    val isOpenClawUpdateAvailable: StateFlow<Boolean> = _isOpenClawUpdateAvailable.asStateFlow()
    private val _installedOpenClawVersion = MutableStateFlow<String?>(null)
    val installedOpenClawVersion: StateFlow<String?> = _installedOpenClawVersion.asStateFlow()
    private val _bundledOpenClawVersion = MutableStateFlow<String?>(null)
    val bundledOpenClawVersion: StateFlow<String?> = _bundledOpenClawVersion.asStateFlow()
    private val _bugReportUiState = MutableStateFlow(BugReportUiState())
    val bugReportUiState: StateFlow<BugReportUiState> = _bugReportUiState.asStateFlow()
    private val _transferUiState = MutableStateFlow(TransferUiState())
    val transferUiState: StateFlow<TransferUiState> = _transferUiState.asStateFlow()
    private val codexAuthRunning = AtomicBoolean(false)
    private val gitHubCopilotAuthRunning = AtomicBoolean(false)
    private var gitHubCopilotAuthJob: Job? = null
    private val oauthServerLock = Any()
    private var oauthServerSocket: ServerSocket? = null

    private var wsClient: GatewayWsClient? = null
    private var whatsappQrJob: Job? = null
    private var whatsappLinkRefreshJob: Job? = null
    private var whatsappLoginGuardKeepAliveJob: Job? = null
    private val whatsappLoginGuardLock = Any()
    private var isWhatsAppLoginCoordinatorOwner: Boolean = false
    private var isWhatsAppLoginGuardActive: Boolean = false
    private var wasGatewayActiveAtWhatsAppLoginStart: Boolean = false
    private var logUnlockTapCount: Int = 0
    private var lastLogUnlockTapAtMs: Long = 0L
    private var pendingTransferImportArtifact: File? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.backfillSelectedModelProviderIfMissing()
            val provider = prefs.apiProvider.first()
            if (provider == "openai-codex") {
                _isCodexAuthenticated.value = detectCodexAuth()
            } else if (provider == "github-copilot") {
                refreshGitHubCopilotAuthStatusInternal()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            refreshWhatsAppLinkStateInternal()
        }
        refreshOpenClawUpdateInfo()
    }


    fun setAutoStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoStartOnBoot(enabled) }
    }

    fun setChargeOnlyMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setChargeOnlyMode(enabled) }
    }

    fun onVersionInfoTapped() {
        if (isLogSectionUnlocked.value) return

        val now = System.currentTimeMillis()
        if (now - lastLogUnlockTapAtMs > LOG_UNLOCK_TAP_WINDOW_MS) {
            logUnlockTapCount = 0
        }
        lastLogUnlockTapAtMs = now
        logUnlockTapCount += 1

        if (logUnlockTapCount >= LOG_UNLOCK_TAP_COUNT) {
            logUnlockTapCount = 0
            viewModelScope.launch {
                prefs.setLogSectionUnlocked(true)
            }
        }
    }

    fun setApiProvider(
        provider: String,
        onApplied: ((appliedProvider: String, changed: Boolean) -> Unit)? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val previousLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            val previousProvider = prefs.apiProvider.first()
            val previousPrimaryModelId = prefs.currentProviderPrimaryModelId.first().trim()
            if (previousProvider == "openai-compatible" && previousPrimaryModelId.isNotBlank()) {
                prefs.setOpenAiCompatibleModelId(previousPrimaryModelId.removePrefix("openai-compatible/"))
            }
            prefs.setApiProvider(provider)
            if (provider != previousProvider) {
                _hasLoadedModels.value = false
            }
            val appliedProvider = prefs.apiProvider.first()
            val appliedLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            val runtimeChanged = hasRuntimeLaunchConfigChanged(
                previous = previousLaunchConfig,
                current = appliedLaunchConfig,
            )
            if (appliedProvider == "openai-codex") {
                refreshCodexAuthStatus()
            } else if (appliedProvider == "github-copilot") {
                refreshGitHubCopilotAuthStatus()
            }
            if (onApplied != null) {
                withContext(Dispatchers.Main) {
                    onApplied(appliedProvider, runtimeChanged)
                }
            }
        }
    }

    fun setApiKey(
        key: String,
        onApplied: ((appliedProvider: String, runtimeChanged: Boolean) -> Unit)? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val previousLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            val provider = prefs.apiProvider.first()
            prefs.setApiKey(key)
            syncApiKeyAuthProfile(provider = provider, apiKey = key)
            val appliedLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            persistLaunchConfigIfRunnable(appliedLaunchConfig)
            val runtimeChanged = hasRuntimeLaunchConfigChanged(
                previous = previousLaunchConfig,
                current = appliedLaunchConfig,
            )
            if (onApplied != null) {
                withContext(Dispatchers.Main) {
                    onApplied(provider, runtimeChanged)
                }
            }
        }
    }

    fun setApiKeyForProvider(
        provider: String,
        key: String,
        onApplied: ((appliedProvider: String, runtimeChanged: Boolean) -> Unit)? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val previousLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            prefs.setApiKeyForProvider(provider = provider, key = key)
            syncApiKeyAuthProfile(provider = provider, apiKey = key)
            val appliedLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            persistLaunchConfigIfRunnable(appliedLaunchConfig)
            val runtimeChanged = hasRuntimeLaunchConfigChanged(
                previous = previousLaunchConfig,
                current = appliedLaunchConfig,
            )
            if (onApplied != null) {
                withContext(Dispatchers.Main) {
                    onApplied(provider, runtimeChanged)
                }
            }
        }
    }

    fun getApiKeyForProvider(provider: String, onLoaded: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val key = prefs.getApiKeyForProvider(provider)
            withContext(Dispatchers.Main) {
                onLoaded(key)
            }
        }
    }

    fun setSelectedModel(
        model: com.coderred.andclaw.data.OpenRouterModel,
        onApplied: ((appliedProvider: String, runtimeChanged: Boolean) -> Unit)? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val previousLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            val provider = prefs.apiProvider.first()
            prefs.setSelectedModels(provider = provider, models = listOf(model), primary = model.id)
            val launchConfig = prefs.getGatewayLaunchConfigSnapshot()
            persistLaunchConfigIfRunnable(launchConfig)
            val appliedProvider = prefs.apiProvider.first()
            val runtimeChanged = hasRuntimeLaunchConfigChanged(
                previous = previousLaunchConfig,
                current = launchConfig,
            )
            if (onApplied != null) {
                withContext(Dispatchers.Main) {
                    onApplied(appliedProvider, runtimeChanged)
                }
            }
        }
    }

    fun applySelectedModels(
        models: List<OpenRouterModel>,
        onApplied: ((appliedProvider: String, runtimeChanged: Boolean) -> Unit)? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _modelLoadError.value = null
            val previousLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            val provider = prefs.apiProvider.first()
            val selectedModelProvider = prefs.selectedModelProvider.first()
            val globalPrimaryModelId = prefs.selectedModel.first()
            val compatPrimaryModelId = prefs.openAiCompatibleModelId.first()
            val ollamaPrimaryModelId = if (provider == "ollama-cloud") {
                prefs.ollamaCloudModelId.first()
            } else {
                prefs.ollamaModelId.first()
            }
            val currentSelectedModelIds = prefs.currentProviderSelectedModelIds.first()
            if (models.isEmpty()) {
                prefs.clearSelectedModels(provider)
            } else {
                val requestedPrimary = resolveSelectionChangePrimaryDirective(
                    provider = provider,
                    appliedModelIds = models.map { it.id },
                    currentSelectedModelIds = currentSelectedModelIds,
                    globalPrimaryModelId = globalPrimaryModelId,
                    globalPrimaryProvider = selectedModelProvider,
                    openAiCompatibleModelId = compatPrimaryModelId,
                    ollamaModelId = ollamaPrimaryModelId,
                )
                prefs.setSelectedModels(
                    provider = provider,
                    models = models,
                    primary = requestedPrimary,
                )
            }
            val launchConfig = prefs.getGatewayLaunchConfigSnapshot()
            persistLaunchConfigIfRunnable(launchConfig)
            val appliedProvider = prefs.apiProvider.first()
            val runtimeChanged = hasRuntimeLaunchConfigChanged(
                previous = previousLaunchConfig,
                current = launchConfig,
            )
            if (onApplied != null) {
                withContext(Dispatchers.Main) {
                    onApplied(appliedProvider, runtimeChanged)
                }
            }
        }
    }

    fun setGlobalDefaultModel(
        provider: String,
        modelId: String,
        onApplied: ((runtimeChanged: Boolean) -> Unit)? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val previousLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            // Ensure the default model is in the provider's selected models list.
            val bareModelId = PreferencesManager.canonicalizeModelId(provider, modelId)
            if (bareModelId.isNotBlank()) {
                val currentSelectedIds = prefs.getSelectedModelIdsForProvider(provider)
                if (!currentSelectedIds.contains(bareModelId)) {
                    val updatedIds = currentSelectedIds + bareModelId
                    prefs.setSelectedModelIdsWithoutActivatingProvider(
                        provider = provider,
                        modelIds = updatedIds,
                        primary = null,
                    )
                }
            }
            prefs.setGlobalPrimaryModel(provider = provider, modelId = modelId)
            val appliedLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            persistLaunchConfigIfRunnable(appliedLaunchConfig)
            val runtimeChanged = hasRuntimeLaunchConfigChanged(
                previous = previousLaunchConfig,
                current = appliedLaunchConfig,
            )
            if (onApplied != null) {
                withContext(Dispatchers.Main) {
                    onApplied(runtimeChanged)
                }
            }
        }
    }

    fun saveOpenAiCompatibleConfig(
        apiKey: String,
        baseUrl: String,
        modelId: String,
        activateProvider: Boolean = true,
        onApplied: ((runtimeChanged: Boolean) -> Unit)? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val previousLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            prefs.setOpenAiCompatibleBaseUrl(baseUrl)
            if (activateProvider) {
                prefs.setApiProvider("openai-compatible")
            }
            val activeProfileId = prefs.activeOpenAiCompatibleProfileId.first()
            val profiles = prefs.openAiCompatibleProfiles.first()
            val activeProfile = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.firstOrNull()
            val currentProvider = prefs.apiProvider.first()
            val existingSelectedIdsSource = if (activateProvider || currentProvider == "openai-compatible") {
                prefs.currentProviderSelectedModelIds.first()
                    .ifEmpty { activeProfile?.selectedModels.orEmpty() }
            } else {
                activeProfile?.selectedModels.orEmpty()
            }
            val existingSelectedIds = existingSelectedIdsSource
                .map { it.trim().removePrefix("openai-compatible/") }
                .filter { it.isNotBlank() }
                .distinct()
            val existingPrimaryModelId = activeProfile?.primaryModel
                .orEmpty()
                .trim()
                .removePrefix("openai-compatible/")
                .takeIf { it.isNotBlank() && existingSelectedIds.contains(it) }
                ?: prefs.openAiCompatibleModelId.first()
                    .trim()
                    .removePrefix("openai-compatible/")
                    .takeIf { it.isNotBlank() && existingSelectedIds.contains(it) }
                    .orEmpty()
            val requestedModelId = modelId
                .trim()
                .removePrefix("openai-compatible/")
            val selectedModelIds = if (requestedModelId.isNotBlank()) {
                buildList {
                    add(requestedModelId)
                    addAll(existingSelectedIds.filterNot { it == requestedModelId })
                }.distinct()
            } else {
                existingSelectedIds.distinct()
            }
            val primaryModelId = when {
                requestedModelId.isNotBlank() -> requestedModelId
                activateProvider -> ""
                else -> existingPrimaryModelId
            }
            val compatMetadataById = prefs.selectedModelMetadataByProvider.first()["openai-compatible"].orEmpty()
            val selectedModelsMetadata = selectedModelIds
                .map { selectedId ->
                    compatMetadataById[selectedId]?.toOpenRouterModel()
                        ?: resolveOpenAiCompatibleModelMetadata(selectedId)
                }
            if (activateProvider) {
                prefs.setSelectedModels(
                    provider = "openai-compatible",
                    models = selectedModelsMetadata,
                    primary = primaryModelId,
                )
            } else {
                prefs.setSelectedModelsWithoutActivatingProvider(
                    provider = "openai-compatible",
                    models = selectedModelsMetadata,
                    primary = primaryModelId,
                )
            }
            prefs.setOpenAiCompatibleModelId(primaryModelId)
            val profileId = activeProfile?.id ?: activeProfileId.ifBlank { "default" }
            val profileName = activeProfile?.name ?: if (profileId == "default") "Default" else profileId
            prefs.upsertOpenAiCompatibleProfile(
                profile = com.coderred.andclaw.data.OpenAiCompatibleProfile(
                    id = profileId,
                    name = profileName,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    selectedModels = selectedModelIds,
                    primaryModel = primaryModelId.ifBlank { null },
                ),
                activate = true,
            )
            prefs.setApiKeyForProvider(provider = "openai-compatible", key = apiKey)
            val appliedLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            persistLaunchConfigIfRunnable(appliedLaunchConfig)
            val runtimeChanged = hasRuntimeLaunchConfigChanged(
                previous = previousLaunchConfig,
                current = appliedLaunchConfig,
            )
            syncApiKeyAuthProfile(provider = "openai-compatible", apiKey = apiKey)
            if (onApplied != null) {
                withContext(Dispatchers.Main) {
                    onApplied(runtimeChanged)
                }
            }
        }
    }

    fun saveOllamaConfig(
        apiKey: String,
        baseUrl: String,
        activateProvider: Boolean = true,
        onApplied: ((runtimeChanged: Boolean) -> Unit)? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val previousLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            prefs.setOllamaBaseUrl(baseUrl)
            if (activateProvider) {
                prefs.setApiProvider("ollama")
            }
            val currentProvider = prefs.apiProvider.first()
            val existingSelectedIdsSource = if (activateProvider || currentProvider == "ollama") {
                prefs.currentProviderSelectedModelIds.first()
            } else {
                prefs.getSelectedModelIdsForProvider("ollama")
            }
            val selectedModelIds = existingSelectedIdsSource
                .map { it.trim().removePrefix("ollama/") }
                .filter { it.isNotBlank() }
                .distinct()
                .let { currentIds ->
                    if (currentIds.isNotEmpty()) {
                        currentIds
                    } else {
                        val selectedProvider = prefs.selectedModelProvider.first().trim().lowercase(Locale.US)
                        val selectedModel = prefs.selectedModel.first().trim().removePrefix("ollama/")
                        if (selectedProvider == "ollama" && selectedModel.isNotBlank()) {
                            listOf(selectedModel)
                        } else {
                            currentIds
                        }
                    }
                }
            val existingPrimaryModelId = prefs.ollamaModelId.first()
                .trim()
                .removePrefix("ollama/")
                .takeIf { it.isNotBlank() && selectedModelIds.contains(it) }
                .orEmpty()
            val effectivePrimaryModelId = existingPrimaryModelId.ifBlank {
                selectedModelIds.firstOrNull().orEmpty()
            }
            val ollamaMetadataById = prefs.selectedModelMetadataByProvider.first()["ollama"].orEmpty()
            val selectedModelsMetadata = selectedModelIds.map { selectedId ->
                ollamaMetadataById[selectedId]?.toOpenRouterModel()
                    ?: resolveOllamaModelMetadata(selectedId)
            }
            if (activateProvider) {
                prefs.setSelectedModels(
                    provider = "ollama",
                    models = selectedModelsMetadata,
                    primary = effectivePrimaryModelId,
                )
            } else {
                prefs.setSelectedModelsWithoutActivatingProvider(
                    provider = "ollama",
                    models = selectedModelsMetadata,
                    primary = effectivePrimaryModelId,
                )
            }
            prefs.setOllamaModelId(effectivePrimaryModelId)
            val normalizedApiKey = apiKey.ifBlank { "ollama-local" }
            prefs.setApiKeyForProvider(provider = "ollama", key = normalizedApiKey)
            syncApiKeyAuthProfile(provider = "ollama", apiKey = normalizedApiKey)
            val appliedLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            persistLaunchConfigIfRunnable(appliedLaunchConfig)
            val runtimeChanged = hasRuntimeLaunchConfigChanged(
                previous = previousLaunchConfig,
                current = appliedLaunchConfig,
            )
            if (onApplied != null) {
                withContext(Dispatchers.Main) {
                    onApplied(runtimeChanged)
                }
            }
        }
    }

    fun setGptSubscription(
        useCodexOAuth: Boolean,
        onApplied: ((appliedProvider: String, changed: Boolean) -> Unit)? = null,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val provider = if (useCodexOAuth) "openai-codex" else "openai"
            val previousLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()

            prefs.setApiProvider(provider)
            promoteGptSubscriptionModelSelection(provider)
            val appliedProvider = prefs.apiProvider.first()
            val appliedLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
            persistLaunchConfigIfRunnable(appliedLaunchConfig)
            val runtimeChanged = hasRuntimeLaunchConfigChanged(
                previous = previousLaunchConfig,
                current = appliedLaunchConfig,
            )

            if (useCodexOAuth) {
                _isCodexAuthenticated.value = detectCodexAuth()
            } else {
                val openAiApiKey = prefs.apiKey.first()
                syncApiKeyAuthProfile(provider = "openai", apiKey = openAiApiKey)
            }
            if (onApplied != null) {
                withContext(Dispatchers.Main) {
                    onApplied(appliedProvider, runtimeChanged)
                }
            }
        }
    }

    private fun hasRuntimeLaunchConfigChanged(
        previous: com.coderred.andclaw.data.GatewayLaunchConfigSnapshot,
        current: com.coderred.andclaw.data.GatewayLaunchConfigSnapshot,
    ): Boolean {
        return previous.apiProvider != current.apiProvider ||
            previous.apiKey != current.apiKey ||
            previous.selectedModel != current.selectedModel ||
            previous.primaryModelId != current.primaryModelId ||
            previous.openAiCompatibleBaseUrl != current.openAiCompatibleBaseUrl ||
            previous.ollamaBaseUrl != current.ollamaBaseUrl ||
            previous.selectedModelEntries != current.selectedModelEntries
    }

    private suspend fun promoteGptSubscriptionModelSelection(provider: String) {
        if (provider != "openai" && provider != "openai-codex") return

        val currentSelectedModelIds = prefs.currentProviderSelectedModelIds.first()
        val savedPrimary = prefs.getEffectivePrimary(provider)?.takeIf { it.isNotBlank() }
        val (selectedModelIds, targetPrimary, metadataById) = when (provider) {
            "openai-codex" -> {
                val installedCodexModels = resolveCodexModelsFromInstalledBundle()
                val installedCodexModelsByNormalizedId = installedCodexModels.associateBy {
                    normalizeCodexModelIdForComparison(it.id)
                }
                val targetPrimary = savedPrimary
                    ?: resolvePreferredCodexPrimaryModelFromInstalledBundle().removePrefix("openai-codex/")
                val selectedModelIds = currentSelectedModelIds
                    .ifEmpty { listOf(targetPrimary) }
                    .let { modelIds ->
                        if (modelIds.contains(targetPrimary)) {
                            modelIds
                        } else {
                            listOf(targetPrimary) + modelIds
                        }
                    }
                    .distinct()
                val metadataById = selectedModelIds.mapNotNull { modelId ->
                    val installedModel = installedCodexModelsByNormalizedId[normalizeCodexModelIdForComparison(modelId)]
                        ?: return@mapNotNull null
                    modelId to SelectedModelConfigEntry(
                        id = modelId,
                        supportsReasoning = installedModel.supportsReasoning,
                        supportsImages = installedModel.supportsImages,
                        contextLength = installedModel.contextLength,
                        maxOutputTokens = installedModel.maxOutputTokens,
                    )
                }.toMap()
                Triple(selectedModelIds, targetPrimary, metadataById)
            }

            else -> {
                val targetPrimary = savedPrimary
                    ?: defaultBuiltInModels(provider)
                        .firstOrNull()
                    ?.id
                    ?: return
                val selectedModelIds = currentSelectedModelIds
                    .ifEmpty { listOf(targetPrimary) }
                    .let { modelIds ->
                        if (modelIds.contains(targetPrimary)) {
                            modelIds
                        } else {
                            listOf(targetPrimary) + modelIds
                        }
                    }
                    .distinct()
                Triple(selectedModelIds, targetPrimary, emptyMap())
            }
        }

        prefs.setSelectedModelIds(
            provider = provider,
            modelIds = selectedModelIds,
            primary = targetPrimary,
            metadataById = metadataById,
        )
    }

    private fun normalizeCodexModelIdForComparison(modelId: String): String {
        return modelId.trim()
            .removePrefix("openai-codex/")
            .removePrefix("openai/")
    }

    private fun persistLaunchConfigIfRunnable(
        launchConfig: com.coderred.andclaw.data.GatewayLaunchConfigSnapshot,
    ) {
        if (launchConfig.selectedModelEntries.isEmpty()) return
        processManager.ensureOpenClawConfig(
            apiProvider = launchConfig.apiProvider,
            apiKey = launchConfig.apiKey,
            selectedModel = launchConfig.selectedModel,
            selectedModels = launchConfig.selectedModelEntries.map { it.toProcessEntry() },
            primaryModelId = launchConfig.primaryModelId,
            openAiCompatibleBaseUrl = launchConfig.openAiCompatibleBaseUrl,
            ollamaBaseUrl = launchConfig.ollamaBaseUrl,
            modelReasoning = launchConfig.modelReasoning,
            modelImages = launchConfig.modelImages,
            modelContext = launchConfig.modelContext,
            modelMaxOutput = launchConfig.modelMaxOutput,
        )
    }

    fun shouldShowRestartPromptForProvider(provider: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val status = processManager.gatewayState.value.status
            val isGatewayActive = status == GatewayStatus.RUNNING ||
                status == GatewayStatus.STARTING ||
                status == GatewayStatus.ERROR

            val shouldShow = when {
                !isGatewayActive -> false
                provider == "openai-compatible" -> true
                provider == "ollama" -> true
                provider == "openai-codex" -> detectCodexAuth()
                provider == "github-copilot" -> detectGitHubCopilotAuth()
                else -> prefs.hasApiKeyForProvider(provider)
            }
            withContext(Dispatchers.Main) {
                onResult(shouldShow)
            }
        }
    }

    fun shouldShowRestartPromptForRuntimeChange(
        runtimeChanged: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val status = processManager.gatewayState.value.status
            val isGatewayActive = status == GatewayStatus.RUNNING ||
                status == GatewayStatus.STARTING ||
                status == GatewayStatus.ERROR
            val launchConfig = if (runtimeChanged && isGatewayActive) {
                prefs.getGatewayLaunchConfigSnapshot()
            } else {
                null
            }
            val shouldPromptForLaunchChange = shouldPromptForRuntimeLaunchConfigChange(
                launchConfig = launchConfig,
                hasCodexAuth = detectCodexAuth(),
            )
            withContext(Dispatchers.Main) {
                onResult(runtimeChanged && isGatewayActive && shouldPromptForLaunchChange)
            }
        }
    }

    fun setBraveSearchApiKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setBraveSearchApiKey(key)
            restartGatewayIfRunning(source = "settings:brave_search_key_changed")
        }
    }

    fun setMemorySearchEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setMemorySearchEnabled(enabled)
            applyMemorySearchConfigAndRestart(forceRestart = true)
        }
    }

    fun setMemorySearchProvider(provider: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setMemorySearchProvider(provider)
            applyMemorySearchConfigAndRestart(forceRestart = true)
        }
    }

    fun setMemorySearchApiKey(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setMemorySearchApiKey(key)
            applyMemorySearchConfigAndRestart(forceRestart = false)
        }
    }

    fun runOpenClawDoctorFix() {
        if (_isDoctorFixRunning.value || _isRecoveryInstallRunning.value || _isOpenClawUpdateRunning.value) return
        _isDoctorFixRunning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val launchConfig = prefs.getGatewayLaunchConfigSnapshot()
                val provider = launchConfig.apiProvider
                val apiKey = launchConfig.apiKey
                val braveApiKey = prefs.braveSearchApiKey.first()
                val memorySearchApiKey = prefs.memorySearchApiKey.first()
                val channelConfig = prefs.channelConfig.first()

                val doctorEnv = buildMap {
                    fun resolved(value: String): String = value.ifBlank { "__andclaw_env_placeholder__" }

                    put("OPENROUTER_API_KEY", "__andclaw_env_placeholder__")
                    put("OPENAI_API_KEY", "__andclaw_env_placeholder__")
                    put("OPENAI_COMPAT_API_KEY", "__andclaw_env_placeholder__")
                    put("ANTHROPIC_API_KEY", "__andclaw_env_placeholder__")
                    put("GOOGLE_API_KEY", "__andclaw_env_placeholder__")
                    put("GEMINI_API_KEY", "__andclaw_env_placeholder__")
                    put("BRAVE_API_KEY", resolved(braveApiKey))
                    put("BRAVE_SEARCH_API_KEY", resolved(braveApiKey))
                    put("MEMORY_SEARCH_API_KEY", resolved(memorySearchApiKey))
                    put("TELEGRAM_BOT_TOKEN", resolved(channelConfig.telegramBotToken))
                    put("DISCORD_BOT_TOKEN", resolved(channelConfig.discordBotToken))

                    if (apiKey.isNotBlank()) {
                        when (provider) {
                            "anthropic" -> put("ANTHROPIC_API_KEY", apiKey)
                            "openai" -> put("OPENAI_API_KEY", apiKey)
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
                            "ollama", "ollama-cloud" -> put("OLLAMA_API_KEY", apiKey)
                            "openrouter" -> put("OPENROUTER_API_KEY", apiKey)
                            "google" -> {
                                put("GOOGLE_API_KEY", apiKey)
                                put("GEMINI_API_KEY", apiKey)
                            }
                        }
                    }
                }

                val command = "export NODE_OPTIONS='--require /root/.openclaw-patch.js' && " +
                    "openclaw doctor --fix 2>&1"
                val result = prootManager.executeWithResult(
                    command = command,
                    timeoutMs = 300_000,
                    extraEnv = doctorEnv,
                )
                _doctorFixResult.value = when {
                    result == null -> DoctorFixResult(
                        success = false,
                        output = "Failed to execute command.",
                    )
                    result.timedOut -> DoctorFixResult(
                        success = false,
                        output = "Command timed out after 300 seconds.\n\n${result.output}",
                    )
                    else -> DoctorFixResult(
                        success = result.exitCode == 0,
                        output = result.output.ifBlank { "No output." },
                    )
                }
            } finally {
                _isDoctorFixRunning.value = false
            }
        }
    }

    fun consumeDoctorFixResult() {
        _doctorFixResult.value = null
    }

    fun runRecoveryInstall() {
        if (_isRecoveryInstallRunning.value || _isDoctorFixRunning.value || _isOpenClawUpdateRunning.value) return
        _isRecoveryInstallRunning.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = runCatching { setupManager.runRecoveryInstall() }.getOrNull()
                _recoveryInstallResult.value = when {
                    result == null -> RecoveryInstallResult(
                        success = false,
                        output = getApplication<Application>().getString(R.string.dashboard_update_recovery_failed),
                    )
                    result.outcome == BundleUpdateOutcome.UPDATED ||
                        result.outcome == BundleUpdateOutcome.SKIPPED_NOT_REQUIRED -> RecoveryInstallResult(
                        success = true,
                        output = getApplication<Application>().getString(R.string.dashboard_update_recovery_done),
                    )
                    result.outcome == BundleUpdateOutcome.SKIPPED_COOLDOWN -> RecoveryInstallResult(
                        success = false,
                        output = getApplication<Application>().getString(R.string.dashboard_update_action_cooldown),
                    )
                    result.outcome == BundleUpdateOutcome.SKIPPED_MANUAL_RETRY_EXHAUSTED -> RecoveryInstallResult(
                        success = false,
                        output = getApplication<Application>().getString(R.string.dashboard_update_action_manual_exhausted),
                    )
                    else -> RecoveryInstallResult(
                        success = false,
                        output = result.errorMessage
                            ?: getApplication<Application>().getString(R.string.dashboard_update_recovery_failed),
                    )
                }
            } finally {
                _isRecoveryInstallRunning.value = false
                refreshOpenClawUpdateInfo()
            }
        }
    }

    fun refreshOpenClawUpdateInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { setupManager.getOpenClawUpdateInfo() }
                .onSuccess { info ->
                    _installedOpenClawVersion.value = info.installedVersion
                    _bundledOpenClawVersion.value = info.bundledVersion
                    _isOpenClawUpdateAvailable.value = info.updateAvailable
                }
                .onFailure {
                    _isOpenClawUpdateAvailable.value = false
                }
        }
    }

    fun runOpenClawUpdate() {
        if (_isOpenClawUpdateRunning.value || _isRecoveryInstallRunning.value || _isDoctorFixRunning.value) return
        _isOpenClawUpdateRunning.value = true
        _openClawUpdateResult.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val wasGatewayActive = processManager.gatewayState.value.status.let { status ->
                status == GatewayStatus.RUNNING || status == GatewayStatus.STARTING
            }
            try {
                if (wasGatewayActive) {
                    GatewayService.stop(context, source = "settings:openclaw_update_stop")
                    val stopped = waitForGatewayStopped()
                    if (!stopped) {
                        val status = processManager.gatewayState.value.status
                        throw IllegalStateException("Gateway stop timed out (status=$status). OpenClaw update aborted.")
                    }
                }
                val result = setupManager.runOpenClawManualSync()
                _openClawUpdateResult.value = OpenClawUpdateResult(
                    success = true,
                    output = if (result.fullReinstall) {
                        context.getString(R.string.dashboard_update_action_done)
                    } else {
                        context.getString(
                            R.string.settings_openclaw_update_result_incremental,
                            result.copiedCount,
                            result.deletedCount,
                            result.skippedCount,
                        )
                    },
                )
            } catch (error: Exception) {
                _openClawUpdateResult.value = OpenClawUpdateResult(
                    success = false,
                    output = error.message
                        ?: getApplication<Application>().getString(R.string.dashboard_update_action_failed),
                )
            } finally {
                restoreGatewayIfNeeded(
                    context = context,
                    shouldRestore = wasGatewayActive,
                )
                _isOpenClawUpdateRunning.value = false
                refreshOpenClawUpdateInfo()
            }
        }
    }

    private suspend fun waitForGatewayStopped(
        timeoutMs: Long = 10_000L,
        allowErrorAsStopped: Boolean = true,
    ): Boolean {
        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            val status = processManager.gatewayState.value.status
            if (status == GatewayStatus.STOPPED || (allowErrorAsStopped && status == GatewayStatus.ERROR)) {
                return true
            }
            delay(250)
        }
        return false
    }

    private suspend fun restoreGatewayIfNeeded(
        context: Context,
        shouldRestore: Boolean,
        timeoutMs: Long = 10_000L,
    ) {
        if (!shouldRestore) return

        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            when (processManager.gatewayState.value.status) {
                GatewayStatus.RUNNING,
                GatewayStatus.STARTING,
                -> return
                GatewayStatus.STOPPED,
                GatewayStatus.ERROR,
                -> {
                    GatewayService.start(context, source = "settings:openclaw_update_restore")
                    return
                }
                GatewayStatus.STOPPING -> delay(250)
            }
        }
        GatewayService.start(context, source = "settings:openclaw_update_restore")
    }

    fun consumeOpenClawUpdateResult() {
        _openClawUpdateResult.value = null
    }

    fun consumeRecoveryInstallResult() {
        _recoveryInstallResult.value = null
    }

    fun setTransferExportPassword(password: String) {
        _transferUiState.value = _transferUiState.value.copy(
            passwords = _transferUiState.value.passwords.copy(exportPassword = password),
        )
    }

    fun setTransferImportPassword(password: String) {
        _transferUiState.value = _transferUiState.value.copy(
            passwords = _transferUiState.value.passwords.copy(importPassword = password),
        )
    }

    fun clearTransferExportActionState() {
        _transferUiState.value = _transferUiState.value.copy(
            passwords = _transferUiState.value.passwords.copy(exportPassword = ""),
            exportAction = TransferActionUiState(),
        )
    }

    fun clearTransferImportActionState() {
        _transferUiState.value = _transferUiState.value.copy(
            passwords = _transferUiState.value.passwords.copy(importPassword = ""),
            importAction = TransferActionUiState(),
        )
    }

    fun clearTransferExportPassword() {
        _transferUiState.value = _transferUiState.value.copy(
            passwords = _transferUiState.value.passwords.copy(exportPassword = ""),
        )
    }

    fun clearTransferImportPassword() {
        _transferUiState.value = _transferUiState.value.copy(
            passwords = _transferUiState.value.passwords.copy(importPassword = ""),
        )
    }

    fun startTransferExport() {
        val currentState = _transferUiState.value
        if (currentState.exportAction.phase == TransferActionPhase.IN_PROGRESS) return
        if (isTransferBlockedByMaintenance()) return

        val password = currentState.passwords.exportPassword
        if (password.isBlank()) {
            _transferUiState.value = currentState.copy(
                exportAction = TransferActionUiState(
                    phase = TransferActionPhase.ERROR,
                    message = appString(R.string.settings_transfer_error_export_password_required),
                    failureReason = TransferFailureUiReason.UNKNOWN,
                ),
            )
            return
        }
        if (password.length < MIN_TRANSFER_PASSWORD_LENGTH) {
            _transferUiState.value = currentState.copy(
                exportAction = TransferActionUiState(
                    phase = TransferActionPhase.ERROR,
                    message = getApplication<Application>().getString(
                        R.string.settings_transfer_error_password_too_short,
                        MIN_TRANSFER_PASSWORD_LENGTH,
                    ),
                    failureReason = TransferFailureUiReason.UNKNOWN,
                ),
            )
            return
        }

        _transferUiState.value = currentState.copy(
            exportAction = TransferActionUiState(
                phase = TransferActionPhase.IN_PROGRESS,
                message = appString(R.string.settings_transfer_progress_export),
            ),
        )

        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val outputDir = app.filesDir.resolve(TRANSFER_EXPORT_DIR)
            outputDir.listFiles()?.forEach { it.delete() }
            val request = TransferExportRequest(
                outputDir = outputDir,
                rootfsDir = prootManager.rootfsDir ?: throw IllegalStateException("rootfsDir is not available"),
                approvedPreferencesSnapshot = snapshotTransferPreferences(),
                applicationId = app.packageName,
                versionCode = resolveCurrentVersionCode(app),
                versionName = BuildConfig.VERSION_NAME,
                createdAtEpochMs = System.currentTimeMillis(),
                password = password.toCharArray(),
            )
            val result = transferManager.export(SettingsTransferExportRequest(request = request))
            withContext(Dispatchers.Main) {
                _transferUiState.value = when (result) {
                    is SettingsTransferExportResult.Success -> {
                        _transferUiState.value.copy(
                            passwords = _transferUiState.value.passwords.copy(exportPassword = ""),
                            exportAction = TransferActionUiState(
                                phase = TransferActionPhase.SUCCESS,
                                message = appString(R.string.settings_transfer_export_success),
                                artifactPath = result.artifact.file.absolutePath,
                            ),
                        )
                    }

                    is SettingsTransferExportResult.Error -> {
                        _transferUiState.value.copy(
                            passwords = _transferUiState.value.passwords.copy(exportPassword = ""),
                            exportAction = TransferActionUiState(
                                phase = TransferActionPhase.ERROR,
                                message = exportFailureMessage(result),
                                failureReason = result.reason.toTransferFailureUiReason(),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun requestTransferImport(artifactFile: File) {
        val currentState = _transferUiState.value
        if (currentState.importAction.phase == TransferActionPhase.IN_PROGRESS) return
        if (isTransferBlockedByMaintenance()) return
        pendingTransferImportArtifact = artifactFile
        _transferUiState.value = currentState.copy(
            overwriteConfirmation = TransferOverwriteConfirmationState(
                isRequired = true,
                pendingArtifactPath = artifactFile.absolutePath,
            ),
            importAction = TransferActionUiState(),
        )
    }

    fun cancelTransferImportOverwriteConfirmation() {
        pendingTransferImportArtifact = null
        _transferUiState.value = _transferUiState.value.copy(
            passwords = _transferUiState.value.passwords.copy(importPassword = ""),
            overwriteConfirmation = TransferOverwriteConfirmationState(),
        )
    }

    fun confirmTransferImportOverwrite() {
        val currentState = _transferUiState.value
        if (!currentState.overwriteConfirmation.isRequired) return
        if (currentState.importAction.phase == TransferActionPhase.IN_PROGRESS) return
        if (isTransferBlockedByMaintenance()) return

        val artifactFile = pendingTransferImportArtifact
            ?: currentState.overwriteConfirmation.pendingArtifactPath?.let(::File)
            ?: return
        // Import does not enforce MIN_TRANSFER_PASSWORD_LENGTH — the user must be able
        // to enter any password that was used during export (including from older versions
        // that had no minimum length requirement).
        val importPassword = currentState.passwords.importPassword
        if (importPassword.isBlank()) {
            _transferUiState.value = currentState.copy(
                importAction = TransferActionUiState(
                    phase = TransferActionPhase.ERROR,
                    message = appString(R.string.settings_transfer_error_import_password_required),
                    failureReason = TransferFailureUiReason.UNKNOWN,
                ),
            )
            return
        }

        _transferUiState.value = currentState.copy(
            overwriteConfirmation = TransferOverwriteConfirmationState(),
            importAction = TransferActionUiState(
                phase = TransferActionPhase.IN_PROGRESS,
                message = appString(R.string.settings_transfer_progress_import),
            ),
        )

        pendingTransferImportArtifact = null

        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val rootfsDir = prootManager.rootfsDir ?: app.filesDir
            val importRequest = TransferRestoreRequest(
                artifactFile = artifactFile,
                password = importPassword.toCharArray(),
                expectedVersion = TransferVersionExpectation(
                    applicationId = app.packageName,
                    versionCode = resolveCurrentVersionCode(app),
                    versionName = BuildConfig.VERSION_NAME,
                ),
                rootfsDir = rootfsDir,
                preferencesRestorer = createTransferPreferencesRestorer(),
                gatewayController = createTransferGatewayController(app),
            )
            val result = transferManager.import(SettingsTransferImportRequest(request = importRequest))
            withContext(Dispatchers.Main) {
                _transferUiState.value = when (result) {
                    is SettingsTransferImportResult.Success -> {
                        refreshCodexAuthStatus()
                        refreshGitHubCopilotAuthStatus()
                        _transferUiState.value.copy(
                            passwords = _transferUiState.value.passwords.copy(importPassword = ""),
                            importAction = TransferActionUiState(
                                phase = TransferActionPhase.SUCCESS,
                                message = appString(R.string.settings_transfer_import_success),
                            ),
                        )
                    }

                    is SettingsTransferImportResult.Error -> {
                        if (result.reason == SettingsTransferFailureReason.TRANSIENT_RUNTIME) {
                            refreshCodexAuthStatus()
                            refreshGitHubCopilotAuthStatus()
                            _transferUiState.value.copy(
                                passwords = _transferUiState.value.passwords.copy(importPassword = ""),
                                importAction = TransferActionUiState(
                                    phase = TransferActionPhase.SUCCESS,
                                    message = appString(R.string.settings_transfer_import_success),
                                ),
                            )
                        } else {
                            _transferUiState.value.copy(
                                passwords = _transferUiState.value.passwords.copy(importPassword = ""),
                                importAction = TransferActionUiState(
                                    phase = TransferActionPhase.ERROR,
                                    message = importFailureMessage(result),
                                    failureReason = result.reason.toTransferFailureUiReason(),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun snapshotTransferPreferences(): Map<String, String> {
        val fullSnapshot = prefs.getTransferCandidatePreferencesSnapshot()
        return fullSnapshot.filterKeys { key -> PreferencesManager.TRANSFER_EXPORTABLE_KEYS.contains(key) }
    }

    private fun createTransferPreferencesRestorer(): TransferPreferencesRestorer {
        return object : TransferPreferencesRestorer {
            override suspend fun snapshotCurrentState(): TransferPreferencesRestoreData {
                return TransferPreferencesRestoreData(values = snapshotTransferPreferences())
            }

            override suspend fun restore(restoreData: TransferPreferencesRestoreData) {
                prefs.applyTransferCandidatePreferencesSnapshot(restoreData.values)
            }
        }
    }

    private fun createTransferGatewayController(context: Context): TransferGatewayQuiesceController {
        return object : TransferGatewayQuiesceController {
            override suspend fun quiesceForRestore(): Boolean {
                val status = processManager.gatewayState.value.status
                return when (status) {
                    GatewayStatus.RUNNING,
                    GatewayStatus.STARTING -> {
                        GatewayService.stop(context, source = "settings:transfer_import_quiesce")
                    val stopped = waitForGatewayStopped(allowErrorAsStopped = true)
                        if (!stopped) {
                            val currentStatus = processManager.gatewayState.value.status
                            throw IllegalStateException(
                                "Gateway stop timed out before import (status=$currentStatus)."
                            )
                        }
                        true
                    }
                    GatewayStatus.STOPPING -> {
                        val stopped = waitForGatewayStopped(allowErrorAsStopped = true)
                        if (!stopped) {
                            val currentStatus = processManager.gatewayState.value.status
                            throw IllegalStateException(
                                "Gateway stop timed out before import (status=$currentStatus)."
                            )
                        }
                        false
                    }
                    else -> false
                }
            }

            override suspend fun verifyRestoredGatewayStartable(
                wasGatewayActiveBeforeRestore: Boolean,
            ): TransferGatewayRestoreVerification {
                GatewayService.start(context, source = "settings:transfer_import_verify")
                val started = waitForGatewayStatus(
                    expectedStatus = GatewayStatus.RUNNING,
                    timeoutMs = TRANSFER_IMPORT_VERIFY_TIMEOUT_MS,
                )
                if (started) {
                    if (!wasGatewayActiveBeforeRestore) {
                        GatewayService.stop(context, source = "settings:transfer_import_restore_prior_state")
                        val stopped = waitForGatewayStopped()
                        if (!stopped) {
                            val currentStatus = processManager.gatewayState.value.status
                            return TransferGatewayRestoreVerification.TransientRuntimeFailure(
                                message = "Gateway stop timed out after verification (status=$currentStatus).",
                            )
                        }
                    }
                    return TransferGatewayRestoreVerification.Success
                }

                val status = processManager.gatewayState.value.status
                val errorMessage = processManager.gatewayState.value.errorMessage.orEmpty()
                return if (
                    status == GatewayStatus.ERROR && isStructuralGatewayRestoreFailure(errorMessage)
                ) {
                    TransferGatewayRestoreVerification.StructuralFailure(
                        message = errorMessage.ifBlank { "Gateway failed to start with structural configuration error." },
                    )
                } else {
                    if (!wasGatewayActiveBeforeRestore) {
                        GatewayService.stop(context, source = "settings:transfer_import_verify_cleanup")
                        val stopped = waitForGatewayStopped(allowErrorAsStopped = true)
                        if (!stopped) {
                            val currentStatus = processManager.gatewayState.value.status
                            return TransferGatewayRestoreVerification.TransientRuntimeFailure(
                                message = "Gateway stop timed out after verification (status=$currentStatus).",
                            )
                        }
                    }
                    TransferGatewayRestoreVerification.TransientRuntimeFailure(
                        message = if (errorMessage.isNotBlank()) errorMessage else "Gateway start verification timed out.",
                    )
                }
            }

            override suspend fun restorePreRestoreGatewayState(wasGatewayActiveBeforeRestore: Boolean) {
                if (wasGatewayActiveBeforeRestore) {
                    GatewayService.start(context, source = "settings:transfer_import_restore_previous_state")
                } else {
                    GatewayService.stop(context, source = "settings:transfer_import_restore_inactive_state")
                }
            }
        }
    }

    private fun exportFailureMessage(result: SettingsTransferExportResult.Error): String {
        return when (result.reason) {
            SettingsTransferFailureReason.WRONG_PASSWORD -> appString(R.string.settings_transfer_error_wrong_password)
            SettingsTransferFailureReason.VERSION_MISMATCH -> appString(R.string.settings_transfer_error_version_mismatch)
            SettingsTransferFailureReason.TRANSIENT_RUNTIME -> appString(R.string.settings_transfer_error_export_failed)
            SettingsTransferFailureReason.UNKNOWN -> appString(R.string.settings_transfer_error_export_failed)
        }
    }

    private fun importFailureMessage(result: SettingsTransferImportResult.Error): String {
        return when (result.reason) {
            SettingsTransferFailureReason.WRONG_PASSWORD -> appString(R.string.settings_transfer_error_wrong_password)
            SettingsTransferFailureReason.VERSION_MISMATCH -> appString(R.string.settings_transfer_error_version_mismatch)
            SettingsTransferFailureReason.TRANSIENT_RUNTIME,
            SettingsTransferFailureReason.UNKNOWN -> appString(R.string.settings_transfer_error_import_failed)
        }
    }

    private fun appString(resId: Int): String = getApplication<Application>().getString(resId)

    private fun isTransferBlockedByMaintenance(): Boolean {
        return _isDoctorFixRunning.value ||
            _isRecoveryInstallRunning.value ||
            _isOpenClawUpdateRunning.value ||
            _isCodexAuthInProgress.value ||
            _isGitHubCopilotAuthInProgress.value
    }

    private fun isStructuralGatewayRestoreFailure(errorMessage: String): Boolean {
        if (errorMessage.isBlank()) return false
        val normalized = errorMessage.lowercase(Locale.US)
        return normalized.contains("config") ||
            normalized.contains("invalid") ||
            normalized.contains("json") ||
            normalized.contains("parse") ||
            normalized.contains("missingenvvar") ||
            normalized.contains("missing env") ||
            normalized.contains("missing environment") ||
            normalized.contains("schema") ||
            normalized.contains("required")
    }

    private suspend fun waitForGatewayStatus(
        expectedStatus: GatewayStatus,
        timeoutMs: Long,
    ): Boolean {
        val startAt = System.currentTimeMillis()
        while (System.currentTimeMillis() - startAt < timeoutMs) {
            if (processManager.gatewayState.value.status == expectedStatus) {
                return true
            }
            delay(TRANSFER_IMPORT_VERIFY_POLL_MS)
        }
        return false
    }

    private fun resolveCurrentVersionCode(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            1L
        }
    }

    private fun SettingsTransferFailureReason.toTransferFailureUiReason(): TransferFailureUiReason {
        return when (this) {
            SettingsTransferFailureReason.WRONG_PASSWORD -> TransferFailureUiReason.WRONG_PASSWORD
            SettingsTransferFailureReason.VERSION_MISMATCH -> TransferFailureUiReason.VERSION_MISMATCH
            SettingsTransferFailureReason.TRANSIENT_RUNTIME -> TransferFailureUiReason.TRANSIENT_RUNTIME
            SettingsTransferFailureReason.UNKNOWN -> TransferFailureUiReason.UNKNOWN
        }
    }

    fun openBugReportDialog() {
        viewModelScope.launch(Dispatchers.IO) {
            val previewResult = runCatching {
                val sessionEntries = processManager.getSessionLogEntries()
                val gatewayErrorMessage = processManager.gatewayState.value.errorMessage
                val gatewayLogLines = processManager.logLines.value
                buildBugReportPreview(sessionEntries, gatewayErrorMessage, gatewayLogLines)
            }

            val preview = previewResult.getOrElse { BugReportPreview() }
            val openErrorMessage = previewResult.exceptionOrNull()?.message
                ?.takeIf { it.isNotBlank() }
                ?: previewResult.exceptionOrNull()?.let { "Failed to load preview data." }

            withContext(Dispatchers.Main) {
                _bugReportUiState.value = _bugReportUiState.value.copy(
                    isVisible = true,
                    hasConsent = false,
                    isGenerating = false,
                    preview = preview,
                    generationErrorMessage = openErrorMessage,
                )
            }
        }
    }

    fun dismissBugReportDialog() {
        _bugReportUiState.value = _bugReportUiState.value.copy(
            isVisible = false,
            hasConsent = false,
            isGenerating = false,
            generationErrorMessage = null,
        )
    }

    fun setBugReportConsent(consented: Boolean) {
        _bugReportUiState.value = _bugReportUiState.value.copy(hasConsent = consented)
    }

    fun setBugReportGenerationErrorMessage(message: String?) {
        _bugReportUiState.value = _bugReportUiState.value.copy(generationErrorMessage = message)
    }

    fun generateBugReportZip() {
        val currentState = _bugReportUiState.value
        if (!currentState.isVisible || !currentState.hasConsent || currentState.isGenerating) return

        _bugReportUiState.value = currentState.copy(
            isGenerating = true,
            generationErrorMessage = null,
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val sessionEntries = processManager.getSessionLogEntries()
                val gatewayErrorMessage = processManager.gatewayState.value.errorMessage
                val gatewayLogLines = processManager.logLines.value
                val metadata = BugReportBundleBuilder.collectMetadata(context)
                val bundle = BugReportBundleBuilder.build(
                    sessionEntries = sessionEntries,
                    gatewayLogLines = gatewayLogLines,
                    metadata = metadata,
                    gatewayErrorMessage = gatewayErrorMessage,
                    processErrorMessage = gatewayErrorMessage,
                )
                val artifact = BugReportZipWriter.write(context, bundle)
                val artifactInfo = BugReportArtifactInfo(
                    fileName = artifact.file.name,
                    sizeBytes = artifact.file.length(),
                )
                val preview = buildBugReportPreview(sessionEntries, gatewayErrorMessage, gatewayLogLines)

                withContext(Dispatchers.Main) {
                    _bugReportUiState.value = _bugReportUiState.value.copy(
                        isGenerating = false,
                        preview = preview,
                        artifact = artifact,
                        artifactInfo = artifactInfo,
                        generationErrorMessage = null,
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                withContext(Dispatchers.Main) {
                    _bugReportUiState.value = _bugReportUiState.value.copy(
                        isGenerating = false,
                        generationErrorMessage = e.message ?: "Failed to generate bug report ZIP.",
                    )
                }
            }
        }
    }

    private fun buildBugReportPreview(
        sessionEntries: List<SessionLogEntry>,
        gatewayErrorMessage: String?,
        gatewayLogLines: List<String>,
    ): BugReportPreview {
        val includedGatewayLogCount = BugReportBundleBuilder.sanitizeGatewayLogLines(gatewayLogLines).size
        return BugReportPreview(
            sessionErrorCount = sessionEntries.count { it.stopReason == "error" || it.errorMessage != null },
            hasGatewayError = !gatewayErrorMessage.isNullOrBlank(),
            hasProcessError = !gatewayErrorMessage.isNullOrBlank(),
            gatewayLogCount = includedGatewayLogCount,
        )
    }

    fun setTelegramEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setTelegramEnabled(enabled)
            applyChannelConfigAndRestart()
        }
    }

    fun setTelegramBotToken(token: String, restartGateway: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setTelegramBotToken(token)
            if (restartGateway) {
                applyChannelConfigAndRestart()
            }
        }
    }

    fun setDiscordEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setDiscordEnabled(enabled)
            applyChannelConfigAndRestart()
        }
    }

    fun setDiscordBotToken(token: String, restartGateway: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setDiscordBotToken(token)
            if (restartGateway) {
                applyChannelConfigAndRestart()
            }
        }
    }

    fun setDiscordGuildAllowlist(raw: String, restartGateway: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setDiscordGuildAllowlist(raw)
            if (restartGateway) {
                applyChannelConfigAndRestart()
            }
        }
    }

    fun setDiscordRequireMention(enabled: Boolean, restartGateway: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setDiscordRequireMention(enabled)
            if (restartGateway) {
                applyChannelConfigAndRestart()
            }
        }
    }

    private var restartJob: kotlinx.coroutines.Job? = null

    private suspend fun applyChannelConfigAndRestart() {
        val channelConfig = prefs.channelConfig.first()
        processManager.ensureChannelConfig(channelConfig)
        restartGatewayIfRunning(source = "settings:channel_config_changed")
    }

    private fun supportsMemorySearchOverride(provider: String): Boolean {
        return when (provider.trim().lowercase()) {
            "auto", "openai", "gemini", "voyage", "mistral" -> true
            else -> false
        }
    }

    private suspend fun applyMemorySearchConfigAndRestart(forceRestart: Boolean) {
        val launchConfig = prefs.getGatewayLaunchConfigSnapshot()

        if (launchConfig.selectedModelEntries.isNotEmpty()) {
            processManager.ensureOpenClawConfig(
                apiProvider = launchConfig.apiProvider,
                apiKey = launchConfig.apiKey,
                selectedModel = launchConfig.selectedModel,
                selectedModels = launchConfig.selectedModelEntries.map { it.toProcessEntry() },
                primaryModelId = launchConfig.primaryModelId,
                openAiCompatibleBaseUrl = launchConfig.openAiCompatibleBaseUrl,
                ollamaBaseUrl = launchConfig.ollamaBaseUrl,
                modelReasoning = launchConfig.modelReasoning,
                modelImages = launchConfig.modelImages,
                modelContext = launchConfig.modelContext,
                modelMaxOutput = launchConfig.modelMaxOutput,
                memorySearchEnabled = launchConfig.memorySearchEnabled,
                memorySearchProvider = launchConfig.memorySearchProvider,
                memorySearchApiKey = launchConfig.memorySearchApiKey,
            )
        }
        val shouldRestartForMemoryRuntime =
            launchConfig.memorySearchEnabled && supportsMemorySearchOverride(launchConfig.memorySearchProvider)
        if (forceRestart || shouldRestartForMemoryRuntime) {
            restartGatewayIfRunning(source = "settings:memory_search_changed")
        }
    }

    private fun restartGatewayIfRunning(
        delayMs: Long = 1000L,
        source: String = "settings:restart_if_running",
    ) {
        val status = processManager.gatewayState.value.status
        if (
            status != GatewayStatus.RUNNING &&
            status != GatewayStatus.STARTING &&
            status != GatewayStatus.ERROR
        ) return
        restartJob?.cancel()
        restartJob = viewModelScope.launch(Dispatchers.Main) {
            kotlinx.coroutines.delay(delayMs)
            val context = getApplication<Application>()
            GatewayService.restart(context, userInitiated = false, source = source)
        }
    }

    private suspend fun restartGatewayForWhatsAppRecovery() {
        withContext(Dispatchers.Main) {
            val context = getApplication<Application>()
            // 515 복구는 강제로 재시작을 시도해야 하므로 userInitiated=true로 우회한다.
            GatewayService.restart(context, userInitiated = true, source = "settings:whatsapp_recovery_restart")
        }
    }

    fun applyRuntimeLaunchConfigNow() {
        viewModelScope.launch(Dispatchers.IO) {
            val launchConfig = prefs.getGatewayLaunchConfigSnapshot()
            val context = getApplication<Application>()
            when (resolveRuntimeLaunchConfigChangeAction(launchConfig, detectCodexAuth())) {
                RuntimeLaunchConfigChangeAction.RESTART -> GatewayService.restart(
                    context,
                    userInitiated = true,
                    source = "settings:runtime_launch_config_restart",
                )
                RuntimeLaunchConfigChangeAction.STOP -> GatewayService.stop(
                    context,
                    source = "settings:runtime_launch_config_stop",
                )
                RuntimeLaunchConfigChangeAction.NONE -> Unit
            }
        }
    }

    fun restartGatewayIfRunningNow() {
        restartGatewayIfRunning(delayMs = 0L, source = "settings:manual_restart_now")
    }

    fun fetchModels() {
        if (_isLoadingModels.value) return
        _isLoadingModels.value = true
        _modelLoadError.value = null
        _hasLoadedModels.value = false

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
                        val responseCode = conn.responseCode
                        if (responseCode != 200) {
                            throw Exception("HTTP $responseCode")
                        }

                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        parseOpenRouterModelsLocal(body)
                    } finally {
                        conn.disconnect()
                    }
                }
                val persistedSelectedModels = withContext(Dispatchers.IO) {
                    prefs.currentProviderSelectedModelEntries.first()
                        .map { entry ->
                            OpenRouterModel(
                                id = entry.id,
                                name = entry.id,
                                contextLength = entry.contextLength,
                                maxOutputTokens = entry.maxOutputTokens,
                                isFree = false,
                                pricing = "Custom",
                                supportsReasoning = entry.supportsReasoning,
                                supportsImages = entry.supportsImages,
                            )
                        }
                }
                _availableModels.value = (models + persistedSelectedModels).distinctBy { it.id }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _modelLoadError.value = e.message
            } finally {
                _isLoadingModels.value = false
                _hasLoadedModels.value = true
            }
        }
    }

    fun fetchModelsForCurrentProvider() {
        val provider = apiProvider.value
        if (provider == "openrouter") {
            fetchModels()
            return
        }

        if (_isLoadingModels.value) return
        _isLoadingModels.value = true
        _modelLoadError.value = null
        _hasLoadedModels.value = false

        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    val persistedSelectedModels = prefs.currentProviderSelectedModelEntries.first()
                        .map { entry ->
                            OpenRouterModel(
                                id = entry.id,
                                name = entry.id,
                                contextLength = entry.contextLength,
                                maxOutputTokens = entry.maxOutputTokens,
                                isFree = false,
                                pricing = when (provider) {
                                    "ollama" -> "Local"
                                    "ollama-cloud" -> "Cloud"
                                    else -> "Custom"
                                },
                                supportsReasoning = entry.supportsReasoning,
                                supportsImages = entry.supportsImages,
                            )
                        }
                    val providerModels = when (provider) {
                        "ollama" -> fetchOllamaModels(prefs.ollamaBaseUrl.first())
                        "ollama-cloud" -> fetchOllamaCloudModels()
                        else -> loadBuiltInModels(provider)
                    }
                    (providerModels + persistedSelectedModels).distinctBy { it.id }
                }
                _availableModels.value = models
                if (models.isEmpty()) {
                    _modelLoadError.value = when (provider) {
                        "ollama", "ollama-cloud" -> "No models found on Ollama server."
                        else -> "No built-in models found."
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _availableModels.value = emptyList()
                _modelLoadError.value = when (provider) {
                    "ollama", "ollama-cloud" -> e.message ?: "Failed to load models from Ollama server."
                    else -> e.message ?: "Failed to load built-in models."
                }
            } finally {
                _isLoadingModels.value = false
                _hasLoadedModels.value = true
            }
        }
    }

    private fun fetchOllamaModels(baseUrl: String): List<OpenRouterModel> {
        return OpenClawModelCatalogReader.loadOllamaModelsFromServer(baseUrl)
            .map { entry ->
                OpenRouterModel(
                    id = entry.id,
                    name = entry.name,
                    contextLength = entry.contextWindow,
                    maxOutputTokens = entry.maxTokens,
                    isFree = false,
                    pricing = "Local",
                    supportsReasoning = entry.supportsReasoning,
                    supportsImages = entry.supportsImages,
                )
            }
    }

    private suspend fun fetchOllamaCloudModels(): List<OpenRouterModel> {
        val apiKey = prefs.getApiKeyForProvider("ollama-cloud")
        return OpenClawModelCatalogReader.loadOllamaModelsFromServer(
            baseUrl = "https://ollama.com",
            apiKey = apiKey,
        ).map { entry ->
            OpenRouterModel(
                id = entry.id,
                name = entry.name,
                contextLength = entry.contextWindow,
                maxOutputTokens = entry.maxTokens,
                isFree = false,
                pricing = "Cloud",
                supportsReasoning = entry.supportsReasoning,
                supportsImages = entry.supportsImages,
            )
        }
    }

    private fun parseOpenRouterModelsLocal(jsonBody: String): List<OpenRouterModel> {
        val result = mutableListOf<OpenRouterModel>()
        val json = JSONObject(jsonBody)
        val data = json.getJSONArray("data")

        for (i in 0 until data.length()) {
            val model = data.getJSONObject(i)
            val id = model.optString("id", "")

            val supportedParams = model.optJSONArray("supported_parameters")
            var supportsTools = false
            if (supportedParams != null) {
                for (j in 0 until supportedParams.length()) {
                    if (supportedParams.getString(j) == "tools") {
                        supportsTools = true
                        break
                    }
                }
            }
            if (!supportsTools) continue

            var supportsReasoning = false
            if (supportedParams != null) {
                for (j in 0 until supportedParams.length()) {
                    if (supportedParams.getString(j) == "reasoning") {
                        supportsReasoning = true
                        break
                    }
                }
            }

            val modality = model.optString("modality", "")
            val supportsImages = modality.contains("image")
            val name = model.optString("name", id)
            val contextLength = model.optInt("context_length", 0)
            val maxOutputTokens = model.optInt(
                "top_provider_max_completion_tokens",
                model.optInt("max_completion_tokens", 4096),
            )

            val pricingObj = model.optJSONObject("pricing")
            val promptStr = pricingObj?.optString("prompt", "") ?: ""
            val completionStr = pricingObj?.optString("completion", "") ?: ""
            val promptPrice = promptStr.toDoubleOrNull() ?: -1.0
            val completionPrice = completionStr.toDoubleOrNull() ?: -1.0
            val isFree = id.endsWith(":free") || (promptPrice == 0.0 && completionPrice == 0.0)

            val pricing = if (isFree) {
                "Free"
            } else {
                val perMillion = promptPrice * 1_000_000
                when {
                    perMillion < 0.01 -> "$0/M"
                    perMillion < 1.0 -> "$${String.format("%.2f", perMillion)}/M"
                    else -> "$${String.format("%.1f", perMillion)}/M"
                }
            }

            result.add(
                OpenRouterModel(
                    id = id,
                    name = name,
                    contextLength = contextLength,
                    maxOutputTokens = maxOutputTokens,
                    isFree = isFree,
                    pricing = pricing,
                    supportsReasoning = supportsReasoning,
                    supportsImages = supportsImages,
                ),
            )
        }

        val freeModels = result.filter { it.isFree }.sortedByDescending { it.contextLength }
        val paidModels = result.filter { !it.isFree }.sortedByDescending { it.contextLength }
        return freeModels + paidModels
    }

    private fun loadBuiltInModels(provider: String): List<OpenRouterModel> {
        val fallbackModels = defaultBuiltInModels(provider)
        val rootfsDir = prootManager.rootfsDir
        val builtInModels = OpenClawModelCatalogReader.loadProviderModels(rootfsDir, provider)
            .ifEmpty {
                if (provider == "openai-codex") {
                    val legacyCodexEntries = resolveLegacyOpenAiCodexEntries(rootfsDir)
                    val syntheticCodexEntries = OpenClawModelCatalogReader.loadSyntheticFallbackEntries(
                        rootfsDir = rootfsDir,
                        provider = "openai-codex",
                        baseEntries = legacyCodexEntries,
                    )
                    (legacyCodexEntries + syntheticCodexEntries)
                        .distinctBy { it.id }
                } else {
                    emptyList()
                }
            }
            .map { it.toOpenRouterModel() }

        return (if (builtInModels.isNotEmpty()) builtInModels else fallbackModels)
            .distinctBy { it.id }
    }

    private fun resolveCodexModelsFromInstalledBundle(): List<OpenRouterModel> {
        val rootfsDir = runCatching { prootManager.rootfsDir }.getOrNull() ?: return emptyList()

        val directCodexModels = OpenClawModelCatalogReader.loadProviderModels(rootfsDir, "openai-codex")
            .map { it.toOpenRouterModel() }
        if (directCodexModels.isNotEmpty()) return directCodexModels.distinctBy { it.id }

        val legacyCodexEntries = resolveLegacyOpenAiCodexEntries(rootfsDir)
        val syntheticCodexEntries = OpenClawModelCatalogReader.loadSyntheticFallbackEntries(
            rootfsDir = rootfsDir,
            provider = "openai-codex",
            baseEntries = legacyCodexEntries,
        )
        return (legacyCodexEntries + syntheticCodexEntries)
            .distinctBy { it.id }
            .map { it.toOpenRouterModel() }
    }

    private fun resolvePreferredCodexPrimaryModelFromInstalledBundle(): String {
        val availableIds = resolveCodexModelsFromInstalledBundle().map { it.id }
        return when {
            "gpt-5.4" in availableIds -> "openai-codex/gpt-5.4"
            "gpt-5.3-codex" in availableIds -> "openai-codex/gpt-5.3-codex"
            availableIds.isNotEmpty() -> "openai-codex/${availableIds.first()}"
            else -> "openai-codex/gpt-5.3-codex"
        }
    }


    private fun OpenClawModelCatalogReader.ModelEntry.toOpenRouterModel(): OpenRouterModel {
        return OpenRouterModel(
            id = id,
            name = name,
            contextLength = contextWindow,
            maxOutputTokens = maxTokens,
            isFree = false,
            pricing = "Built-in",
            supportsReasoning = supportsReasoning,
            supportsImages = supportsImages,
        )
    }

    private fun resolveLegacyOpenAiCodexEntries(rootfsDir: File?): List<OpenClawModelCatalogReader.ModelEntry> {
        return OpenClawModelCatalogReader.loadProviderModels(rootfsDir, "openai")
            .asSequence()
            .filter { isLegacyOpenAiCodexModelId(it.id) }
            .map { entry -> entry.copy(provider = "openai-codex") }
            .distinctBy { it.id }
            .toList()
    }

    private fun isLegacyOpenAiCodexModelId(modelId: String): Boolean {
        val normalizedId = normalizeCodexModelIdForComparison(modelId)
        return normalizedId.contains("codex", ignoreCase = true) ||
            normalizedId == "gpt-5.1" ||
            normalizedId == "gpt-5.2" ||
            normalizedId == "gpt-5.4"
    }

    private fun builtInModel(
        id: String,
        contextWindow: Int,
        maxTokens: Int,
        supportsReasoning: Boolean = false,
        supportsImages: Boolean = false,
    ): OpenRouterModel {
        return OpenRouterModel(
            id = id,
            name = id,
            contextLength = contextWindow,
            maxOutputTokens = maxTokens,
            isFree = false,
            pricing = "Built-in",
            supportsReasoning = supportsReasoning,
            supportsImages = supportsImages,
        )
    }

    private fun defaultBuiltInModels(provider: String): List<OpenRouterModel> {
        return when (provider) {
            "anthropic" -> listOf(
                builtInModel("claude-sonnet-4-5", contextWindow = 200_000, maxTokens = 8_192),
                builtInModel("claude-opus-4.1", contextWindow = 200_000, maxTokens = 8_192),
            )
            "openai" -> listOf(
                builtInModel("gpt-5-mini", contextWindow = 128_000, maxTokens = 16_384),
                builtInModel("gpt-5", contextWindow = 128_000, maxTokens = 16_384),
            )
            "openai-codex" -> listOf(
                builtInModel("gpt-5.3-codex", contextWindow = 272_000, maxTokens = 128_000, supportsReasoning = true, supportsImages = true),
            )
            "github-copilot" -> listOf(
                builtInModel("gpt-4o", contextWindow = 64_000, maxTokens = 16_384, supportsImages = true),
                builtInModel("gpt-4.1", contextWindow = 64_000, maxTokens = 16_384, supportsImages = true),
                builtInModel("claude-sonnet-4.5", contextWindow = 128_000, maxTokens = 32_000, supportsReasoning = true, supportsImages = true),
            )
            "zai" -> listOf(
                builtInModel("glm-5", contextWindow = 204_800, maxTokens = 131_072, supportsReasoning = true),
                builtInModel("glm-4.6v", contextWindow = 128_000, maxTokens = 32_768, supportsReasoning = true, supportsImages = true),
            )
            "kimi-coding" -> listOf(
                builtInModel("k2p5", contextWindow = 262_144, maxTokens = 32_768, supportsReasoning = true, supportsImages = true),
                builtInModel("kimi-k2-thinking", contextWindow = 262_144, maxTokens = 32_768, supportsReasoning = true),
            )
            "minimax" -> listOf(
                builtInModel("MiniMax-M2.5", contextWindow = 204_800, maxTokens = 131_072, supportsReasoning = true),
                builtInModel("MiniMax-M2.5-highspeed", contextWindow = 204_800, maxTokens = 131_072, supportsReasoning = true),
            )
            "ollama" -> emptyList()
            "ollama-cloud" -> emptyList()
            "openai-compatible" -> emptyList()
            "google" -> listOf(
                builtInModel("gemini-2.5-flash", contextWindow = 1_000_000, maxTokens = 8_192),
                builtInModel("gemini-2.5-pro", contextWindow = 1_000_000, maxTokens = 8_192),
            )
            else -> emptyList()
        }
    }

    private fun resolveOpenAiCompatibleModelMetadata(modelId: String): OpenRouterModel {
        return defaultBuiltInModels("openai-compatible")
            .firstOrNull { it.id == modelId }
            ?: OpenRouterModel(
                id = modelId,
                name = modelId,
                contextLength = 128_000,
                maxOutputTokens = 4_096,
                isFree = false,
                pricing = "Custom",
                supportsReasoning = false,
                supportsImages = false,
            )
    }

    private fun resolveOllamaModelMetadata(modelId: String): OpenRouterModel {
        return defaultBuiltInModels("ollama")
            .firstOrNull { it.id == modelId }
            ?: OpenRouterModel(
                id = modelId,
                name = modelId,
                contextLength = 32_768,
                maxOutputTokens = 4_096,
                isFree = false,
                pricing = "Local",
                supportsReasoning = false,
                supportsImages = false,
            )
    }

    private fun SelectedModelConfigEntry.toOpenRouterModel(): OpenRouterModel {
        return OpenRouterModel(
            id = id,
            name = id,
            contextLength = contextLength,
            maxOutputTokens = maxOutputTokens,
            isFree = false,
            pricing = "Custom",
            supportsReasoning = supportsReasoning,
            supportsImages = supportsImages,
        )
    }

    private fun SelectedModelConfigEntry.toProcessEntry(): ProcessManager.ModelSelectionEntry {
        return ProcessManager.ModelSelectionEntry(
            id = id,
            supportsReasoning = supportsReasoning,
            supportsImages = supportsImages,
            contextLength = contextLength,
            maxOutputTokens = maxOutputTokens,
        )
    }

    fun refreshCodexAuthStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            _isCodexAuthenticated.value = detectCodexAuth()
        }
    }

    fun loginOpenAiCodexOAuth() {
        if (!codexAuthRunning.compareAndSet(false, true)) return

        viewModelScope.launch(Dispatchers.IO) {
            _isCodexAuthInProgress.value = true
            try {
                _codexAuthUrl.value = null
                _codexAuthDebugLine.value = null

                runCodexDirectPkceLogin()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "Codex OAuth failed: ${e.message}", e)
                _codexAuthDebugLine.value = e.message
            } finally {
                _isCodexAuthenticated.value = detectCodexAuth()
                Log.i(TAG, "Auth flow finished. authenticated=${_isCodexAuthenticated.value}")
                _isCodexAuthInProgress.value = false
                codexAuthRunning.set(false)
            }
        }
    }

    private data class AuthorizationFlow(
        val verifier: String,
        val state: String,
        val url: String,
    )

    private data class OAuthTokenResult(
        val access: String,
        val refresh: String,
        val expires: Long,
    )

    private data class GitHubCopilotDeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String?,
        val expiresInSec: Long,
        val intervalSec: Long,
    )

    private suspend fun runCodexDirectPkceLogin() {
        val flow = createAuthorizationFlow()
        _codexAuthUrl.value = flow.url
        Log.i(TAG, "Detected auth URL: ${flow.url}")

        val code = waitForOAuthCallbackCode(flow.state)
            ?: throw IllegalStateException("OAuth callback timeout")

        val token = exchangeAuthorizationCode(code, flow.verifier)
        val accountId = extractAccountId(token.access)
            ?: throw IllegalStateException("Failed to extract accountId from token")

        writeCodexOAuthCredentials(token, accountId)
        ensureCodexPrimaryModel()
    }

    private fun createAuthorizationFlow(originator: String = "pi"): AuthorizationFlow {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)
        val state = generateState()
        val authUrl = Uri.parse(OAUTH_AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("response_mode", "query")
            .appendQueryParameter("client_id", OAUTH_CLIENT_ID)
            .appendQueryParameter("redirect_uri", OAUTH_REDIRECT_URI)
            .appendQueryParameter("scope", OAUTH_SCOPE)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("id_token_add_organizations", "true")
            .appendQueryParameter("codex_cli_simplified_flow", "true")
            .appendQueryParameter("originator", originator)
            .build()
            .toString()

        return AuthorizationFlow(
            verifier = verifier,
            state = state,
            url = authUrl,
        )
    }

    private fun waitForOAuthCallbackCode(expectedState: String, timeoutMs: Long = 300_000): String? {
        val startedAt = System.currentTimeMillis()
        var serverSocket: ServerSocket? = null
        return try {
            synchronized(oauthServerLock) {
                runCatching { oauthServerSocket?.close() }
                oauthServerSocket = null

                serverSocket = ServerSocket(1455, 50, InetAddress.getByName("127.0.0.1"))
                oauthServerSocket = serverSocket
            }
            val socket = serverSocket ?: return null
            socket.soTimeout = 1_000

            while (System.currentTimeMillis() - startedAt < timeoutMs) {
                try {
                    val client = socket.accept()
                    client.use {
                        client.soTimeout = 3_000
                        val requestLine = BufferedReader(InputStreamReader(client.getInputStream()))
                            .readLine()
                            .orEmpty()
                        Log.d(TAG, "OAuth callback requestLine=$requestLine")
                        val target = requestLine.split(" ").getOrNull(1).orEmpty()
                        val parsedUri = if (target.startsWith("http://") || target.startsWith("https://")) {
                            runCatching { Uri.parse(target) }.getOrNull()
                        } else {
                            null
                        }
                        val path = parsedUri?.path ?: target.substringBefore("?")
                        val query = parsedUri?.encodedQuery ?: target.substringAfter("?", "")
                        val params = parseQueryString(query)
                        val code = params["code"]
                        val state = params["state"]
                        val err = params["error"]
                        val errDesc = params["error_description"]
                        val body: String

                        val isCallbackPath = path == "/auth/callback" || path == "/auth/callback/"
                        if (!isCallbackPath) {
                            Log.w(TAG, "OAuth callback path mismatch: path=$path target=$target")
                            body = "Not found"
                            client.getOutputStream().write(
                                "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
                                    .toByteArray()
                            )
                        } else if (code.isNullOrBlank()) {
                            Log.w(TAG, "OAuth callback missing code. error=$err desc=$errDesc params=$params")
                            body = "Invalid callback"
                            client.getOutputStream().write(
                                "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
                                    .toByteArray()
                            )
                        } else {
                            if (state != expectedState) {
                                Log.w(TAG, "OAuth state mismatch. expected=$expectedState actual=$state")
                            }
                            val title = getApplication<Application>().getString(R.string.settings_api_key_configured)
                            val appName = getApplication<Application>().getString(R.string.app_name)
                            val appReturnUri = BuildConfig.OAUTH_APP_RETURN_URI
                            body = """
                                <!doctype html>
                                <html lang="en">
                                <head>
                                  <meta charset="utf-8" />
                                  <meta name="viewport" content="width=device-width, initial-scale=1" />
                                  <title>$appName</title>
                                  <style>
                                    body {
                                      margin: 0;
                                      min-height: 100vh;
                                      display: grid;
                                      place-items: center;
                                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                                      background: #f6f8fb;
                                      color: #0f172a;
                                    }
                                    .card {
                                      width: min(92vw, 560px);
                                      background: #ffffff;
                                      border-radius: 18px;
                                      padding: 28px 24px;
                                      box-shadow: 0 10px 30px rgba(15, 23, 42, 0.10);
                                      text-align: center;
                                    }
                                    .mark {
                                      font-size: 64px;
                                      line-height: 1;
                                      margin-bottom: 10px;
                                    }
                                    h1 {
                                      margin: 0;
                                      font-size: 32px;
                                      font-weight: 800;
                                    }
                                    p {
                                      margin: 12px 0 0 0;
                                      font-size: 20px;
                                      color: #334155;
                                    }
                                    a {
                                      display: inline-block;
                                      margin-top: 20px;
                                      font-size: 16px;
                                      color: #2563eb;
                                      text-decoration: none;
                                      font-weight: 600;
                                    }
                                  </style>
                                </head>
                                <body>
                                  <div class="card">
                                    <div class="mark">✓</div>
                                    <h1>$title</h1>
                                    <p>$appName</p>
                                    <a href="$appReturnUri">Return to $appName</a>
                                  </div>
                                  <script>
                                    setTimeout(function() {
                                      window.location.href = "$appReturnUri";
                                    }, 350);
                                  </script>
                                </body>
                                </html>
                            """.trimIndent()
                            client.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
                                    .toByteArray()
                            )
                            return code
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    // keep waiting
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind callback server: ${e.message}", e)
            null
        } finally {
            synchronized(oauthServerLock) {
                runCatching { serverSocket?.close() }
                if (oauthServerSocket === serverSocket) {
                    oauthServerSocket = null
                }
            }
        }
    }

    fun consumeCodexAuthUrl() {
        _codexAuthUrl.value = null
    }

    fun refreshGitHubCopilotAuthStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshGitHubCopilotAuthStatusInternal()
        }
    }

    fun loginGitHubCopilot() {
        if (!gitHubCopilotAuthRunning.compareAndSet(false, true)) return

        gitHubCopilotAuthJob = viewModelScope.launch(Dispatchers.IO) {
            _isGitHubCopilotAuthInProgress.value = true
            try {
                clearGitHubCopilotAuthTransientUi(clearDebugLine = true)
                runGitHubCopilotDeviceLogin()
                _gitHubCopilotAuthDebugLine.value = null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _gitHubCopilotAuthDebugLine.value = e.message ?: "GitHub Copilot login failed"
                Log.e(TAG, "GitHub Copilot login failed: ${e.message}", e)
            } finally {
                clearGitHubCopilotAuthTransientUi(clearDebugLine = false)
                refreshGitHubCopilotAuthStatusInternal()
                _isGitHubCopilotAuthInProgress.value = false
                gitHubCopilotAuthRunning.set(false)
                gitHubCopilotAuthJob = null
            }
        }
    }

    fun cancelGitHubCopilotLogin() {
        gitHubCopilotAuthJob?.cancel()
        gitHubCopilotAuthJob = null
        gitHubCopilotAuthRunning.set(false)
        clearGitHubCopilotAuthTransientUi(clearDebugLine = false)
        _isGitHubCopilotAuthInProgress.value = false
    }

    fun consumeGitHubCopilotAuthUrl() {
        _gitHubCopilotAuthUrl.value = null
    }

    private fun clearGitHubCopilotAuthTransientUi(clearDebugLine: Boolean) {
        _gitHubCopilotAuthUrl.value = null
        _gitHubCopilotVerificationUrl.value = null
        _gitHubCopilotAuthCode.value = null
        if (clearDebugLine) {
            _gitHubCopilotAuthDebugLine.value = null
        }
    }

    private suspend fun refreshGitHubCopilotAuthStatusInternal() {
        val authenticated = detectGitHubCopilotAuth()
        prefs.setGitHubCopilotAuthenticated(authenticated)
        _isGitHubCopilotAuthenticated.value = authenticated
    }

    private suspend fun runGitHubCopilotDeviceLogin() {
        val previousLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
        val device = requestGitHubCopilotDeviceCode()
        _gitHubCopilotAuthCode.value = device.userCode
        val verificationUrl = device.verificationUriComplete ?: device.verificationUri
        _gitHubCopilotVerificationUrl.value = verificationUrl
        _gitHubCopilotAuthUrl.value = verificationUrl

        val accessToken = pollGitHubCopilotAccessToken(device)
        writeGitHubCopilotCredentials(accessToken)
        bootstrapGitHubCopilotSelectionIfNeeded()
        val appliedLaunchConfig = prefs.getGatewayLaunchConfigSnapshot()
        persistLaunchConfigIfRunnable(appliedLaunchConfig)
        notifyGitHubCopilotAuthApplied(previousLaunchConfig, appliedLaunchConfig)
    }

    private fun notifyGitHubCopilotAuthApplied(
        previousLaunchConfig: com.coderred.andclaw.data.GatewayLaunchConfigSnapshot,
        appliedLaunchConfig: com.coderred.andclaw.data.GatewayLaunchConfigSnapshot,
    ) {
        val status = processManager.gatewayState.value.status
        val isGatewayActive = status == GatewayStatus.RUNNING ||
            status == GatewayStatus.STARTING ||
            status == GatewayStatus.ERROR
        if (!isGatewayActive) return
        if (appliedLaunchConfig.apiProvider != "github-copilot") return

        val runtimeChanged = hasRuntimeLaunchConfigChanged(previousLaunchConfig, appliedLaunchConfig) ||
            previousLaunchConfig.apiProvider == "github-copilot"
        if (!runtimeChanged) return

        if (shouldPromptForRuntimeLaunchConfigChange(appliedLaunchConfig, detectCodexAuth())) {
            _gitHubCopilotAuthRestartHintNonce.value += 1
        }
    }

    private fun requestGitHubCopilotDeviceCode(): GitHubCopilotDeviceCode {
        val conn = URL(GITHUB_COPILOT_DEVICE_CODE_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = "client_id=${urlEncode(GITHUB_COPILOT_CLIENT_ID)}&scope=${urlEncode("read:user")}"
            conn.outputStream.use { it.write(body.toByteArray()) }

            val status = conn.responseCode
            val raw = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("GitHub device code failed: HTTP $status")
            }

            val json = JSONObject(raw)
            val deviceCode = json.optString("device_code").trim()
            val userCode = json.optString("user_code").trim()
            val verificationUri = json.optString("verification_uri").trim()
            val expiresInSec = json.optLong("expires_in", -1L)
            val intervalSec = json.optLong("interval", 5L)
            if (deviceCode.isBlank() || userCode.isBlank() || verificationUri.isBlank() || expiresInSec <= 0L) {
                throw IllegalStateException("GitHub device code response missing fields")
            }

            GitHubCopilotDeviceCode(
                deviceCode = deviceCode,
                userCode = userCode,
                verificationUri = verificationUri,
                verificationUriComplete = json.optString("verification_uri_complete").trim().ifBlank { null },
                expiresInSec = expiresInSec,
                intervalSec = intervalSec.coerceAtLeast(1L),
            )
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun pollGitHubCopilotAccessToken(device: GitHubCopilotDeviceCode): String {
        val expiresAt = System.currentTimeMillis() + (device.expiresInSec * 1000L)
        while (System.currentTimeMillis() < expiresAt) {
            val conn = URL(GITHUB_COPILOT_ACCESS_TOKEN_URL).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.doOutput = true
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val body = buildString {
                    append("client_id=").append(urlEncode(GITHUB_COPILOT_CLIENT_ID))
                    append("&device_code=").append(urlEncode(device.deviceCode))
                    append("&grant_type=").append(urlEncode("urn:ietf:params:oauth:grant-type:device_code"))
                }
                conn.outputStream.use { it.write(body.toByteArray()) }

                val status = conn.responseCode
                val raw = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (status !in 200..299) {
                    throw IllegalStateException("GitHub device token failed: HTTP $status")
                }

                val json = JSONObject(raw)
                val accessToken = json.optString("access_token").trim()
                if (accessToken.isNotBlank()) {
                    return accessToken
                }

                when (json.optString("error").trim()) {
                    "authorization_pending" -> delay(device.intervalSec * 1000L)
                    "slow_down" -> delay((device.intervalSec * 1000L) + 2_000L)
                    "expired_token" -> throw IllegalStateException("GitHub 인증 코드가 만료됐어. 다시 로그인해줘.")
                    "access_denied" -> throw IllegalStateException("GitHub 로그인이 취소됐어.")
                    else -> throw IllegalStateException(
                        "GitHub device flow error: ${json.optString("error").ifBlank { "unknown" }}",
                    )
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("GitHub 인증 코드가 만료됐어. 다시 로그인해줘.")
    }

    private fun writeGitHubCopilotCredentials(accessToken: String, profileId: String = "github-copilot:github") {
        val authFile = File(prootManager.rootfsDir, "root/.openclaw/agents/main/agent/auth-profiles.json")
        authFile.parentFile?.mkdirs()

        val root = if (authFile.exists()) {
            runCatching { JSONObject(authFile.readText()) }.getOrElse { JSONObject() }
        } else JSONObject()

        if (!root.has("version")) {
            root.put("version", 1)
        }
        val profiles = root.optJSONObject("profiles") ?: JSONObject().also { root.put("profiles", it) }
        profiles.put(
            profileId,
            JSONObject().apply {
                put("type", "token")
                put("provider", "github-copilot")
                put("token", accessToken)
            },
        )

        val lastGood = root.optJSONObject("lastGood") ?: JSONObject().also { root.put("lastGood", it) }
        lastGood.put("github-copilot", profileId)

        authFile.writeText(root.toString(2))
        updateGitHubCopilotProfilePreference(profileId)
    }

    private fun updateGitHubCopilotProfilePreference(profileId: String) {
        val configFile = File(prootManager.rootfsDir, "root/.openclaw/openclaw.json")
        if (!configFile.exists()) return

        val root = runCatching { JSONObject(configFile.readText()) }
            .getOrElse {
                Log.w(TAG, "Skipping GitHub Copilot profile preference update: invalid openclaw.json", it)
                return
            }

        val auth = root.optJSONObject("auth") ?: JSONObject().also { root.put("auth", it) }
        val profiles = auth.optJSONObject("profiles") ?: JSONObject().also { auth.put("profiles", it) }
        val existingProfile = profiles.optJSONObject(profileId)
        profiles.put(
            profileId,
            JSONObject(existingProfile?.toString() ?: "{}").apply {
                put("provider", "github-copilot")
                put("mode", "token")
            },
        )

        val order = auth.optJSONObject("order") ?: JSONObject().also { auth.put("order", it) }
        val existingOrder = mutableListOf<String>()
        val rawOrder = order.optJSONArray("github-copilot")
        if (rawOrder != null) {
            for (index in 0 until rawOrder.length()) {
                rawOrder.optString(index).trim().takeIf { it.isNotBlank() }?.let(existingOrder::add)
            }
        }
        val nextOrder = listOf(profileId) + existingOrder.filter { it != profileId }
        order.put("github-copilot", org.json.JSONArray(nextOrder))

        configFile.writeText(root.toString(2))
    }

    private suspend fun bootstrapGitHubCopilotSelectionIfNeeded() {
        val provider = "github-copilot"
        val existingSelectedIds = prefs.currentProviderSelectedModelIds.first()
        if (existingSelectedIds.isNotEmpty()) return

        val preferredModel = loadBuiltInModels(provider).firstOrNull { it.id == "gpt-4o" }
            ?: loadBuiltInModels(provider).firstOrNull()
            ?: return

        prefs.setSelectedModels(
            provider = provider,
            models = listOf(preferredModel),
            primary = preferredModel.id,
        )
    }

    private fun detectGitHubCopilotAuth(): Boolean {
        if (hasGitHubCopilotEnvAuth()) return true
        return try {
            val openClawAuthFile = File(prootManager.rootfsDir, "root/.openclaw/agents/main/agent/auth-profiles.json")
            if (openClawAuthFile.exists()) {
                val hasStoredProfile = runCatching {
                    val root = JSONObject(openClawAuthFile.readText())
                    val profiles = root.optJSONObject("profiles") ?: return@runCatching false
                    val keys = profiles.keys()
                    while (keys.hasNext()) {
                        val profileId = keys.next()
                        val profile = profiles.optJSONObject(profileId) ?: continue
                        if (profile.optString("provider").trim().lowercase() != "github-copilot") continue
                        val token = profile.optString("token").trim()
                        val tokenRef = hasOpenClawSecretRef(profile, "tokenRef")
                        if (token.isNotBlank() || tokenRef) return@runCatching true
                    }
                    false
                }.getOrElse { false }
                if (hasStoredProfile) return true
            }

            val authStatusCommand = "export NODE_OPTIONS='--require /root/.openclaw-patch.js' && " +
                "openclaw models status --json 2>&1"
            val authStatusOutput = prootManager.executeAndCapture(authStatusCommand)
            hasOpenClawModelsStatusAuth(authStatusOutput, "github-copilot")
        } catch (_: Exception) {
            false
        }
    }

    private fun detectCodexAuth(): Boolean {
        return try {
            // OpenClaw auth profiles
            val openClawAuthFile = File(prootManager.rootfsDir, "root/.openclaw/agents/main/agent/auth-profiles.json")
            if (openClawAuthFile.exists()) {
                runCatching {
                    val root = JSONObject(openClawAuthFile.readText())
                    val profiles = root.optJSONObject("profiles")
                    val profile = profiles?.optJSONObject("openai-codex:default")
                        ?: profiles?.optJSONObject("openai:default")
                    if (profile != null) {
                        val access = profile.optString("access", "")
                        val expires = profile.optLong("expires", 0L)
                        val hasValidExpiry = expires <= 0L || expires > System.currentTimeMillis()
                        if (access.isNotBlank() && hasValidExpiry) {
                            return true
                        }
                    }
                }
            }

            // Codex CLI auth file fallback
            val codexAuthFile = File(prootManager.rootfsDir, "root/.codex/auth.json")
            if (codexAuthFile.exists() && codexAuthFile.readText().contains("chatgpt.com")) {
                return true
            }

            // CLI 상태 조회 (느린 편이라 마지막 fallback)
            val authStatusCommand = "export NODE_OPTIONS='--require /root/.openclaw-patch.js' && " +
                "openclaw models status --json 2>&1"
            val authStatusOutput = prootManager.executeAndCapture(authStatusCommand)
            hasOpenClawModelsStatusAuth(authStatusOutput, "openai-codex")
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun exchangeAuthorizationCode(code: String, verifier: String): OAuthTokenResult {
        var lastError: Exception? = null

        for (attempt in 0..7) {
            try {
                return exchangeAuthorizationCodeOnce(OAUTH_TOKEN_URL_PRIMARY, code, verifier)
            } catch (e: Exception) {
                lastError = e
                val retryable = e is UnknownHostException || e is SocketTimeoutException
                if (!retryable || attempt >= 7) break
                val backoffMs = 500L * (attempt + 1)
                Log.w(TAG, "Token exchange retry after ${backoffMs}ms (${e.javaClass.simpleName})")
                delay(backoffMs)
            }
        }

        throw lastError ?: IllegalStateException("Token exchange failed")
    }

    private fun exchangeAuthorizationCodeOnce(
        tokenUrl: String,
        code: String,
        verifier: String,
    ): OAuthTokenResult {
        val conn = URL(tokenUrl).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val body = buildString {
                append("grant_type=authorization_code")
                append("&client_id=").append(urlEncode(OAUTH_CLIENT_ID))
                append("&code=").append(urlEncode(code))
                append("&code_verifier=").append(urlEncode(verifier))
                append("&redirect_uri=").append(urlEncode(OAUTH_REDIRECT_URI))
            }
            conn.outputStream.use { it.write(body.toByteArray()) }

            val status = conn.responseCode
            val raw = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (status !in 200..299) {
                val reason = raw.take(240)
                throw IllegalStateException("Token exchange failed: HTTP $status ${if (reason.isNotBlank()) "- $reason" else ""}".trim())
            }

            val json = JSONObject(raw)
            val access = json.optString("access_token", "")
            val refresh = json.optString("refresh_token", "")
            val expiresInSec = json.optLong("expires_in", -1)
            if (access.isBlank() || refresh.isBlank() || expiresInSec <= 0) {
                throw IllegalStateException("Token response missing fields")
            }

            OAuthTokenResult(
                access = access,
                refresh = refresh,
                expires = System.currentTimeMillis() + (expiresInSec * 1000L),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun extractAccountId(accessToken: String): String? {
        val payload = decodeJwt(accessToken) ?: return null
        val auth = payload.optJSONObject(OAUTH_JWT_CLAIM_PATH) ?: return null
        return auth.optString("chatgpt_account_id", "").ifBlank { null }
    }

    private fun decodeJwt(token: String): JSONObject? {
        return runCatching {
            val parts = token.split(".")
            if (parts.size != 3) return null
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP))
            JSONObject(payload)
        }.getOrNull()
    }

    private fun writeCodexOAuthCredentials(token: OAuthTokenResult, accountId: String) {
        val authFile = File(prootManager.rootfsDir, "root/.openclaw/agents/main/agent/auth-profiles.json")
        authFile.parentFile?.mkdirs()

        val root = if (authFile.exists()) {
            runCatching { JSONObject(authFile.readText()) }.getOrElse { JSONObject() }
        } else JSONObject()

        if (!root.has("version")) {
            root.put("version", 1)
        }
        val profiles = root.optJSONObject("profiles") ?: JSONObject().also { root.put("profiles", it) }

        val codexCredential = JSONObject().apply {
            put("type", "oauth")
            put("provider", "openai-codex")
            put("access", token.access)
            put("refresh", token.refresh)
            put("expires", token.expires)
            put("accountId", accountId)
        }
        val openAiCredential = JSONObject(codexCredential.toString()).apply {
            put("provider", "openai")
        }

        profiles.put("openai-codex:default", codexCredential)
        profiles.put("openai:default", openAiCredential)

        val lastGood = root.optJSONObject("lastGood") ?: JSONObject().also { root.put("lastGood", it) }
        lastGood.put("openai-codex", "openai-codex:default")
        lastGood.put("openai", "openai:default")

        authFile.writeText(root.toString(2))
    }

    private fun syncApiKeyAuthProfile(provider: String, apiKey: String) {
        val normalizedProvider = when (provider.lowercase()) {
            "openai-codex" -> "openai"
            "ollama-cloud" -> "ollama"
            else -> provider.lowercase()
        }
        if (normalizedProvider !in setOf("google", "openai", "anthropic", "openrouter", "openai-compatible", "zai", "kimi-coding", "minimax", "ollama")) return

        runCatching {
            val profileId = "$normalizedProvider:default"
            val authFile = File(prootManager.rootfsDir, "root/.openclaw/agents/main/agent/auth-profiles.json")
            authFile.parentFile?.mkdirs()

            val root = if (authFile.exists()) {
                runCatching { JSONObject(authFile.readText()) }.getOrElse { JSONObject() }
            } else {
                JSONObject()
            }

            if (!root.has("version")) {
                root.put("version", 1)
            }

            val profiles = root.optJSONObject("profiles") ?: JSONObject().also { root.put("profiles", it) }

            val normalizedKey = if (normalizedProvider == "ollama") {
                apiKey.ifBlank { "ollama-local" }
            } else {
                apiKey
            }

            if (normalizedKey.isBlank()) {
                profiles.remove(profileId)
                root.optJSONObject("lastGood")?.let { lastGood ->
                    if (lastGood.optString(normalizedProvider) == profileId) {
                        lastGood.remove(normalizedProvider)
                    }
                    if (lastGood.length() == 0) {
                        root.remove("lastGood")
                    }
                }
            } else {
                val apiKeyCredential = JSONObject().apply {
                    put("type", "api_key")
                    put("provider", normalizedProvider)
                    put("key", normalizedKey)
                }
                profiles.put(profileId, apiKeyCredential)

                val lastGood = root.optJSONObject("lastGood") ?: JSONObject().also { root.put("lastGood", it) }
                lastGood.put(normalizedProvider, profileId)
            }

            authFile.writeText(root.toString(2))
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to sync auth profile for provider $normalizedProvider", throwable)
        }
    }

    private fun ensureCodexPrimaryModel() {
        val configFile = File(prootManager.rootfsDir, "root/.openclaw/openclaw.json")
        if (!configFile.exists()) return

        runCatching {
            val availableCodexModels = resolveCodexModelsFromInstalledBundle()
            val availableCodexIds = availableCodexModels.map { it.id }
            val availableCodexModelsByNormalizedId = availableCodexModels.associateBy {
                normalizeCodexModelIdForComparison(it.id)
            }
            val availableScopedCodexIds = availableCodexIds.map { "openai-codex/$it" }.toSet()
            val availableLegacyScopedCodexIds = availableCodexIds.map { "openai/$it" }.toSet()
            val preferredPrimary = resolvePreferredCodexPrimaryModelFromInstalledBundle()
            val json = JSONObject(configFile.readText())
            val agents = json.optJSONObject("agents") ?: JSONObject().also { json.put("agents", it) }
            val defaults = agents.optJSONObject("defaults") ?: JSONObject().also { agents.put("defaults", it) }
            val model = defaults.optJSONObject("model") ?: JSONObject().also { defaults.put("model", it) }
            val currentPrimary = model.optString("primary").trim()
            val normalizedCurrentPrimary = when {
                currentPrimary in availableScopedCodexIds -> currentPrimary
                currentPrimary in availableLegacyScopedCodexIds -> "openai-codex/${normalizeCodexModelIdForComparison(currentPrimary)}"
                currentPrimary in availableCodexIds -> "openai-codex/$currentPrimary"
                else -> ""
            }
            val primaryToPersist = normalizedCurrentPrimary.ifBlank { preferredPrimary }
            model.put("primary", primaryToPersist)

            val models = defaults.optJSONObject("models") ?: JSONObject().also { defaults.put("models", it) }
            if (!models.has(primaryToPersist)) {
                val migratedLegacyModelConfig = if (currentPrimary in availableLegacyScopedCodexIds) {
                    models.optJSONObject(currentPrimary)?.let { JSONObject(it.toString()) }
                } else {
                    null
                }
                models.put(primaryToPersist, migratedLegacyModelConfig ?: JSONObject())
            }
            configFile.writeText(json.toString(2))

            val savedPrimary = runBlocking { prefs.getEffectivePrimary("openai-codex") }.orEmpty().trim()
            if (savedPrimary.isBlank()) {
                val barePrimaryId = normalizeCodexModelIdForComparison(primaryToPersist)
                val existingSelectedIds = runBlocking {
                    prefs.getSelectedModelIdsForProvider("openai-codex")
                }
                val selectedModelIds = buildList {
                    add(barePrimaryId)
                    addAll(existingSelectedIds)
                }.distinctBy(::normalizeCodexModelIdForComparison)
                val metadataById = selectedModelIds.mapNotNull { modelId ->
                    val installedModel = availableCodexModelsByNormalizedId[normalizeCodexModelIdForComparison(modelId)]
                        ?: return@mapNotNull null
                    modelId to SelectedModelConfigEntry(
                        id = modelId,
                        supportsReasoning = installedModel.supportsReasoning,
                        supportsImages = installedModel.supportsImages,
                        contextLength = installedModel.contextLength,
                        maxOutputTokens = installedModel.maxOutputTokens,
                    )
                }.toMap()
                runBlocking {
                    prefs.setSelectedModelIdsWithoutActivatingProvider(
                        provider = "openai-codex",
                        modelIds = selectedModelIds,
                        primary = barePrimaryId,
                        metadataById = metadataById,
                    )
                }
            }
        }
    }

    private fun generateCodeVerifier(): String {
        val random = ByteArray(32)
        SecureRandom().nextBytes(random)
        return Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateCodeChallenge(verifier: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun generateState(): String {
        val random = ByteArray(16)
        SecureRandom().nextBytes(random)
        return random.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun parseQueryString(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split("&")
            .mapNotNull { part ->
                val key = part.substringBefore("=", missingDelimiterValue = "").trim()
                if (key.isBlank()) return@mapNotNull null
                val value = part.substringAfter("=", missingDelimiterValue = "")
                key to URLDecoder.decode(value, "UTF-8")
            }
            .toMap()
    }

    private fun urlEncode(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8")
    }

    // ── WhatsApp QR ──

    private fun hasWhatsAppCredsFile(): Boolean {
        val rootfs = prootManager.rootfsDir ?: return false
        val whatsappCredsRoot = File(rootfs, "root/.openclaw/credentials/whatsapp")
        if (!whatsappCredsRoot.exists()) return false

        return whatsappCredsRoot.walkTopDown()
            .maxDepth(4)
            .any { file ->
                file.isFile &&
                    file.name == "creds.json" &&
                    file.length() > 1
            }
    }

    /**
     * 설정 화면의 "연결됨" 뱃지에 반영할 실제 링크 상태를 갱신한다.
     *
     * 중요:
     * - creds 파일 존재 여부가 아니라 gateway가 계산한 snapshot.connected를 기준으로 삼는다.
     * - 그래서 "파일은 남아있는데 실제 미연결"인 케이스를 줄일 수 있다.
     */
    private suspend fun refreshWhatsAppLinkStateInternal() {
        val snapshot = runCatching {
            val client = GatewayWsClient(prootManager)
            try {
                client.getWhatsAppChannelSnapshot(probe = false)
            } finally {
                client.close()
            }
        }.getOrNull()
        val linkedByCreds = runCatching { hasWhatsAppCredsFile() }.getOrDefault(false)
        val linkedSnapshot = when {
            snapshot?.connected == true -> true
            snapshot?.linked != null -> snapshot.linked == true
            snapshot?.connected != null -> snapshot.connected == true
            else -> null
        }
        _isWhatsAppLinked.value = linkedSnapshot ?: linkedByCreds
    }

    /**
     * 재시작 직후/경계 상태에서 즉시 실패 판정하지 않기 위한 grace polling.
     *
     * 설계 의도:
     * 1) 짧은 간격으로 상태를 재확인해서 일시적인 false/null을 흡수
     * 2) requiredConnectedCount를 통해 "연속 connected"가 잡힐 때만 성공 처리
     * 3) 각 probe 호출은 withTimeoutOrNull로 상한을 둬서 루프가 과도하게 블로킹되지 않게 함
     */
    private suspend fun awaitWhatsAppConnectedWithGrace(
        client: GatewayWsClient,
        timeoutMs: Long = 18_000L,
        intervalMs: Long = 1_500L,
        requiredConnectedCount: Int = WHATSAPP_STABLE_CONNECTED_REQUIRED_COUNT,
        probe: Boolean = true,
        maxProbeTimeoutMs: Long = 4_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var consecutiveConnected = 0
        while (System.currentTimeMillis() < deadline) {
            val remainingMs = deadline - System.currentTimeMillis()
            if (remainingMs <= 0L) break
            // 전체 grace window를 넘지 않도록 남은 시간과 per-probe 상한 중 작은 값 사용
            val probeTimeoutMs = minOf(remainingMs, maxProbeTimeoutMs)
            val connected = withTimeoutOrNull(probeTimeoutMs) {
                withContext(Dispatchers.IO) {
                    client.isWhatsAppChannelConnected(
                        probe = probe,
                        statusTimeoutMs = probeTimeoutMs,
                    )
                }
            }
            if (connected == true) {
                consecutiveConnected += 1
                // 연속 성공 횟수 충족 시에만 true
                if (consecutiveConnected >= requiredConnectedCount) return true
            } else {
                // 중간에 false/null이면 streak 리셋
                consecutiveConnected = 0
            }
            delay(intervalMs)
        }
        return false
    }

    /**
     * restart 호출 후 gateway 상태머신이 실제로 다시 올라왔는지 대기한다.
     *
     * 기본 규칙:
     * - requireTransition=false: RUNNING이면 즉시 ready
     * - requireTransition=true: STARTING/STOPPED/ERROR 등 전이 신호를 한 번 본 뒤 RUNNING일 때 ready
     *
     * fallback:
     * - 일부 디바이스/타이밍에서는 전이 이벤트를 못 보고 RUNNING만 보일 수 있어,
     *   requireTransition=true여도 3초 이상 RUNNING 지속 시 fallback ready 허용
     */
    private suspend fun waitForGatewayRestartReady(
        timeoutMs: Long = WHATSAPP_GATEWAY_RESTART_READY_TIMEOUT_MS,
        intervalMs: Long = WHATSAPP_GATEWAY_RESTART_RETRY_INTERVAL_MS,
        requireTransition: Boolean = false,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        val deadline = System.currentTimeMillis() + timeoutMs
        var sawRestartTransition = !requireTransition
        while (System.currentTimeMillis() < deadline) {
            when (processManager.gatewayState.value.status) {
                GatewayStatus.RUNNING -> {
                    if (sawRestartTransition) return true
                    val elapsedMs = System.currentTimeMillis() - startedAt
                    if (requireTransition && elapsedMs >= 3_000L) {
                        Log.i(
                            WHATSAPP_LOG_TAG,
                            "restart ready fallback: still RUNNING after ${elapsedMs}ms without observed transition; proceeding.",
                        )
                        return true
                    }
                }
                GatewayStatus.STARTING -> sawRestartTransition = true
                GatewayStatus.STOPPED, GatewayStatus.STOPPING, GatewayStatus.ERROR -> {
                    sawRestartTransition = true
                }
            }
            delay(intervalMs)
        }
        return false
    }

    private fun clearWhatsAppCredsOffline(): Boolean {
        val rootfs = prootManager.rootfsDir ?: return false
        val whatsappCredsRoot = File(rootfs, "root/.openclaw/credentials/whatsapp")
        if (!whatsappCredsRoot.exists()) return true
        return runCatching { whatsappCredsRoot.deleteRecursively() }.getOrDefault(false)
    }

    fun refreshWhatsAppLinkState() {
        if (whatsappLinkRefreshJob?.isActive == true) return
        if (whatsappQrJob?.isActive == true) return
        when (_whatsappQrState.value) {
            is WhatsAppQrState.Loading,
            is WhatsAppQrState.Waiting,
            is WhatsAppQrState.QrReady,
            -> return
            else -> Unit
        }
        whatsappLinkRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                refreshWhatsAppLinkStateInternal()
            } finally {
                whatsappLinkRefreshJob = null
            }
        }
    }

    fun disconnectChannel(channelId: String, channelLabel: String) {
        if (_isChannelDisconnecting.value) return

        _isChannelDisconnecting.value = true
        _disconnectingChannelLabel.value = channelLabel
        _channelDisconnectError.value = null

        viewModelScope.launch {
            try {
                when (channelId.trim().lowercase()) {
                    "whatsapp" -> {
                        val client = GatewayWsClient(prootManager)
                        try {
                            val logoutSuccess = withContext(Dispatchers.IO) { client.logoutChannel("whatsapp") }
                            var success = logoutSuccess
                            if (!logoutSuccess) {
                                val status = processManager.gatewayState.value.status
                                val gatewayActive =
                                    status == GatewayStatus.RUNNING || status == GatewayStatus.STARTING
                                val cleared = withContext(Dispatchers.IO) { clearWhatsAppCredsOffline() }
                                if (cleared) {
                                    Log.i(
                                        WHATSAPP_LOG_TAG,
                                        "logoutChannel failed; cleared WhatsApp creds as fallback (gatewayActive=$gatewayActive)",
                                    )
                                }
                                success = cleared
                            }

                            if (!success) {
                                val reason = client.getLastCallErrorMessage()
                                _channelDisconnectError.value = reason ?: "Failed to disconnect WhatsApp"
                                return@launch
                            }
                            cancelWhatsAppQr()
                            restartGatewayIfRunning(delayMs = 0L, source = "settings:whatsapp_disconnect_restart")
                            withContext(Dispatchers.IO) {
                                refreshWhatsAppLinkStateInternal()
                            }
                        } finally {
                            client.close()
                        }
                    }
                    "telegram" -> {
                        withContext(Dispatchers.IO) {
                            prefs.setTelegramBotToken("")
                            prefs.setTelegramEnabled(false)
                            applyChannelConfigAndRestart()
                        }
                    }
                    "discord" -> {
                        withContext(Dispatchers.IO) {
                            prefs.setDiscordBotToken("")
                            prefs.setDiscordEnabled(false)
                            prefs.setDiscordGuildAllowlist("")
                            applyChannelConfigAndRestart()
                        }
                    }
                    else -> {
                        _channelDisconnectError.value = "Unsupported channel: $channelId"
                        return@launch
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _channelDisconnectError.value = e.message ?: "Failed to disconnect channel"
            } finally {
                _isChannelDisconnecting.value = false
                _disconnectingChannelLabel.value = null
            }
        }
    }

    fun disconnectWhatsApp() {
        disconnectChannel(channelId = "whatsapp", channelLabel = "WhatsApp")
    }

    fun consumeChannelDisconnectError() {
        _channelDisconnectError.value = null
    }

    fun startWhatsAppQr() {
        // 이미 로그인 잡이 돌고 있으면 중복 진입 방지
        if (whatsappQrJob?.isActive == true) return
        val qrClickStartedAt = SystemClock.elapsedRealtime()
        Log.i(WHATSAPP_LOG_TAG, "QR connect tapped; starting login flow")
        // 일반 상태 polling과 로그인 흐름이 겹치면 상태 오판정/락 대기가 생기므로 중지
        whatsappLinkRefreshJob?.cancel()
        whatsappLinkRefreshJob = null
        // Settings / Dashboard 동시 로그인 시도 방지(전역 가드)
        if (!WhatsAppLoginCoordinator.tryAcquire()) {
            _whatsappQrState.value = WhatsAppQrState.Error(
                "WhatsApp login is already running in another screen. Please wait."
            )
            return
        }
        isWhatsAppLoginCoordinatorOwner = true
        whatsappQrJob?.cancel()
        _whatsappQrState.value = WhatsAppQrState.Loading
        whatsappQrJob = viewModelScope.launch {
            val appContext = getApplication<Application>().applicationContext
            var keepSessionOpen = false
            try {
                val client = GatewayWsClient(prootManager)
                wsClient = client
                var qrAttempt = 0

                // 1) stale creds 정리
                // - gateway가 꺼져 있을 때만 기본 purge 시도
                // - gateway가 켜져 있으면 건너뛰고 이후 단계에서 필요 시 강제 purge
                val gatewayActive =
                    processManager.gatewayState.value.status == GatewayStatus.RUNNING ||
                        processManager.gatewayState.value.status == GatewayStatus.STARTING
                // 복구 단계에서 "원래 꺼져있던 gateway를 강제로 켜는" 부작용을 막기 위해
                // 로그인 시작 시점의 활성 상태를 저장한다.
                wasGatewayActiveAtWhatsAppLoginStart = gatewayActive
                if (!gatewayActive) {
                    val purgeStartedAt = SystemClock.elapsedRealtime()
                    val purgedStaleCreds = withContext(Dispatchers.IO) {
                        client.purgeStaleWhatsAppCredsIfNeeded()
                    }
                    Log.i(
                        WHATSAPP_LOG_TAG,
                        "stale creds purge elapsed=${SystemClock.elapsedRealtime() - purgeStartedAt}ms purged=$purgedStaleCreds",
                    )
                } else {
                    Log.i(
                        WHATSAPP_LOG_TAG,
                        "stale creds purge skipped (gateway active)",
                    )
                }

                // 2) 로그인 가드(FGS) 시작
                // - 로그인 도중 프로세스가 쉽게 죽지 않도록 guard 유지
                startWhatsAppLoginGuardKeepAlive(appContext)

                // 3) flapping/충돌 상태 격리 시도 (짧은 상한)
                val isolationStartedAt = SystemClock.elapsedRealtime()
                val isolated = withTimeoutOrNull(1_500L) {
                    withContext(Dispatchers.IO) {
                        client.ensureWhatsAppLoginIsolation()
                    }
                } ?: false
                Log.i(
                    WHATSAPP_LOG_TAG,
                    "login isolation elapsed=${SystemClock.elapsedRealtime() - isolationStartedAt}ms isolated=$isolated",
                )
                if (isolated) {
                    delay(500)
                }

                // 4) 기본 QR 발급 시도
                Log.i(WHATSAPP_LOG_TAG, "web.login.start request begin")
                val loginStartCallAt = SystemClock.elapsedRealtime()
                var qrData = withContext(Dispatchers.IO) {
                    client.startWhatsAppLogin(timeoutMs = WHATSAPP_QR_START_TIMEOUT_MS)
                }
                Log.i(
                    WHATSAPP_LOG_TAG,
                    "web.login.start request end elapsed=${SystemClock.elapsedRealtime() - loginStartCallAt}ms success=${qrData != null}",
                )
                if (qrData == null) {
                    val reason = client.getLastCallErrorMessage()
                    val qrTimeoutLikely = reason?.contains(
                        "timed out waiting for whatsapp qr",
                        ignoreCase = true,
                    ) == true
                    if (qrTimeoutLikely) {
                        // 4-a) QR timeout이면 강제 정리 + relink 경로로 복구 시도
                        Log.i(
                            WHATSAPP_LOG_TAG,
                            "start login returned QR timeout; forcing stale creds purge + relink flow.",
                        )
                        val purged = withContext(Dispatchers.IO) {
                            client.purgeStaleWhatsAppCredsIfNeeded(force = true)
                        }
                        if (purged) {
                            val status = processManager.gatewayState.value.status
                            val activeNow =
                                status == GatewayStatus.RUNNING || status == GatewayStatus.STARTING
                            if (activeNow) {
                                // gateway가 이미 떠 있으면 강제 restart로 세션 꼬임 해소
                                restartGatewayForWhatsAppRecovery()
                                val restartReady = waitForGatewayRestartReady(
                                    timeoutMs = 30_000L,
                                    intervalMs = 500L,
                                    requireTransition = true,
                                )
                                if (!restartReady) {
                                    _whatsappQrState.value = WhatsAppQrState.Error(
                                        "Gateway restart timed out while forcing relink. Please try again."
                                    )
                                    return@launch
                                }
                            }
                        }
                        Log.i(WHATSAPP_LOG_TAG, "web.login.start(force=true) request begin [qr-timeout recovery]")
                        val forceCallAt = SystemClock.elapsedRealtime()
                        qrData = withContext(Dispatchers.IO) {
                            client.startWhatsAppLogin(force = true, timeoutMs = WHATSAPP_QR_FORCE_TIMEOUT_MS)
                        }
                        Log.i(
                            WHATSAPP_LOG_TAG,
                            "web.login.start(force=true) request end elapsed=${SystemClock.elapsedRealtime() - forceCallAt}ms success=${qrData != null}",
                        )
                    }
                }
                if (qrData == null) {
                    if (client.isLastCallWhatsAppAlreadyLinked()) {
                        // 5) "already linked" 응답: 즉시 force relink 1차 시도
                        Log.i(WHATSAPP_LOG_TAG, "web.login.start returned already linked; forcing relink QR.")
                        Log.i(WHATSAPP_LOG_TAG, "web.login.start(force=true) request begin")
                        val forceCallAt = SystemClock.elapsedRealtime()
                        qrData = withContext(Dispatchers.IO) {
                            client.startWhatsAppLogin(force = true, timeoutMs = WHATSAPP_QR_FORCE_TIMEOUT_MS)
                        }
                        Log.i(
                            WHATSAPP_LOG_TAG,
                            "web.login.start(force=true) request end elapsed=${SystemClock.elapsedRealtime() - forceCallAt}ms success=${qrData != null}",
                        )
                        if (qrData == null) {
                            val forceReason = client.getLastCallErrorMessage().orEmpty()
                            val forceQrTimeoutLikely = forceReason.contains(
                                "timed out waiting for whatsapp qr",
                                ignoreCase = true,
                            )
                            if (forceQrTimeoutLikely) {
                                // 5-a) force relink도 QR timeout이면 purge + restart 후 force 재시도
                                Log.i(
                                    WHATSAPP_LOG_TAG,
                                    "force relink timed out for QR; purging creds + gateway restart + force retry.",
                                )
                                val purgedLinkedCreds = withContext(Dispatchers.IO) {
                                    client.purgeStaleWhatsAppCredsIfNeeded(force = true)
                                }
                                if (purgedLinkedCreds) {
                                    val status = processManager.gatewayState.value.status
                                    val activeNow =
                                        status == GatewayStatus.RUNNING || status == GatewayStatus.STARTING
                                    if (activeNow) {
                                        restartGatewayForWhatsAppRecovery()
                                        val restartReady = waitForGatewayRestartReady(
                                            timeoutMs = 30_000L,
                                            intervalMs = 500L,
                                            requireTransition = true,
                                        )
                                        if (!restartReady) {
                                            _whatsappQrState.value = WhatsAppQrState.Error(
                                                "Gateway restart timed out while forcing relink. Please try again."
                                            )
                                            return@launch
                                        }
                                    }
                                }
                                Log.i(WHATSAPP_LOG_TAG, "web.login.start(force=true) request begin [after purge+restart]")
                                val forceRetryAt = SystemClock.elapsedRealtime()
                                qrData = withContext(Dispatchers.IO) {
                                    client.startWhatsAppLogin(force = true, timeoutMs = WHATSAPP_QR_FORCE_TIMEOUT_MS)
                                }
                                Log.i(
                                    WHATSAPP_LOG_TAG,
                                    "web.login.start(force=true) request end elapsed=${SystemClock.elapsedRealtime() - forceRetryAt}ms success=${qrData != null} [after purge+restart]",
                                )
                            }
                        }
                        if (qrData == null) {
                            // 5-b) relink QR은 못 받았지만 이미 connected 상태일 수 있으니 마지막 확인
                            val connected = withContext(Dispatchers.IO) {
                                client.isWhatsAppChannelConnected(probe = false) == true
                            }
                            if (connected) {
                                _whatsappQrState.value = WhatsAppQrState.Connected
                                delay(1500)
                                _whatsappQrState.value = WhatsAppQrState.Idle
                                withContext(Dispatchers.IO) {
                                    refreshWhatsAppLinkStateInternal()
                                }
                                return@launch
                            }
                        }
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
                    return@launch
                }

                val isDataUrl = qrData.startsWith("data:image/")
                qrAttempt += 1
                val issuedAtMs = System.currentTimeMillis()
                // 6) QR 표시 후, SettingsScreen의 LaunchedEffect가 자동으로 confirmWhatsAppQrScanned() 호출
                _whatsappQrState.value = WhatsAppQrState.QrReady(
                    qrData = qrData,
                    isDataUrl = isDataUrl,
                    attempt = qrAttempt,
                    issuedAtMs = issuedAtMs,
                    expiresAtMs = issuedAtMs + WHATSAPP_QR_EXPIRES_MS,
                )
                Log.i(
                    WHATSAPP_LOG_TAG,
                    "QR ready; total elapsed since tap=${SystemClock.elapsedRealtime() - qrClickStartedAt}ms",
                )
                keepSessionOpen = true
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _whatsappQrState.value = WhatsAppQrState.Error(e.message ?: "Unknown error")
            } finally {
                whatsappQrJob = null
                if (!keepSessionOpen) {
                    finishWhatsAppLoginSession()
                }
            }
        }
    }

    /**
     * QR 표시 후 실제 login.wait / 515 복구 / 최종 판정을 수행한다.
     *
     * 현재 판정 흐름:
     * - wait 최대 N회
     * - 515/restart-required 감지 시 즉시 restart 복구 분기
     * - restart 이후 즉시 확인 + grace polling + final probe + final snapshot 순서로 확인
     * - 그래도 실패면 Error로 종료
     */
    fun confirmWhatsAppQrScanned() {
        if (whatsappQrJob?.isActive == true) return
        if (_whatsappQrState.value !is WhatsAppQrState.QrReady) return

        val client = wsClient
        if (client == null) {
            finishWhatsAppLoginSession()
            _whatsappQrState.value = WhatsAppQrState.Error("Login session expired. Please generate a new QR code.")
            return
        }

        whatsappQrJob = viewModelScope.launch {
            try {
                var success = false
                var lastWaitReason: String? = null
                var restartImmediately = false
                // 1) web.login.wait 단계
                for (attempt in 0 until WHATSAPP_WAIT_MAX_ATTEMPTS) {
                    val waitStartedAt = SystemClock.elapsedRealtime()
                    val waitResult = withContext(Dispatchers.IO) {
                        client.waitWhatsAppLogin(timeoutMs = WHATSAPP_WAIT_TIMEOUT_MS)
                    }
                    val waitElapsedMs = SystemClock.elapsedRealtime() - waitStartedAt
                    val waitMessage = client.getLastCallGatewayMessage()
                    if (!waitMessage.isNullOrBlank()) {
                        Log.i(
                            WHATSAPP_LOG_TAG,
                            "web.login.wait attempt=${attempt + 1} elapsed=${waitElapsedMs}ms connected=$waitResult message=$waitMessage",
                        )
                    }
                    if (waitResult) {
                        // web.login.wait=true는 이미 로그인 완료 신호로 간주한다.
                        // channels.status 반영이 몇 초 늦을 수 있으므로 strict gate로 쓰지 않는다.
                        success = true
                        val connectedHint = withTimeoutOrNull(5_000L) {
                            withContext(Dispatchers.IO) {
                                client.isWhatsAppChannelConnected(
                                    probe = false,
                                    statusTimeoutMs = 5_000L,
                                ) == true
                            }
                        }
                        if (connectedHint != true) {
                            Log.i(
                                WHATSAPP_LOG_TAG,
                                "web.login.wait attempt=${attempt + 1} succeeded; accepting success without strict status gate.",
                            )
                        }
                        break
                    }
                    lastWaitReason = client.getLastCallErrorMessage()
                    Log.i(
                        WHATSAPP_LOG_TAG,
                        "web.login.wait attempt=${attempt + 1} failed elapsed=${waitElapsedMs}ms reason=${lastWaitReason ?: "unknown"}",
                    )
                    if (client.isLastCallWhatsAppRestartRequired()) {
                        // 515 계열은 추가 wait 반복보다 restart 복구가 우선
                        restartImmediately = true
                        Log.i(
                            WHATSAPP_LOG_TAG,
                            "515 detected on wait attempt=${attempt + 1}; restart immediately.",
                        )
                        break
                    }
                }
                if (success) {
                    _whatsappQrState.value = WhatsAppQrState.Connected
                    delay(1500)
                    _whatsappQrState.value = WhatsAppQrState.Idle
                } else {
                    // 2) restart 복구 단계
                    _whatsappQrState.value = WhatsAppQrState.Waiting
                    Log.i(
                        WHATSAPP_LOG_TAG,
                        if (restartImmediately) {
                            "restart required detected; restarting gateway for recovery."
                        } else {
                            "wait failed ${WHATSAPP_WAIT_MAX_ATTEMPTS} times; restarting gateway for recovery."
                        },
                    )
                    var restartAttempted = false
                    var restartReady = false
                    if (wasGatewayActiveAtWhatsAppLoginStart) {
                        restartAttempted = true
                        restartGatewayForWhatsAppRecovery()
                        restartReady = waitForGatewayRestartReady(
                            timeoutMs = 30_000L,
                            intervalMs = 500L,
                            requireTransition = true,
                        )
                    } else {
                        Log.i(
                            WHATSAPP_LOG_TAG,
                            "skip restart recovery: gateway was not active at login start.",
                        )
                    }
                    if (restartReady) {
                        val connectedImmediatelyAfterRestart = withContext(Dispatchers.IO) {
                            client.isWhatsAppChannelConnected(probe = false) == true
                        }
                        if (connectedImmediatelyAfterRestart) {
                            Log.i(
                                WHATSAPP_LOG_TAG,
                                "Linked immediately after gateway restart.",
                            )
                            _whatsappQrState.value = WhatsAppQrState.Connected
                            delay(1500)
                            _whatsappQrState.value = WhatsAppQrState.Idle
                            return@launch
                        }

                        // restart 직후 즉시 true를 못 잡더라도 일정 시간 grace polling
                        val recoveredAfterRestart = awaitWhatsAppConnectedWithGrace(
                            client = client,
                            timeoutMs = 25_000L,
                            intervalMs = 1_000L,
                            requiredConnectedCount = 1,
                            probe = false,
                            maxProbeTimeoutMs = 20_000L,
                        )
                        if (recoveredAfterRestart) {
                            Log.i(
                                WHATSAPP_LOG_TAG,
                                "Linked after gateway restart recovery.",
                            )
                            _whatsappQrState.value = WhatsAppQrState.Connected
                            delay(1500)
                            _whatsappQrState.value = WhatsAppQrState.Idle
                            return@launch
                        }
                    }

                    // 3) restart ready timeout이어도 false negative 방지를 위해 final probe를 더 길게 수행
                    val finalProbeTimeoutMs = if (restartAttempted && restartReady) 20_000L else 45_000L
                    val finalProbePerCallTimeoutMs = if (restartAttempted && restartReady) 20_000L else 35_000L
                    val recoveredByFinalProbe = awaitWhatsAppConnectedWithGrace(
                        client = client,
                        timeoutMs = finalProbeTimeoutMs,
                        intervalMs = 1_000L,
                        requiredConnectedCount = 1,
                        probe = false,
                        maxProbeTimeoutMs = finalProbePerCallTimeoutMs,
                    )
                    if (recoveredByFinalProbe) {
                        Log.i(
                            WHATSAPP_LOG_TAG,
                            "Linked after final probe verification post-restart.",
                        )
                        _whatsappQrState.value = WhatsAppQrState.Connected
                        delay(1500)
                        _whatsappQrState.value = WhatsAppQrState.Idle
                        return@launch
                    }

                    // 4) 마지막 단발 snapshot 확인(최종 false negative 방지)
                    val finalSnapshotConnected = withTimeoutOrNull(finalProbePerCallTimeoutMs) {
                        withContext(Dispatchers.IO) {
                            client.isWhatsAppChannelConnected(
                                probe = false,
                                statusTimeoutMs = finalProbePerCallTimeoutMs,
                            ) == true
                        }
                    } == true
                    if (finalSnapshotConnected) {
                        Log.i(
                            WHATSAPP_LOG_TAG,
                            "Linked on final snapshot check after recovery path.",
                        )
                        _whatsappQrState.value = WhatsAppQrState.Connected
                        delay(1500)
                        _whatsappQrState.value = WhatsAppQrState.Idle
                        return@launch
                    }

                    val reason = client.getLastCallErrorMessage() ?: lastWaitReason
                    val message = if (restartAttempted && !restartReady) {
                        "Gateway restart timed out. Please try again."
                    } else if (reason.isNullOrBlank()) {
                        if (restartAttempted) {
                            "Login failed after gateway restart."
                        } else {
                            "Login failed. Please try again."
                        }
                    } else {
                        if (restartAttempted) {
                            "Login failed after gateway restart: $reason"
                        } else {
                            "Login failed: $reason"
                        }
                    }
                    _whatsappQrState.value = WhatsAppQrState.Error(message)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _whatsappQrState.value = WhatsAppQrState.Error(e.message ?: "Unknown error")
            } finally {
                whatsappQrJob = null
                // 재시도 차단(코디네이터/가드 락)을 최소화하기 위해 세션 정리를 먼저 수행한다.
                finishWhatsAppLoginSession()
                withContext(NonCancellable + Dispatchers.IO) {
                    val refreshed = withTimeoutOrNull(4_000L) {
                        refreshWhatsAppLinkStateInternal()
                        true
                    } ?: false
                    if (!refreshed) {
                        Log.i(
                            WHATSAPP_LOG_TAG,
                            "post-login link-state refresh timed out; session already released.",
                        )
                    }
                }
            }
        }
    }

    /**
     * WhatsApp 로그인 세션 정리 공통 함수.
     * - WS client close
     * - login guard(FGS) 중지
     * - 전역 coordinator release
     */
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
        wasGatewayActiveAtWhatsAppLoginStart = false
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
                if (!isActive) {
                    break
                }
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

    fun cancelWhatsAppQr() {
        whatsappQrJob?.cancel()
        whatsappQrJob = null
        finishWhatsAppLoginSession()
        _whatsappQrState.value = WhatsAppQrState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        cancelWhatsAppQr()
        clearTransferExportPassword()
        clearTransferImportPassword()
    }

    fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(getApplication<Application>().packageName)
    }

    fun requestBatteryOptimizationExemption(): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${getApplication<Application>().packageName}")
        }
    }
}

internal fun canRestartGatewayForRuntimeLaunch(
    launchConfig: com.coderred.andclaw.data.GatewayLaunchConfigSnapshot?,
    hasCodexAuth: Boolean,
): Boolean {
    return when {
        launchConfig == null -> false
        launchConfig.selectedModelEntries.isEmpty() -> false
        launchConfig.apiProvider == "ollama" -> true
        launchConfig.apiProvider == "openai-compatible" ->
            launchConfig.apiKey.isNotBlank() ||
                PreferencesManager.isKnownKeylessOpenAiCompatibleBaseUrl(
                    launchConfig.openAiCompatibleBaseUrl,
                )
        launchConfig.apiProvider == "openai-codex" -> hasCodexAuth
        else -> launchConfig.apiKey.isNotBlank()
    }
}

internal enum class RuntimeLaunchConfigChangeAction {
    NONE,
    RESTART,
    STOP,
}

internal fun resolveRuntimeLaunchConfigChangeAction(
    launchConfig: com.coderred.andclaw.data.GatewayLaunchConfigSnapshot?,
    hasCodexAuth: Boolean,
): RuntimeLaunchConfigChangeAction {
    return when {
        launchConfig == null -> RuntimeLaunchConfigChangeAction.NONE
        launchConfig.selectedModelEntries.isEmpty() -> RuntimeLaunchConfigChangeAction.STOP
        canRestartGatewayForRuntimeLaunch(launchConfig, hasCodexAuth) -> RuntimeLaunchConfigChangeAction.RESTART
        else -> RuntimeLaunchConfigChangeAction.STOP
    }
}

internal fun shouldPromptForRuntimeLaunchConfigChange(
    launchConfig: com.coderred.andclaw.data.GatewayLaunchConfigSnapshot?,
    hasCodexAuth: Boolean,
): Boolean {
    return resolveRuntimeLaunchConfigChangeAction(launchConfig, hasCodexAuth) != RuntimeLaunchConfigChangeAction.NONE
}

internal fun resolveSelectionChangePrimaryDirective(
    provider: String,
    appliedModelIds: List<String>,
    currentSelectedModelIds: List<String>,
    globalPrimaryModelId: String,
    globalPrimaryProvider: String,
    openAiCompatibleModelId: String,
    ollamaModelId: String = "",
): String? {
    val normalizedProvider = provider.trim().lowercase()
    val normalize: (String) -> String = { modelId ->
        when (normalizedProvider) {
            "openai-compatible" -> modelId.trim().removePrefix("openai-compatible/")
            "ollama", "ollama-cloud" -> modelId.trim().removePrefix("ollama/").removePrefix("ollama-cloud/").removeSuffix(":latest")
            else -> modelId.trim()
        }
    }
    val normalizedAppliedIds = appliedModelIds.map(normalize).filter { it.isNotBlank() }.toSet()
    val normalizedCurrentIds = currentSelectedModelIds.map(normalize).filter { it.isNotBlank() }.toSet()
    if (normalizedAppliedIds == normalizedCurrentIds) return null

    val normalizedGlobalPrimary = normalize(globalPrimaryModelId)
    val globalPrimaryRemoved =
        globalPrimaryProvider.trim().lowercase() == normalizedProvider &&
            normalizedGlobalPrimary.isNotBlank() &&
            normalizedCurrentIds.contains(normalizedGlobalPrimary) &&
            !normalizedAppliedIds.contains(normalizedGlobalPrimary)
    if (globalPrimaryRemoved) return ""

    val compatPrimary = openAiCompatibleModelId.trim().removePrefix("openai-compatible/")
    val compatPrimaryRemoved =
        normalizedProvider == "openai-compatible" &&
            compatPrimary.isNotBlank() &&
            normalizedCurrentIds.contains(compatPrimary) &&
            !normalizedAppliedIds.contains(compatPrimary)
    if (compatPrimaryRemoved) return ""

    val currentOllamaPrimary = ollamaModelId.trim().removePrefix("ollama/").removePrefix("ollama-cloud/")
    val ollamaPrimaryRemoved =
        (normalizedProvider == "ollama" || normalizedProvider == "ollama-cloud") &&
            currentOllamaPrimary.isNotBlank() &&
            normalizedCurrentIds.contains(currentOllamaPrimary) &&
            !normalizedAppliedIds.contains(currentOllamaPrimary)
    return if (ollamaPrimaryRemoved) "" else null
}
