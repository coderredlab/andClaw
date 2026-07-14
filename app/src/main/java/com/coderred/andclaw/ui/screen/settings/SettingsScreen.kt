@file:Suppress("PackageDirectoryMismatch", "FunctionName")

package com.coderred.andclaw.ui.screen.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coderred.andclaw.BuildConfig
import com.coderred.andclaw.R
import com.coderred.andclaw.data.BugReportEmailIntentBuilder
import com.coderred.andclaw.data.SetupStep
import com.coderred.andclaw.data.BugReportEmailMetadata
import com.coderred.andclaw.data.BugReportEmailSummary
import com.coderred.andclaw.data.OpenAiConnectionMode
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.data.transfer.TransferSizeLimits
import com.coderred.andclaw.proroot.ExecutionRuntime
import com.coderred.andclaw.proroot.OpenClawModelCatalogReader
import com.coderred.andclaw.ui.component.DefaultModelDialogOption
import com.coderred.andclaw.ui.component.DefaultModelSelectionDialog
import com.coderred.andclaw.ui.component.KeepScreenOnEffect
import com.coderred.andclaw.ui.component.ModelSelectionDialog
import com.coderred.andclaw.ui.component.WhatsAppQrDialog
import com.coderred.andclaw.ui.screen.dashboard.WhatsAppQrState
import com.coderred.andclaw.service.OpenAiConnectionTransitionFailure
import com.coderred.andclaw.service.OpenAiConnectionTransitionPhase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal const val SETTINGS_SECTION_TOOLS = "tools"
private const val CODEX_AUTH_DEBUG_DIALOG_AUTO_OPEN_THRESHOLD = 180
private const val SHOW_CODEX_AUTH_MAINTENANCE_ACTIONS = false

internal val SETTINGS_PROVIDER_IDS = listOf(
    "openrouter",
    "anthropic",
    "openai",
    "github-copilot",
    "google",
    "zai",
    "kimi-coding",
    "minimax",
    "ollama",
    "ollama-cloud",
    "openai-compatible",
)

internal enum class ProviderSelectionUiAction {
    OPEN_OPENAI_CONNECTION_MODE_CHOOSER,
    APPLY_PROVIDER,
}

internal enum class OpenAiCredentialUiStatus {
    CONFIGURED,
    NOT_CONFIGURED,
    IN_PROGRESS,
}

internal data class OpenAiConnectionModeUiItem(
    val mode: OpenAiConnectionMode,
    val credentialStatus: OpenAiCredentialUiStatus,
    val isCurrent: Boolean,
)

internal enum class OpenAiModeSelectionUiEffect {
    NONE,
    SHOW_RESTART_CONFIRMATION,
    START_CODEX_LOGIN,
    SHOW_API_KEY_DIALOG,
}

internal fun resolveInitialSettingsTabIndex(initialSection: String?): Int {
    return when (initialSection) {
        SETTINGS_SECTION_TOOLS -> 3
        else -> 0
    }
}

internal fun canonicalSettingsProviderId(provider: String): String =
    OpenClawModelCatalogReader.canonicalProvider(provider)

internal fun resolveProviderSelectionUiAction(provider: String): ProviderSelectionUiAction =
    if (canonicalSettingsProviderId(provider) == "openai") {
        ProviderSelectionUiAction.OPEN_OPENAI_CONNECTION_MODE_CHOOSER
    } else {
        ProviderSelectionUiAction.APPLY_PROVIDER
    }

internal fun buildOpenAiConnectionModeUiItems(
    activeMode: OpenAiConnectionMode,
    isCodexAuthenticated: Boolean,
    isCodexAuthInProgress: Boolean,
    isApiKeyConfigured: Boolean,
): List<OpenAiConnectionModeUiItem> = listOf(
    OpenAiConnectionModeUiItem(
        mode = OpenAiConnectionMode.CODEX_SUBSCRIPTION,
        credentialStatus = when {
            isCodexAuthInProgress -> OpenAiCredentialUiStatus.IN_PROGRESS
            isCodexAuthenticated -> OpenAiCredentialUiStatus.CONFIGURED
            else -> OpenAiCredentialUiStatus.NOT_CONFIGURED
        },
        isCurrent = activeMode == OpenAiConnectionMode.CODEX_SUBSCRIPTION,
    ),
    OpenAiConnectionModeUiItem(
        mode = OpenAiConnectionMode.PLATFORM_API_KEY,
        credentialStatus = if (isApiKeyConfigured) {
            OpenAiCredentialUiStatus.CONFIGURED
        } else {
            OpenAiCredentialUiStatus.NOT_CONFIGURED
        },
        isCurrent = activeMode == OpenAiConnectionMode.PLATFORM_API_KEY,
    ),
)

internal fun resolveOpenAiModeSelectionUiEffect(
    result: SettingsViewModel.OpenAiModeSelectionResult,
): OpenAiModeSelectionUiEffect = when (result) {
    SettingsViewModel.OpenAiModeSelectionResult.NoChange,
    SettingsViewModel.OpenAiModeSelectionResult.TransitionRequested,
    -> OpenAiModeSelectionUiEffect.NONE

    SettingsViewModel.OpenAiModeSelectionResult.AwaitingRestartConfirmation ->
        OpenAiModeSelectionUiEffect.SHOW_RESTART_CONFIRMATION
    SettingsViewModel.OpenAiModeSelectionResult.RequiresCodexLogin ->
        OpenAiModeSelectionUiEffect.START_CODEX_LOGIN
    SettingsViewModel.OpenAiModeSelectionResult.RequiresApiKey ->
        OpenAiModeSelectionUiEffect.SHOW_API_KEY_DIALOG
}

internal fun isProviderConfigurationEditingEnabled(
    isOpenAiTransitionPending: Boolean,
): Boolean = !isOpenAiTransitionPending

internal sealed interface SettingsErrorUiState {
    val message: String

    data class ChannelDisconnect(
        override val message: String,
    ) : SettingsErrorUiState

    data class OpenAiTransition(
        val failure: OpenAiConnectionTransitionFailure,
    ) : SettingsErrorUiState {
        override val message: String
            get() = failure.message
    }
}

internal fun resolveSettingsError(
    channelDisconnectError: String?,
    openAiConnectionTransitionFailure: OpenAiConnectionTransitionFailure?,
): SettingsErrorUiState? = when {
    channelDisconnectError != null ->
        SettingsErrorUiState.ChannelDisconnect(channelDisconnectError)
    openAiConnectionTransitionFailure != null ->
        SettingsErrorUiState.OpenAiTransition(openAiConnectionTransitionFailure)
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionName")
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenClawConfigEditor: () -> Unit,
    initialApiProvider: String? = null,
    initialSection: String? = null,
    openApiKeyDialogOnLaunch: Boolean = false,
    viewModel: SettingsViewModel = viewModel(),
) {
    val autoStartOnBoot by viewModel.autoStartOnBoot.collectAsState()
    val chargeOnlyMode by viewModel.chargeOnlyMode.collectAsState()
    val executionRuntime by viewModel.executionRuntime.collectAsState()
    val apiProvider by viewModel.apiProvider.collectAsState()
    val openAiConnectionMode by viewModel.openAiConnectionMode.collectAsState()
    val openAiConnectionTransition by viewModel.openAiConnectionTransition.collectAsState()
    val canonicalApiProvider = canonicalSettingsProviderId(apiProvider)
    val providerConfigurationEditingEnabled =
        isProviderConfigurationEditingEnabled(openAiConnectionTransition.isPending)
    val apiKey by viewModel.apiKey.collectAsState()
    val openAiCompatibleBaseUrl by viewModel.openAiCompatibleBaseUrl.collectAsState()
    val openAiCompatibleModelId by viewModel.openAiCompatibleModelId.collectAsState()
    val ollamaBaseUrl by viewModel.ollamaBaseUrl.collectAsState()
    val ollamaModelId by viewModel.ollamaModelId.collectAsState()
    val ollamaCloudModelId by viewModel.ollamaCloudModelId.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val selectedModelProvider by viewModel.selectedModelProvider.collectAsState()
    val selectedModelIds by viewModel.currentProviderSelectedModelIds.collectAsState()
    val globalDefaultModelOptions by viewModel.globalDefaultModelOptions.collectAsState()
    val availableModels by viewModel.availableModels.collectAsState()
    val isLoadingModels by viewModel.isLoadingModels.collectAsState()
    val modelLoadError by viewModel.modelLoadError.collectAsState()
    val hasLoadedModels by viewModel.hasLoadedModels.collectAsState()
    val telegramEnabled by viewModel.telegramEnabled.collectAsState()
    val telegramBotToken by viewModel.telegramBotToken.collectAsState()
    val discordEnabled by viewModel.discordEnabled.collectAsState()
    val discordBotToken by viewModel.discordBotToken.collectAsState()
    val discordGuildAllowlist by viewModel.discordGuildAllowlist.collectAsState()
    val discordRequireMention by viewModel.discordRequireMention.collectAsState()
    val braveSearchApiKey by viewModel.braveSearchApiKey.collectAsState()
    val memorySearchEnabled by viewModel.memorySearchEnabled.collectAsState()
    val memorySearchProvider by viewModel.memorySearchProvider.collectAsState()
    val memorySearchApiKey by viewModel.memorySearchApiKey.collectAsState()
    val isDoctorFixRunning by viewModel.isDoctorFixRunning.collectAsState()
    val doctorFixResult by viewModel.doctorFixResult.collectAsState()
    val isOpenClawExtensionPruneRunning by viewModel.isOpenClawExtensionPruneRunning.collectAsState()
    val openClawExtensionPruneResult by viewModel.openClawExtensionPruneResult.collectAsState()
    val isRecoveryInstallRunning by viewModel.isRecoveryInstallRunning.collectAsState()
    val recoveryInstallResult by viewModel.recoveryInstallResult.collectAsState()
    val isOpenClawUpdateRunning by viewModel.isOpenClawUpdateRunning.collectAsState()
    val openClawUpdateResult by viewModel.openClawUpdateResult.collectAsState()
    val isOpenClawUpdateAvailable by viewModel.isOpenClawUpdateAvailable.collectAsState()
    val installedOpenClawVersion by viewModel.installedOpenClawVersion.collectAsState()
    val bundledOpenClawVersion by viewModel.bundledOpenClawVersion.collectAsState()
    val runtimeRestartHintNonce by viewModel.runtimeRestartHintNonce.collectAsState()
    val setupState by viewModel.setupState.collectAsState()
    val whatsappQrState by viewModel.whatsappQrState.collectAsState()
    val isWhatsAppLinked by viewModel.isWhatsAppLinked.collectAsState()
    val isChannelDisconnecting by viewModel.isChannelDisconnecting.collectAsState()
    val disconnectingChannelLabel by viewModel.disconnectingChannelLabel.collectAsState()
    val channelDisconnectError by viewModel.channelDisconnectError.collectAsState()
    val isCodexAuthInProgress by viewModel.isCodexAuthInProgress.collectAsState()
    val isCodexAuthResetInProgress by viewModel.isCodexAuthResetInProgress.collectAsState()
    val isCodexAuthDiagnosticsInProgress by viewModel.isCodexAuthDiagnosticsInProgress.collectAsState()
    val isCodexAuthenticated by viewModel.isCodexAuthenticated.collectAsState()
    val isGitHubCopilotAuthInProgress by viewModel.isGitHubCopilotAuthInProgress.collectAsState()
    val isGitHubCopilotAuthenticated by viewModel.isGitHubCopilotAuthenticated.collectAsState()
    KeepScreenOnEffect(enabled = isRecoveryInstallRunning || isOpenClawUpdateRunning)
    val codexAuthUrl by viewModel.codexAuthUrl.collectAsState()
    val codexAuthDebugLine by viewModel.codexAuthDebugLine.collectAsState()
    val gitHubCopilotAuthUrl by viewModel.gitHubCopilotAuthUrl.collectAsState()
    val gitHubCopilotVerificationUrl by viewModel.gitHubCopilotVerificationUrl.collectAsState()
    val gitHubCopilotAuthCode by viewModel.gitHubCopilotAuthCode.collectAsState()
    val gitHubCopilotAuthDebugLine by viewModel.gitHubCopilotAuthDebugLine.collectAsState()
    val gitHubCopilotAuthRestartHintNonce by viewModel.gitHubCopilotAuthRestartHintNonce.collectAsState()
    val bugReportUiState by viewModel.bugReportUiState.collectAsState()
    val transferUiState by viewModel.transferUiState.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val providerLabelFor: (String) -> String = { provider ->
        when (canonicalSettingsProviderId(provider)) {
            "openrouter" -> context.getString(R.string.onboarding_provider_openrouter)
            "anthropic" -> context.getString(R.string.onboarding_provider_anthropic)
            "openai" -> context.getString(R.string.settings_memory_search_provider_openai)
            "github-copilot" -> context.getString(R.string.onboarding_provider_github_copilot)
            "zai" -> context.getString(R.string.onboarding_provider_zai)
            "kimi-coding" -> context.getString(R.string.onboarding_provider_kimi_coding)
            "minimax" -> context.getString(R.string.onboarding_provider_minimax)
            "ollama" -> context.getString(R.string.onboarding_provider_ollama)
            "ollama-cloud" -> context.getString(R.string.onboarding_provider_ollama_cloud)
            "openai-compatible" -> context.getString(R.string.onboarding_provider_openai_compatible)
            "google" -> context.getString(R.string.onboarding_provider_google)
            else -> provider
        }
    }
    val defaultModelOptionsUi = globalDefaultModelOptions.map { option ->
        val provider = canonicalSettingsProviderId(option.provider)
        DefaultModelDialogOption(
            provider = provider,
            providerLabel = providerLabelFor(provider),
            modelId = option.modelId,
            displayModelId = formatSelectedModelLabel(provider, option.modelId),
        )
    }
    val canonicalSelectedModelProvider = canonicalSettingsProviderId(selectedModelProvider)
    val defaultModelDisplay = when {
        selectedModel.isBlank() || canonicalSelectedModelProvider.isBlank() ->
            stringResource(R.string.settings_default_model_none)
        else ->
            "${providerLabelFor(canonicalSelectedModelProvider)} · " +
                formatSelectedModelLabel(canonicalSelectedModelProvider, selectedModel)
    }
    val memorySearchProviderDisplay = when (memorySearchProvider) {
        "openai" -> stringResource(R.string.settings_memory_search_provider_openai)
        "gemini" -> stringResource(R.string.settings_memory_search_provider_gemini)
        "voyage" -> stringResource(R.string.settings_memory_search_provider_voyage)
        "mistral" -> stringResource(R.string.settings_memory_search_provider_mistral)
        "local" -> stringResource(R.string.settings_memory_search_provider_local)
        else -> stringResource(R.string.settings_memory_search_provider_auto)
    }
    val executionRuntimeDisplay = formatExecutionRuntimeLabel(
        runtime = executionRuntime,
        prorootLabel = stringResource(R.string.settings_execution_runtime_proroot),
        prootLabel = stringResource(R.string.settings_execution_runtime_proot),
    )
    val canRunExtensionPrune = executionRuntime == ExecutionRuntime.PROOT.storageValue
    val settingsTabs = listOf(
        stringResource(R.string.settings_section_gateway),
        stringResource(R.string.settings_tab_model),
        stringResource(R.string.settings_section_channels),
        stringResource(R.string.settings_section_tools),
    )

    var showModelDialog by remember { mutableStateOf(false) }
    var showDefaultModelDialog by remember { mutableStateOf(false) }
    var showProviderDialog by remember { mutableStateOf(false) }
    var showOpenAiConnectionModeDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showRestartHint by remember { mutableStateOf(false) }
    var showBotRestartNotice by remember { mutableStateOf(false) }
    var showTelegramTokenDialog by remember { mutableStateOf(false) }
    var showDiscordTokenDialog by remember { mutableStateOf(false) }
    var showDiscordGuildAllowlistDialog by remember { mutableStateOf(false) }
    var showBraveKeyDialog by remember { mutableStateOf(false) }
    var showMemorySearchProviderDialog by remember { mutableStateOf(false) }
    var showMemorySearchApiKeyDialog by remember { mutableStateOf(false) }
    var showExecutionRuntimeDialog by remember { mutableStateOf(false) }
    var showCodexAuthResetConfirmDialog by remember { mutableStateOf(false) }
    var showCodexAuthDiagnosticsDialog by remember { mutableStateOf(false) }
    var showExtensionPruneConfirmDialog by remember { mutableStateOf(false) }
    var showOssLicensesDialog by remember { mutableStateOf(false) }
    var showRecoveryInstallConfirmDialog by remember { mutableStateOf(false) }
    var showOpenClawUpdateConfirmDialog by remember { mutableStateOf(false) }
    var showWhatsAppActionDialog by remember { mutableStateOf(false) }
    var showTransferExportPasswordDialog by remember { mutableStateOf(false) }
    var showTransferImportPasswordDialog by remember { mutableStateOf(false) }
    var pendingTransferImportFile by remember { mutableStateOf<File?>(null) }
    var pendingApiKeyProvider by remember(initialApiProvider, openApiKeyDialogOnLaunch) {
        mutableStateOf(initialApiProvider?.takeIf { openApiKeyDialogOnLaunch })
    }
    var apiKeyDialogProviderOverride by remember { mutableStateOf<String?>(null) }
    var apiKeyDialogCurrentKeyOverride by remember { mutableStateOf<String?>(null) }
    var openAiApiKeyConfigured by remember { mutableStateOf(false) }
    var selectedSettingsTabIndex by remember(initialSection) {
        mutableStateOf(resolveInitialSettingsTabIndex(initialSection))
    }
    val handleOpenAiModeSelectionResult: (SettingsViewModel.OpenAiModeSelectionResult) -> Unit = { result ->
        when (resolveOpenAiModeSelectionUiEffect(result)) {
            OpenAiModeSelectionUiEffect.NONE -> Unit
            OpenAiModeSelectionUiEffect.SHOW_RESTART_CONFIRMATION -> {
                showRestartHint = true
            }
            OpenAiModeSelectionUiEffect.START_CODEX_LOGIN -> {
                viewModel.loginOpenAiCodexOAuth()
            }
            OpenAiModeSelectionUiEffect.SHOW_API_KEY_DIALOG -> {
                apiKeyDialogProviderOverride = "openai"
                apiKeyDialogCurrentKeyOverride = ""
                showApiKeyDialog = true
            }
        }
    }
    val openOpenAiConnectionModeDialog = {
        viewModel.getApiKeyForProvider("openai") { currentKey ->
            openAiApiKeyConfigured = currentKey.isNotBlank()
            showOpenAiConnectionModeDialog = true
        }
    }
    val isMaintenanceBusy =
        isDoctorFixRunning ||
            isRecoveryInstallRunning ||
            isOpenClawUpdateRunning ||
            isOpenClawExtensionPruneRunning ||
            isCodexAuthInProgress ||
            isCodexAuthResetInProgress ||
            isCodexAuthDiagnosticsInProgress ||
            isGitHubCopilotAuthInProgress
    val openClawVersionInfoText = if (!installedOpenClawVersion.isNullOrBlank() && !bundledOpenClawVersion.isNullOrBlank()) {
        if (isOpenClawUpdateAvailable) {
            context.getString(
                R.string.settings_openclaw_update_available_version,
                installedOpenClawVersion!!,
                bundledOpenClawVersion!!,
            )
        } else {
            context.getString(
                R.string.settings_openclaw_update_current_version,
                installedOpenClawVersion!!,
            )
        }
    } else {
        null
    }

    LaunchedEffect(runtimeRestartHintNonce) {
        if (runtimeRestartHintNonce > 0L) {
            showRestartHint = true
        }
    }
    val ossLicensesText = remember {
        runCatching {
            context.resources.openRawResource(R.raw.third_party_licenses)
                .bufferedReader()
                .use { it.readText() }
        }.getOrElse { "Failed to load OSS license notices." }
    }
    val transferImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val importedFile = copyTransferUriToCache(context, uri)
        if (importedFile == null) {
            Toast.makeText(
                context,
                context.getString(R.string.settings_transfer_import_copy_failed),
                Toast.LENGTH_SHORT,
            ).show()
            return@rememberLauncherForActivityResult
        }
        pendingTransferImportFile = importedFile
        showTransferImportPasswordDialog = true
    }

    LaunchedEffect(codexAuthUrl) {
        val url = codexAuthUrl
        if (!url.isNullOrBlank()) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.settings_cannot_open), Toast.LENGTH_SHORT).show()
            } finally {
                viewModel.consumeCodexAuthUrl()
            }
        }
    }
    if (SHOW_CODEX_AUTH_MAINTENANCE_ACTIONS) {
        LaunchedEffect(codexAuthDebugLine) {
            val debugLine = codexAuthDebugLine
            if (!debugLine.isNullOrBlank() && debugLine.length > CODEX_AUTH_DEBUG_DIALOG_AUTO_OPEN_THRESHOLD) {
                showCodexAuthDiagnosticsDialog = true
            }
        }
    }

    LaunchedEffect(gitHubCopilotAuthUrl) {
        val url = gitHubCopilotAuthUrl
        if (!url.isNullOrBlank()) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {
                Toast.makeText(context, context.getString(R.string.settings_cannot_open), Toast.LENGTH_SHORT).show()
            } finally {
                viewModel.consumeGitHubCopilotAuthUrl()
            }
        }
    }

    LaunchedEffect(gitHubCopilotAuthRestartHintNonce) {
        if (gitHubCopilotAuthRestartHintNonce > 0L) {
            showRestartHint = true
        }
    }

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                viewModel.refreshWhatsAppLinkState()
                delay(5000)
            }
        }
    }

    LaunchedEffect(whatsappQrState) {
        if (whatsappQrState is WhatsAppQrState.QrReady) {
            viewModel.confirmWhatsAppQrScanned()
        }
    }

    LaunchedEffect(canonicalApiProvider, apiKey) {
        if (canonicalApiProvider == "openai") {
            openAiApiKeyConfigured = apiKey.isNotBlank()
        }
    }

    LaunchedEffect(providerConfigurationEditingEnabled) {
        if (!providerConfigurationEditingEnabled) {
            showProviderDialog = false
            showOpenAiConnectionModeDialog = false
            showApiKeyDialog = false
            showModelDialog = false
            showDefaultModelDialog = false
        }
    }

    LaunchedEffect(openAiConnectionTransition.phase) {
        if (openAiConnectionTransition.phase == OpenAiConnectionTransitionPhase.AWAITING_CONFIRMATION) {
            showRestartHint = true
        }
    }

    LaunchedEffect(pendingApiKeyProvider) {
        val targetProvider = pendingApiKeyProvider ?: return@LaunchedEffect
        selectedSettingsTabIndex = 0
        if (canonicalSettingsProviderId(targetProvider) == "openai") {
            pendingApiKeyProvider = null
            viewModel.setOpenAiConnectionMode(
                targetMode = OpenAiConnectionMode.PLATFORM_API_KEY,
                onResult = handleOpenAiModeSelectionResult,
            )
            return@LaunchedEffect
        }
        viewModel.setApiProvider(targetProvider) { appliedProvider, _ ->
            if (appliedProvider == "github-copilot") {
                pendingApiKeyProvider = null
                viewModel.loginGitHubCopilot()
                return@setApiProvider
            }
            viewModel.getApiKeyForProvider(appliedProvider) { currentKey ->
                apiKeyDialogProviderOverride = appliedProvider
                apiKeyDialogCurrentKeyOverride = currentKey
                showApiKeyDialog = true
                pendingApiKeyProvider = null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_cd_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TabRow(
                selectedTabIndex = selectedSettingsTabIndex,
                modifier = Modifier.fillMaxWidth(),
            ) {
                settingsTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedSettingsTabIndex == index,
                        onClick = { selectedSettingsTabIndex = index },
                        text = {
                            Text(
                                text = title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }

            if (selectedSettingsTabIndex == 0) {
                // ══════════════════════════════════════
                // Gateway 섹션
                // ══════════════════════════════════════
                SectionHeader(
                    title = stringResource(R.string.settings_section_gateway),
                    icon = Icons.Default.PowerSettingsNew,
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column {
                        SettingToggle(
                            title = stringResource(R.string.settings_auto_start),
                            description = stringResource(R.string.settings_auto_start_desc),
                            checked = autoStartOnBoot,
                            onCheckedChange = viewModel::setAutoStartOnBoot,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        SettingToggle(
                            title = stringResource(R.string.settings_charge_only),
                            description = stringResource(R.string.settings_charge_only_desc),
                            checked = chargeOnlyMode,
                            onCheckedChange = viewModel::setChargeOnlyMode,
                        )
                    }
                }

                // ══════════════════════════════════════
                // Battery 섹션
                // ══════════════════════════════════════
                SectionHeader(
                    title = stringResource(R.string.settings_section_battery),
                    icon = Icons.Default.BatteryAlert,
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                context.startActivity(viewModel.requestBatteryOptimizationExemption())
                            } catch (e: Exception) {
                                Toast
                                    .makeText(context, context.getString(R.string.settings_cannot_open), Toast.LENGTH_SHORT)
                                    .show()
                            }
                        },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.BatteryAlert,
                            contentDescription = null,
                            tint = if (viewModel.isBatteryOptimizationIgnored())
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_battery_optimization),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = if (viewModel.isBatteryOptimizationIgnored())
                                    stringResource(R.string.settings_battery_exempted)
                                else
                                    stringResource(R.string.settings_battery_not_exempted),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (selectedSettingsTabIndex == 1) {
                SectionHeader(
                    title = stringResource(R.string.settings_select_model),
                    icon = Icons.Default.Psychology,
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column {
                        SettingClickableRow(
                            title = stringResource(R.string.settings_current_provider),
                            value = providerLabelFor(canonicalApiProvider),
                            enabled = providerConfigurationEditingEnabled,
                            onClick = { showProviderDialog = true },
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )

                        when {
                            canonicalApiProvider == "openai" -> {
                                SettingClickableRow(
                                    title = stringResource(R.string.settings_openai_connection_method),
                                    value = stringResource(openAiConnectionModeTitle(openAiConnectionMode)),
                                    enabled = providerConfigurationEditingEnabled,
                                    onClick = openOpenAiConnectionModeDialog,
                                )

                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 20.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )

                                if (openAiConnectionMode == OpenAiConnectionMode.CODEX_SUBSCRIPTION) {
                                    SettingClickableRow(
                                        title = stringResource(R.string.settings_codex_oauth_title),
                                        value = when {
                                            isCodexAuthInProgress ->
                                                stringResource(R.string.settings_codex_oauth_signing_in)
                                            isCodexAuthenticated ->
                                                stringResource(R.string.settings_connection_status_connected)
                                            else ->
                                                stringResource(R.string.settings_connection_status_not_connected)
                                        },
                                        valueColor = if (!isCodexAuthenticated && !isCodexAuthInProgress) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            null
                                        },
                                        enabled = providerConfigurationEditingEnabled,
                                        onClick = {
                                            if (isCodexAuthenticated) {
                                                viewModel.loginOpenAiCodexOAuth()
                                            } else {
                                                viewModel.setOpenAiConnectionMode(
                                                    targetMode = OpenAiConnectionMode.CODEX_SUBSCRIPTION,
                                                    onResult = handleOpenAiModeSelectionResult,
                                                )
                                            }
                                        },
                                    )
                                    if (SHOW_CODEX_AUTH_MAINTENANCE_ACTIONS) {
                                        SettingClickableRow(
                                            title = stringResource(R.string.settings_codex_oauth_reset_title),
                                            value = if (isCodexAuthResetInProgress) {
                                                stringResource(R.string.settings_codex_oauth_reset_running)
                                            } else {
                                                stringResource(R.string.settings_codex_oauth_reset_desc)
                                            },
                                            enabled = providerConfigurationEditingEnabled &&
                                                !isCodexAuthInProgress &&
                                                !isCodexAuthResetInProgress &&
                                                !isCodexAuthDiagnosticsInProgress,
                                            onClick = { showCodexAuthResetConfirmDialog = true },
                                        )
                                        SettingClickableRow(
                                            title = stringResource(R.string.settings_codex_oauth_diagnostics_title),
                                            value = if (isCodexAuthDiagnosticsInProgress) {
                                                stringResource(R.string.settings_codex_oauth_diagnostics_running)
                                            } else {
                                                stringResource(R.string.settings_codex_oauth_diagnostics_desc)
                                            },
                                            enabled = providerConfigurationEditingEnabled &&
                                                !isCodexAuthInProgress &&
                                                !isCodexAuthResetInProgress &&
                                                !isCodexAuthDiagnosticsInProgress,
                                            onClick = { viewModel.diagnoseOpenAiCodexOAuth() },
                                        )
                                        if (codexAuthDebugLine != null) {
                                            Text(
                                                text = codexAuthDebugLine ?: "",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(start = 20.dp, end = 20.dp),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            TextButton(
                                                modifier = Modifier.padding(
                                                    start = 12.dp,
                                                    end = 12.dp,
                                                    bottom = 8.dp,
                                                ),
                                                onClick = { showCodexAuthDiagnosticsDialog = true },
                                            ) {
                                                Text(
                                                    stringResource(
                                                        R.string.settings_codex_oauth_diagnostics_view_full,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    val openAiApiKeyRowState = resolveApiKeyRowState(
                                        provider = "openai",
                                        apiKey = apiKey,
                                        baseUrl = "",
                                        configuredLabel = stringResource(R.string.settings_connection_status_connected),
                                        notConfiguredLabel = stringResource(R.string.settings_connection_status_not_connected),
                                        notRequiredLabel = stringResource(R.string.settings_api_key_not_required),
                                    )
                                    SettingClickableRow(
                                        title = stringResource(R.string.settings_api_key),
                                        value = openAiApiKeyRowState.value,
                                        valueColor = if (openAiApiKeyRowState.isError) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            null
                                        },
                                        enabled = providerConfigurationEditingEnabled,
                                        onClick = {
                                            viewModel.getApiKeyForProvider("openai") { currentKey ->
                                                apiKeyDialogProviderOverride = "openai"
                                                apiKeyDialogCurrentKeyOverride = currentKey
                                                showApiKeyDialog = true
                                            }
                                        },
                                    )
                                }
                            }

                            shouldUseApiKeyDialog(canonicalApiProvider) -> {
                                val customProviderBaseUrlValue = customProviderBaseUrl(
                                    provider = canonicalApiProvider,
                                    openAiCompatibleBaseUrl = openAiCompatibleBaseUrl,
                                    ollamaBaseUrl = ollamaBaseUrl,
                                )
                                val apiKeyRowState = resolveApiKeyRowState(
                                    provider = canonicalApiProvider,
                                    apiKey = apiKey,
                                    baseUrl = customProviderBaseUrlValue,
                                    configuredLabel = stringResource(R.string.settings_api_key_configured),
                                    notConfiguredLabel = stringResource(R.string.settings_api_key_not_configured),
                                    notRequiredLabel = stringResource(R.string.settings_api_key_not_required),
                                )
                                SettingClickableRow(
                                    title = if (canonicalApiProvider == "ollama") {
                                        stringResource(R.string.settings_ollama_host_address)
                                    } else {
                                        stringResource(R.string.settings_api_key)
                                    },
                                    value = if (canonicalApiProvider == "ollama") {
                                        customProviderBaseUrlValue.ifBlank {
                                            stringResource(R.string.settings_api_key_not_configured)
                                        }
                                    } else {
                                        apiKeyRowState.value
                                    },
                                    valueColor = if (canonicalApiProvider == "ollama") {
                                        if (customProviderBaseUrlValue.isBlank()) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            null
                                        }
                                    } else if (apiKeyRowState.isError) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        null
                                    },
                                    enabled = providerConfigurationEditingEnabled,
                                    onClick = { showApiKeyDialog = true },
                                )
                            }

                            else -> {
                                SettingClickableRow(
                                    title = stringResource(R.string.settings_github_copilot_login_title),
                                    value = when {
                                        isGitHubCopilotAuthInProgress ->
                                            stringResource(R.string.settings_github_copilot_login_signing_in)
                                        isGitHubCopilotAuthenticated ->
                                            stringResource(R.string.settings_api_key_configured)
                                        else ->
                                            stringResource(R.string.settings_api_key_not_configured)
                                    },
                                    valueColor = if (
                                        !isGitHubCopilotAuthenticated &&
                                        !isGitHubCopilotAuthInProgress
                                    ) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        null
                                    },
                                    enabled = providerConfigurationEditingEnabled,
                                    onClick = {
                                        val existingUrl = gitHubCopilotVerificationUrl
                                        if (isGitHubCopilotAuthInProgress && !existingUrl.isNullOrBlank()) {
                                            try {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(existingUrl)),
                                                )
                                            } catch (_: Exception) {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.settings_cannot_open),
                                                    Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        } else {
                                            viewModel.loginGitHubCopilot()
                                        }
                                    },
                                )
                                if (!gitHubCopilotAuthCode.isNullOrBlank()) {
                                    Text(
                                        text = stringResource(
                                            R.string.settings_github_copilot_login_code,
                                            gitHubCopilotAuthCode.orEmpty(),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(
                                            start = 20.dp,
                                            end = 20.dp,
                                            bottom = 12.dp,
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (!gitHubCopilotVerificationUrl.isNullOrBlank()) {
                                    Text(
                                        text = gitHubCopilotVerificationUrl.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(
                                            start = 20.dp,
                                            end = 20.dp,
                                            bottom = 12.dp,
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (gitHubCopilotAuthDebugLine != null) {
                                    Text(
                                        text = gitHubCopilotAuthDebugLine ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(
                                            start = 20.dp,
                                            end = 20.dp,
                                            bottom = 12.dp,
                                        ),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }

                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    SettingClickableRow(
                        title = stringResource(R.string.settings_select_model),
                        value = when {
                            selectedModelIds.isEmpty() && !hasLoadedModels -> ""
                            selectedModelIds.isEmpty() -> stringResource(R.string.settings_model_none_found)
                            else -> selectedModelIds.joinToString(", ") {
                                formatSelectedModelLabel(canonicalApiProvider, it)
                            }
                        },
                        enabled = providerConfigurationEditingEnabled,
                        onClick = {
                            showModelDialog = true
                            viewModel.fetchModelsForCurrentProvider()
                        },
                    )
                }

                SectionHeader(
                    title = stringResource(R.string.settings_default_model_label),
                    icon = Icons.Default.Psychology,
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    SettingClickableRow(
                        title = stringResource(R.string.settings_default_model_label),
                        value = defaultModelDisplay,
                        onClick = { showDefaultModelDialog = true },
                        enabled = providerConfigurationEditingEnabled &&
                            defaultModelOptionsUi.isNotEmpty(),
                    )
                }
            }

            if (selectedSettingsTabIndex == 3) {
                // ══════════════════════════════════════
                // Tools 섹션
                // ══════════════════════════════════════
                SectionHeader(
                    title = stringResource(R.string.settings_section_tools),
                    icon = Icons.Default.Search,
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column {
                        SettingClickableRow(
                            title = stringResource(R.string.settings_brave_search_key),
                            value = if (braveSearchApiKey.isNotBlank()) {
                                stringResource(R.string.settings_api_key_configured) + " (${braveSearchApiKey.take(8)}...)"
                            } else {
                                stringResource(R.string.settings_brave_search_optional)
                            },
                            onClick = { showBraveKeyDialog = true },
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )

                        SettingToggle(
                            title = stringResource(R.string.settings_memory_search_title),
                            description = stringResource(R.string.settings_memory_search_desc),
                            checked = memorySearchEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setMemorySearchEnabled(enabled)
                                showRestartHint = true
                            },
                        )

                        if (memorySearchEnabled) {
                            SettingClickableRow(
                                title = stringResource(R.string.settings_memory_search_provider),
                                value = memorySearchProviderDisplay,
                                onClick = { showMemorySearchProviderDialog = true },
                                indent = true,
                            )
                        }

                        SettingClickableRow(
                            title = stringResource(R.string.settings_memory_search_api_key),
                            value = if (memorySearchApiKey.isNotBlank()) {
                                stringResource(R.string.settings_api_key_configured) + " (${memorySearchApiKey.take(8)}...)"
                            } else {
                                stringResource(R.string.settings_memory_search_api_key_optional)
                            },
                            onClick = { showMemorySearchApiKeyDialog = true },
                            indent = true,
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )

                        SettingClickableRow(
                            title = stringResource(R.string.settings_openclaw_doctor_fix),
                            value = if (isDoctorFixRunning) {
                                stringResource(R.string.settings_openclaw_doctor_fix_running)
                            } else {
                                stringResource(R.string.settings_openclaw_doctor_fix_run)
                            },
                            enabled = !isMaintenanceBusy,
                            onClick = { viewModel.runOpenClawDoctorFix() },
                        )
                    }
                }

                SectionHeader(
                    title = stringResource(R.string.settings_section_advanced_users),
                    icon = Icons.Default.Info,
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column {
                        SettingClickableRow(
                            title = stringResource(R.string.settings_execution_runtime_title),
                            value = executionRuntimeDisplay,
                            onClick = { showExecutionRuntimeDialog = true },
                            valueMaxLines = 2,
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )

                        SettingClickableRow(
                            title = stringResource(R.string.settings_openclaw_config_editor_entry),
                            value = stringResource(R.string.settings_openclaw_config_editor_entry_desc),
                            onClick = onOpenClawConfigEditor,
                            valueMaxLines = 2,
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )

                        SettingClickableRow(
                            title = stringResource(R.string.settings_openclaw_extension_prune),
                            value = if (isOpenClawExtensionPruneRunning) {
                                stringResource(R.string.settings_openclaw_extension_prune_running)
                            } else if (!canRunExtensionPrune) {
                                stringResource(R.string.settings_openclaw_extension_prune_requires_proot)
                            } else {
                                stringResource(R.string.settings_openclaw_extension_prune_desc)
                            },
                            enabled = !isMaintenanceBusy && canRunExtensionPrune,
                            onClick = { showExtensionPruneConfirmDialog = true },
                            valueMaxLines = 3,
                        )
                    }
                }
            }

            if (selectedSettingsTabIndex == 2) {
                // ══════════════════════════════════════
                // Channels 섹션
                // ══════════════════════════════════════
                SectionHeader(
                    title = stringResource(R.string.settings_section_channels),
                    icon = Icons.AutoMirrored.Filled.Chat,
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column {
                        // WhatsApp (always-on): 토글 없이 QR 연결만 제공
                        SettingClickableRow(
                            title = stringResource(R.string.settings_channel_whatsapp),
                            value = if (isWhatsAppLinked) {
                                stringResource(R.string.whatsapp_connected)
                            } else {
                                stringResource(R.string.whatsapp_connect_btn)
                            },
                            enabled = !isChannelDisconnecting,
                            onClick = {
                                viewModel.refreshWhatsAppLinkState()
                                showWhatsAppActionDialog = true
                            },
                        )

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )

                        // Telegram
                        SettingToggle(
                            title = stringResource(R.string.settings_channel_telegram),
                            description = stringResource(R.string.settings_telegram_desc),
                            checked = telegramEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setTelegramEnabled(enabled)
                                if (enabled && telegramBotToken.isBlank()) {
                                    showTelegramTokenDialog = true
                                }
                            },
                        )

                        if (telegramEnabled) {
                            SettingClickableRow(
                                title = stringResource(R.string.settings_bot_token_title),
                                value = if (telegramBotToken.isNotBlank()) {
                                    stringResource(R.string.settings_token_configured) + " (${telegramBotToken.take(8)}...)"
                                } else {
                                    stringResource(R.string.settings_token_not_configured)
                                },
                                valueColor = if (telegramBotToken.isBlank()) MaterialTheme.colorScheme.error else null,
                                onClick = { showTelegramTokenDialog = true },
                                indent = true,
                            )
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 20.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )

                        // Discord
                        SettingToggle(
                            title = stringResource(R.string.settings_channel_discord),
                            description = stringResource(R.string.settings_discord_desc),
                            checked = discordEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setDiscordEnabled(enabled)
                                if (enabled && discordBotToken.isBlank()) {
                                    showDiscordTokenDialog = true
                                }
                            },
                        )

                        if (discordEnabled) {
                            SettingClickableRow(
                                title = stringResource(R.string.settings_bot_token_title),
                                value = if (discordBotToken.isNotBlank()) {
                                    stringResource(R.string.settings_token_configured) + " (${discordBotToken.take(8)}...)"
                                } else {
                                    stringResource(R.string.settings_token_not_configured)
                                },
                                valueColor = if (discordBotToken.isBlank()) MaterialTheme.colorScheme.error else null,
                                onClick = { showDiscordTokenDialog = true },
                                indent = true,
                            )

                            SettingClickableRow(
                                title = stringResource(R.string.settings_discord_guild_allowlist_title),
                                value = discordGuildAllowlistSummary(
                                    raw = discordGuildAllowlist,
                                    notConfigured = stringResource(R.string.settings_discord_guild_allowlist_not_configured),
                                    configuredFormat = stringResource(R.string.settings_discord_guild_allowlist_configured),
                                ),
                                valueColor = if (discordGuildAllowlist.trim().isEmpty()) MaterialTheme.colorScheme.error else null,
                                onClick = { showDiscordGuildAllowlistDialog = true },
                                indent = true,
                            )

                            SettingToggle(
                                title = stringResource(R.string.settings_discord_require_mention_title),
                                description = stringResource(R.string.settings_discord_require_mention_desc),
                                checked = discordRequireMention,
                                onCheckedChange = { enabled ->
                                    viewModel.setDiscordRequireMention(enabled, restartGateway = false)
                                    showBotRestartNotice = true
                                },
                                indent = true,
                            )
                        }
                    }
                }
            }

            if (selectedSettingsTabIndex == 0) {
                // ══════════════════════════════════════
                // About 섹션
                // ══════════════════════════════════════
                SectionHeader(
                    title = stringResource(R.string.settings_section_about),
                    icon = Icons.Default.Info,
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.onVersionInfoTapped() },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column {
                                Text(
                                    stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.settings_about_powered_by),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.settings_disclaimer),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        SettingClickableRow(
                            title = "Open Source Licenses",
                            value = "View",
                            onClick = { showOssLicensesDialog = true },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        SettingClickableRow(
                            title = stringResource(R.string.bug_report_title),
                            value = bugReportUiState.artifactInfo?.let {
                                stringResource(
                                    R.string.bug_report_last_file,
                                    it.fileName,
                                    formatFileSize(it.sizeBytes),
                                )
                            } ?: stringResource(R.string.bug_report_consent_required),
                            onClick = { viewModel.openBugReportDialog() },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        SettingClickableRow(
                            title = stringResource(R.string.settings_transfer_export_title),
                            value = stringResource(R.string.settings_transfer_export_desc),
                            enabled = !isMaintenanceBusy,
                            valueMaxLines = 3,
                            onClick = { showTransferExportPasswordDialog = true },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        SettingClickableRow(
                            title = stringResource(R.string.settings_transfer_import_title),
                            value = stringResource(R.string.settings_transfer_import_desc),
                            enabled = !isMaintenanceBusy,
                            onClick = { transferImportPicker.launch(arrayOf("*/*")) },
                        )
                    }
                }
            }

            if (selectedSettingsTabIndex == 3 && isOpenClawUpdateAvailable) {
                FilledTonalButton(
                    onClick = { showOpenClawUpdateConfirmDialog = true },
                    enabled = !isMaintenanceBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (isOpenClawUpdateRunning) {
                            stringResource(R.string.settings_openclaw_update_running)
                        } else {
                            stringResource(R.string.settings_openclaw_update_action)
                        },
                    )
                }
            }

            if (selectedSettingsTabIndex == 3 && !openClawVersionInfoText.isNullOrBlank()) {
                Text(
                    text = openClawVersionInfoText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (selectedSettingsTabIndex == 3 && isOpenClawUpdateAvailable) {
                TextButton(
                    onClick = { showRecoveryInstallConfirmDialog = true },
                    enabled = !isMaintenanceBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.dashboard_update_action_recover))
                }
            } else if (selectedSettingsTabIndex == 3) {
                FilledTonalButton(
                    onClick = { showRecoveryInstallConfirmDialog = true },
                    enabled = !isMaintenanceBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (isRecoveryInstallRunning) {
                            stringResource(R.string.settings_openclaw_doctor_fix_running)
                        } else {
                            stringResource(R.string.dashboard_update_action_recover)
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    // ── Model Selection Dialog ──
    if (showModelDialog) {
        ModelSelectionDialog(
            models = availableModels,
            selectedModelIds = selectedModelIds.toSet(),
            isLoading = isLoadingModels,
            errorMessage = modelLoadError,
            onApplySelection = { models ->
                viewModel.applySelectedModels(models) { _, changed ->
                    showModelDialog = false
                    showRestartHint = changed && viewModel.isGatewayActive
                }
            },
            onDismiss = { showModelDialog = false },
            onRetry = { viewModel.fetchModelsForCurrentProvider() },
        )
    }

    if (showDefaultModelDialog) {
        DefaultModelSelectionDialog(
            options = defaultModelOptionsUi,
            selectedProvider = canonicalSelectedModelProvider,
            selectedModelId = formatCanonicalSelectedModelId(canonicalSelectedModelProvider, selectedModel),
            onApplySelection = { option ->
                viewModel.setGlobalDefaultModel(option.provider, option.modelId) { changed ->
                    showDefaultModelDialog = false
                    showRestartHint = changed && viewModel.isGatewayActive
                }
            },
            onDismiss = { showDefaultModelDialog = false },
        )
    }

    // ── Provider Selection Dialog ──
    if (showProviderDialog) {
        ProviderSelectionDialog(
            currentProvider = canonicalApiProvider,
            onSelectProvider = { provider ->
                showProviderDialog = false
                when (resolveProviderSelectionUiAction(provider)) {
                    ProviderSelectionUiAction.OPEN_OPENAI_CONNECTION_MODE_CHOOSER ->
                        openOpenAiConnectionModeDialog()
                    ProviderSelectionUiAction.APPLY_PROVIDER -> {
                        viewModel.setApiProvider(provider) { _, changed ->
                            showRestartHint = changed && viewModel.isGatewayActive
                        }
                    }
                }
            },
            onDismiss = { showProviderDialog = false },
        )
    }

    if (showOpenAiConnectionModeDialog) {
        OpenAiConnectionModeDialog(
            items = buildOpenAiConnectionModeUiItems(
                activeMode = openAiConnectionMode,
                isCodexAuthenticated = isCodexAuthenticated,
                isCodexAuthInProgress = isCodexAuthInProgress,
                isApiKeyConfigured = openAiApiKeyConfigured,
            ),
            isTransitionPending = openAiConnectionTransition.isPending,
            onSelectMode = { targetMode ->
                showOpenAiConnectionModeDialog = false
                viewModel.setOpenAiConnectionMode(
                    targetMode = targetMode,
                    onResult = handleOpenAiModeSelectionResult,
                )
            },
            onDismiss = { showOpenAiConnectionModeDialog = false },
        )
    }

    // ── API Key Input Dialog ──
    if (showApiKeyDialog) {
        val dialogProvider = apiKeyDialogProviderOverride ?: canonicalApiProvider
        ApiKeyInputDialog(
            currentKey = apiKeyDialogCurrentKeyOverride
                ?: if (dialogProvider == canonicalApiProvider) apiKey else "",
            provider = dialogProvider,
            currentBaseUrl = customProviderBaseUrl(
                provider = dialogProvider,
                openAiCompatibleBaseUrl = openAiCompatibleBaseUrl,
                ollamaBaseUrl = ollamaBaseUrl,
            ),
            currentModelId = resolveApiKeyDialogModelId(
                provider = dialogProvider,
                openAiCompatibleModelId = openAiCompatibleModelId,
                ollamaModelId = if (dialogProvider == "ollama-cloud") ollamaCloudModelId else ollamaModelId,
                selectedModel = selectedModel,
            ),
            onSave = { key ->
                if (dialogProvider == "openai" && dialogProvider != canonicalApiProvider) {
                    viewModel.setApiKeyForProvider(
                        provider = dialogProvider,
                        key = key,
                        onError = viewModel::reportOpenAiConnectionTransitionError,
                    ) { _, _ ->
                        viewModel.retryRequestedOpenAiConnectionMode(handleOpenAiModeSelectionResult)
                    }
                } else if (dialogProvider == canonicalApiProvider) {
                    viewModel.setApiKey(
                        key = key,
                        onError = viewModel::reportOpenAiConnectionTransitionError,
                    ) { _, changed ->
                        showRestartHint = changed && viewModel.isGatewayActive
                        viewModel.retryRequestedOpenAiConnectionMode(handleOpenAiModeSelectionResult)
                    }
                } else {
                    viewModel.setApiKeyForProvider(
                        provider = dialogProvider,
                        key = key,
                        onError = viewModel::reportOpenAiConnectionTransitionError,
                    ) { _, changed ->
                        showRestartHint = changed && viewModel.isGatewayActive
                    }
                }
                showApiKeyDialog = false
                apiKeyDialogProviderOverride = null
                apiKeyDialogCurrentKeyOverride = null
            },
            onSaveOpenAiCompatible = { key, baseUrl, modelId ->
                viewModel.saveOpenAiCompatibleConfig(
                    apiKey = key,
                    baseUrl = baseUrl,
                    modelId = modelId,
                    activateProvider = shouldActivateCustomProviderApiSave(
                        dialogProvider = dialogProvider,
                        globalPrimaryProvider = selectedModelProvider,
                    ),
                    onApplied = { changed ->
                        showRestartHint = changed && viewModel.isGatewayActive
                    },
                )
                showApiKeyDialog = false
                apiKeyDialogProviderOverride = null
                apiKeyDialogCurrentKeyOverride = null
            },
            onSaveOllama = { key, baseUrl ->
                viewModel.saveOllamaConfig(
                    apiKey = key,
                    baseUrl = baseUrl,
                    activateProvider = shouldActivateCustomProviderApiSave(
                        dialogProvider = dialogProvider,
                        globalPrimaryProvider = selectedModelProvider,
                    ),
                    onApplied = { changed ->
                        showRestartHint = changed && viewModel.isGatewayActive
                    },
                )
                showApiKeyDialog = false
                apiKeyDialogProviderOverride = null
                apiKeyDialogCurrentKeyOverride = null
            },
            onDismiss = {
                showApiKeyDialog = false
                apiKeyDialogProviderOverride = null
                apiKeyDialogCurrentKeyOverride = null
            },
        )
    }

    // ── Telegram Bot Token Dialog ──
    if (showTelegramTokenDialog) {
        BotTokenInputDialog(
            currentToken = telegramBotToken,
            channelName = stringResource(R.string.settings_channel_telegram),
            tokenHint = stringResource(R.string.settings_bot_token_hint),
            helpUrl = "https://core.telegram.org/bots#how-do-i-create-a-bot",
            helpText = stringResource(R.string.settings_bot_token_help, stringResource(R.string.settings_channel_telegram)),
            onSave = { token ->
                viewModel.setTelegramBotToken(token, restartGateway = false)
                showTelegramTokenDialog = false
                showBotRestartNotice = true
            },
            onDisconnect = if (telegramBotToken.isNotBlank()) {
                {
                    showTelegramTokenDialog = false
                    viewModel.disconnectChannel(
                        channelId = "telegram",
                        channelLabel = context.getString(R.string.settings_channel_telegram),
                    )
                }
            } else {
                null
            },
            disconnectEnabled = !isChannelDisconnecting,
            onDismiss = { showTelegramTokenDialog = false },
        )
    }

    // ── Discord Bot Token Dialog ──
    if (showDiscordTokenDialog) {
        BotTokenInputDialog(
            currentToken = discordBotToken,
            channelName = stringResource(R.string.settings_channel_discord),
            tokenHint = stringResource(R.string.settings_bot_token_hint),
            helpUrl = "https://discord.com/developers/applications",
            helpText = stringResource(R.string.settings_bot_token_help, stringResource(R.string.settings_channel_discord)),
            onSave = { token ->
                viewModel.setDiscordBotToken(token, restartGateway = false)
                showDiscordTokenDialog = false
                showBotRestartNotice = true
            },
            onDisconnect = if (discordBotToken.isNotBlank()) {
                {
                    showDiscordTokenDialog = false
                    viewModel.disconnectChannel(
                        channelId = "discord",
                        channelLabel = context.getString(R.string.settings_channel_discord),
                    )
                }
            } else {
                null
            },
            disconnectEnabled = !isChannelDisconnecting,
            onDismiss = { showDiscordTokenDialog = false },
        )
    }

    if (showDiscordGuildAllowlistDialog) {
        MultilineTextInputDialog(
            title = stringResource(R.string.settings_discord_guild_allowlist_title),
            currentValue = discordGuildAllowlist,
            hint = stringResource(R.string.settings_discord_guild_allowlist_hint),
            helpText = stringResource(R.string.settings_discord_guild_allowlist_help),
            onSave = { raw ->
                viewModel.setDiscordGuildAllowlist(raw, restartGateway = false)
                showDiscordGuildAllowlistDialog = false
                showBotRestartNotice = true
            },
            onDismiss = { showDiscordGuildAllowlistDialog = false },
        )
    }

    // ── Brave Search API Key Dialog ──
    if (showBraveKeyDialog) {
        BraveSearchKeyDialog(
            currentKey = braveSearchApiKey,
            onSave = { key ->
                viewModel.setBraveSearchApiKey(key)
                showBraveKeyDialog = false
                showRestartHint = true
            },
            onDismiss = { showBraveKeyDialog = false },
        )
    }

    if (showMemorySearchProviderDialog) {
        MemorySearchProviderDialog(
            currentProvider = memorySearchProvider,
            onSelectProvider = { provider ->
                viewModel.setMemorySearchProvider(provider)
                showMemorySearchProviderDialog = false
                showRestartHint = true
            },
            onDismiss = { showMemorySearchProviderDialog = false },
        )
    }

    if (showMemorySearchApiKeyDialog) {
        MemorySearchApiKeyDialog(
            currentKey = memorySearchApiKey,
            onSave = { key ->
                viewModel.setMemorySearchApiKey(key)
                showMemorySearchApiKeyDialog = false
                showRestartHint = true
            },
            onDismiss = { showMemorySearchApiKeyDialog = false },
        )
    }

    if (showExecutionRuntimeDialog) {
        AlertDialog(
            onDismissRequest = { showExecutionRuntimeDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.settings_execution_runtime_dialog_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.settings_execution_runtime_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    listOf(
                        ExecutionRuntime.PROROOT.storageValue to stringResource(R.string.settings_execution_runtime_proroot),
                        ExecutionRuntime.PROOT.storageValue to stringResource(R.string.settings_execution_runtime_proot),
                    ).forEach { (runtimeValue, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setExecutionRuntime(runtimeValue)
                                    showExecutionRuntimeDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = executionRuntime == runtimeValue,
                                onClick = {
                                    viewModel.setExecutionRuntime(runtimeValue)
                                    showExecutionRuntimeDialog = false
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showExecutionRuntimeDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (showCodexAuthResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCodexAuthResetConfirmDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.settings_codex_oauth_reset_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_codex_oauth_reset_confirm_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isCodexAuthResetInProgress && !isCodexAuthDiagnosticsInProgress,
                    onClick = {
                        showCodexAuthResetConfirmDialog = false
                        viewModel.resetOpenAiCodexOAuth()
                    },
                ) {
                    Text(stringResource(R.string.settings_codex_oauth_reset_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCodexAuthResetConfirmDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    val currentCodexDebugLine = codexAuthDebugLine
    if (showCodexAuthDiagnosticsDialog && !currentCodexDebugLine.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { showCodexAuthDiagnosticsDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.settings_codex_oauth_diagnostics_title)) },
            text = {
                Text(
                    text = currentCodexDebugLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(currentCodexDebugLine))
                        Toast.makeText(
                            context,
                            context.getString(R.string.settings_codex_oauth_diagnostics_copied),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) {
                    Text(stringResource(R.string.settings_codex_oauth_diagnostics_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCodexAuthDiagnosticsDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (showExtensionPruneConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExtensionPruneConfirmDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.settings_openclaw_extension_prune_confirm_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_openclaw_extension_prune_confirm_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExtensionPruneConfirmDialog = false
                        viewModel.runOpenClawExtensionPrune()
                    },
                ) {
                    Text(stringResource(R.string.settings_openclaw_extension_prune_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExtensionPruneConfirmDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showOpenClawUpdateConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showOpenClawUpdateConfirmDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.settings_openclaw_update_action)) },
            text = {
                Text(
                    text = openClawVersionInfoText
                        ?: stringResource(R.string.settings_openclaw_update_action),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOpenClawUpdateConfirmDialog = false
                        viewModel.runOpenClawUpdate()
                    },
                ) {
                    Text(stringResource(R.string.settings_restart_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showOpenClawUpdateConfirmDialog = false }) {
                    Text(stringResource(R.string.settings_restart_confirm_no))
                }
            },
        )
    }

    if (showRestartHint) {
        AlertDialog(
            onDismissRequest = {
                showRestartHint = false
                if (openAiConnectionTransition.isPending) {
                    viewModel.cancelPendingOpenAiConnectionMode()
                }
            },
            shape = RoundedCornerShape(24.dp),
            text = { Text(stringResource(R.string.settings_restart_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRestartHint = false
                        viewModel.applyRuntimeLaunchConfigNow()
                    },
                ) {
                    Text(stringResource(R.string.settings_restart_confirm_yes))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRestartHint = false
                        if (openAiConnectionTransition.isPending) {
                            viewModel.cancelPendingOpenAiConnectionMode()
                        }
                    },
                ) {
                    Text(stringResource(R.string.settings_restart_confirm_no))
                }
            },
        )
    }

    if (showBotRestartNotice) {
        AlertDialog(
            onDismissRequest = { },
            shape = RoundedCornerShape(24.dp),
            text = { Text(stringResource(R.string.settings_bot_restart_notice_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBotRestartNotice = false
                        viewModel.restartGatewayIfRunningNow()
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (showOssLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showOssLicensesDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Open Source Licenses") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = ossLicensesText,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showOssLicensesDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (bugReportUiState.isVisible) {
        BugReportDialog(
            state = bugReportUiState,
            onDismiss = { viewModel.dismissBugReportDialog() },
            onConsentChanged = { viewModel.setBugReportConsent(it) },
            onGenerate = { viewModel.generateBugReportZip() },
            onSend = {
                val artifact = bugReportUiState.artifact ?: return@BugReportDialog
                val intent = BugReportEmailIntentBuilder.build(
                    artifact = artifact,
                    summary = BugReportEmailSummary(
                        sessionErrorCount = bugReportUiState.preview.sessionErrorCount,
                        hasGatewayError = bugReportUiState.preview.hasGatewayError,
                        hasProcessError = bugReportUiState.preview.hasProcessError,
                        gatewayLogCount = bugReportUiState.preview.gatewayLogCount,
                    ),
                    metadata = BugReportEmailMetadata(
                        appVersionName = BuildConfig.VERSION_NAME,
                        packageName = context.packageName,
                        androidSdkInt = Build.VERSION.SDK_INT,
                        deviceManufacturer = Build.MANUFACTURER.orEmpty(),
                        deviceModel = Build.MODEL.orEmpty(),
                        locale = Locale.getDefault().toLanguageTag(),
                    ),
                )
                try {
                    context.startActivity(
                        Intent.createChooser(intent, context.getString(R.string.bug_report_send_chooser_title))
                    )
                    viewModel.setBugReportGenerationErrorMessage(null)
                } catch (_: ActivityNotFoundException) {
                    viewModel.setBugReportGenerationErrorMessage(
                        context.getString(R.string.bug_report_no_email_app)
                    )
                }
            },
        )
    }

    if (showTransferExportPasswordDialog) {
        TransferPasswordDialog(
            title = stringResource(R.string.settings_transfer_export_dialog_title),
            password = transferUiState.passwords.exportPassword,
            confirmLabel = stringResource(R.string.settings_transfer_export_confirm),
            onPasswordChange = viewModel::setTransferExportPassword,
            onConfirm = {
                showTransferExportPasswordDialog = false
                viewModel.startTransferExport()
            },
            onDismiss = {
                showTransferExportPasswordDialog = false
                viewModel.clearTransferExportPassword()
            },
        )
    }

    if (showTransferImportPasswordDialog) {
        TransferPasswordDialog(
            title = stringResource(R.string.settings_transfer_import_dialog_title),
            password = transferUiState.passwords.importPassword,
            supportingText = pendingTransferImportFile?.name,
            confirmLabel = stringResource(R.string.settings_transfer_import_confirm),
            onPasswordChange = viewModel::setTransferImportPassword,
            onConfirm = {
                val importFile = pendingTransferImportFile ?: return@TransferPasswordDialog
                showTransferImportPasswordDialog = false
                viewModel.requestTransferImport(importFile)
            },
            onDismiss = {
                showTransferImportPasswordDialog = false
                pendingTransferImportFile = null
                viewModel.clearTransferImportPassword()
            },
        )
    }

    if (transferUiState.overwriteConfirmation.isRequired) {
        AlertDialog(
            onDismissRequest = {
                pendingTransferImportFile = null
                viewModel.cancelTransferImportOverwriteConfirmation()
                viewModel.clearTransferImportPassword()
            },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.settings_transfer_overwrite_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_transfer_overwrite_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmTransferImportOverwrite() }) {
                    Text(stringResource(R.string.settings_transfer_import_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingTransferImportFile = null
                        viewModel.cancelTransferImportOverwriteConfirmation()
                        viewModel.clearTransferImportPassword()
                    },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (transferUiState.versionMismatchConfirmation.isRequired) {
        AlertDialog(
            onDismissRequest = {
                pendingTransferImportFile = null
                viewModel.cancelTransferImportVersionMismatchConfirmation()
                viewModel.clearTransferImportPassword()
            },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.settings_transfer_version_mismatch_title)) },
            text = {
                Text(
                    text = stringResource(R.string.settings_transfer_version_mismatch_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmTransferImportVersionMismatch() }) {
                    Text(stringResource(R.string.settings_transfer_version_mismatch_yes))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingTransferImportFile = null
                        viewModel.cancelTransferImportVersionMismatchConfirmation()
                        viewModel.clearTransferImportPassword()
                    },
                ) {
                    Text(stringResource(R.string.settings_transfer_version_mismatch_no))
                }
            },
        )
    }

    when (transferUiState.exportAction.phase) {
        SettingsViewModel.TransferActionPhase.IN_PROGRESS -> {
            TransferStatusDialog(
                title = stringResource(R.string.settings_transfer_export_title),
                message = stringResource(R.string.settings_transfer_progress_export),
            )
        }

        SettingsViewModel.TransferActionPhase.SUCCESS -> {
            AlertDialog(
                onDismissRequest = { viewModel.clearTransferExportActionState() },
                shape = RoundedCornerShape(24.dp),
                title = { Text(stringResource(R.string.settings_transfer_success_title)) },
                text = {
                    Text(
                        text = transferUiState.exportAction.artifactPath
                            ?: stringResource(R.string.settings_transfer_export_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val path = transferUiState.exportAction.artifactPath
                            if (path != null) {
                                shareTransferArtifact(context, File(path))
                            }
                        },
                    ) {
                        Text(stringResource(R.string.settings_transfer_share))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.clearTransferExportActionState() }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }

        SettingsViewModel.TransferActionPhase.ERROR -> {
            AlertDialog(
                onDismissRequest = { viewModel.clearTransferExportActionState() },
                shape = RoundedCornerShape(24.dp),
                title = { Text(stringResource(R.string.dashboard_status_error)) },
                text = {
                    Text(
                        text = transferUiState.exportAction.message
                            ?: stringResource(R.string.settings_transfer_export_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearTransferExportActionState() }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }

        else -> Unit
    }

    when (transferUiState.importAction.phase) {
        SettingsViewModel.TransferActionPhase.IN_PROGRESS -> {
            TransferStatusDialog(
                title = stringResource(R.string.settings_transfer_import_title),
                message = stringResource(R.string.settings_transfer_progress_import),
            )
        }

        SettingsViewModel.TransferActionPhase.SUCCESS -> {
            AlertDialog(
                onDismissRequest = {
                    pendingTransferImportFile = null
                    viewModel.clearTransferImportActionState()
                },
                shape = RoundedCornerShape(24.dp),
                title = { Text(stringResource(R.string.settings_transfer_success_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.settings_transfer_import_success),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingTransferImportFile = null
                            viewModel.clearTransferImportActionState()
                        },
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }

        SettingsViewModel.TransferActionPhase.ERROR -> {
            val isTransientRuntimeFailure =
                transferUiState.importAction.failureReason == SettingsViewModel.TransferFailureUiReason.TRANSIENT_RUNTIME
            AlertDialog(
                onDismissRequest = {
                    pendingTransferImportFile = null
                    viewModel.clearTransferImportActionState()
                },
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        if (isTransientRuntimeFailure) {
                            stringResource(R.string.settings_transfer_success_title)
                        } else {
                            stringResource(R.string.dashboard_status_error)
                        },
                    )
                },
                text = {
                    Text(
                        text = transferUiState.importAction.message
                            ?: stringResource(R.string.settings_transfer_import_desc),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            pendingTransferImportFile = null
                            viewModel.clearTransferImportActionState()
                        },
                    ) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
        }

        else -> Unit
    }

    if (doctorFixResult != null) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeDoctorFixResult() },
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    if (doctorFixResult?.success == true) {
                        stringResource(R.string.settings_openclaw_doctor_fix_success)
                    } else {
                        stringResource(R.string.settings_openclaw_doctor_fix_failed)
                    },
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = doctorFixResult?.output.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeDoctorFixResult() }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (openClawExtensionPruneResult != null) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeOpenClawExtensionPruneResult() },
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    if (openClawExtensionPruneResult?.success == true) {
                        stringResource(R.string.settings_openclaw_extension_prune_done)
                    } else {
                        stringResource(R.string.settings_openclaw_extension_prune_failed)
                    },
                )
            },
            text = {
                Text(
                    text = openClawExtensionPruneResult?.output.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeOpenClawExtensionPruneResult() }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (isOpenClawExtensionPruneRunning) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.settings_openclaw_extension_prune_running),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (recoveryInstallResult != null) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeRecoveryInstallResult() },
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    if (recoveryInstallResult?.success == true) {
                        stringResource(R.string.dashboard_update_recovery_done)
                    } else {
                        stringResource(R.string.dashboard_update_recovery_failed)
                    },
                )
            },
            text = {
                Text(
                    text = recoveryInstallResult?.output.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeRecoveryInstallResult() }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (openClawUpdateResult != null) {
        AlertDialog(
            onDismissRequest = { viewModel.consumeOpenClawUpdateResult() },
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    if (openClawUpdateResult?.success == true) {
                        stringResource(R.string.dashboard_update_action_done)
                    } else {
                        stringResource(R.string.dashboard_update_action_failed)
                    },
                )
            },
            text = {
                Text(
                    text = openClawUpdateResult?.output.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.consumeOpenClawUpdateResult() }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (isRecoveryInstallRunning || isOpenClawUpdateRunning) {
        RecoveryInstallProgressDialog(
            isRecoveryInstallRunning = isRecoveryInstallRunning,
            isOpenClawUpdateRunning = isOpenClawUpdateRunning,
            progress = setupState.progress,
            stepLabel = stringResource(setupState.currentStep.displayNameRes),
        )
    }

    if (showRecoveryInstallConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRecoveryInstallConfirmDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(stringResource(R.string.dashboard_update_action_recover))
            },
            text = {
                Text(
                    text = stringResource(R.string.settings_recovery_install_confirm_message),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRecoveryInstallConfirmDialog = false
                        viewModel.runRecoveryInstall()
                    },
                ) {
                    Text(stringResource(R.string.dashboard_update_action_recover))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecoveryInstallConfirmDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showWhatsAppActionDialog) {
        AlertDialog(
            // 외부 터치/뒤로가기로 닫히지 않게 고정한다.
            // 사용자는 버튼으로만 명시적으로 닫거나 동작을 선택한다.
            onDismissRequest = {},
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.settings_channel_whatsapp)) },
            text = {
                Text(
                    text = if (isWhatsAppLinked) {
                        stringResource(R.string.whatsapp_connected)
                    } else {
                        stringResource(R.string.settings_whatsapp_desc)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWhatsAppActionDialog = false
                        viewModel.startWhatsAppQr()
                    },
                ) {
                    Text(stringResource(R.string.whatsapp_connect_btn))
                }
            },
            dismissButton = {
                if (isWhatsAppLinked) {
                    Row {
                        TextButton(onClick = { showWhatsAppActionDialog = false }) {
                            Text(stringResource(android.R.string.cancel))
                        }
                        TextButton(
                            enabled = !isChannelDisconnecting,
                            onClick = {
                                showWhatsAppActionDialog = false
                                viewModel.disconnectWhatsApp()
                            },
                        ) {
                            Text(stringResource(R.string.settings_channel_disconnect_action))
                        }
                    }
                } else {
                    TextButton(onClick = { showWhatsAppActionDialog = false }) {
                        Text(stringResource(android.R.string.cancel))
                    }
                }
            },
        )
    }

    if (isChannelDisconnecting) {
        ChannelDisconnectProgressDialog(
            channelLabel = disconnectingChannelLabel ?: stringResource(R.string.settings_channel_whatsapp),
        )
    }

    resolveSettingsError(
        channelDisconnectError = channelDisconnectError,
        openAiConnectionTransitionFailure = openAiConnectionTransition.failure,
    )?.let { observedError ->
        val consumeError = {
            when (observedError) {
                is SettingsErrorUiState.ChannelDisconnect ->
                    viewModel.consumeChannelDisconnectError()
                is SettingsErrorUiState.OpenAiTransition ->
                    viewModel.consumeOpenAiConnectionTransitionError(observedError.failure)
            }
        }
        AlertDialog(
            onDismissRequest = consumeError,
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.dashboard_status_error)) },
            text = { Text(observedError.message, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = consumeError) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    // ── WhatsApp QR Dialog ──
    if (whatsappQrState !is WhatsAppQrState.Idle) {
        WhatsAppQrDialog(
            state = whatsappQrState,
            onDismiss = { viewModel.cancelWhatsAppQr() },
            onStartGateway = { viewModel.startGatewayAndRetryWhatsAppQr() },
        )
    }
}

@Composable
private fun RecoveryInstallProgressDialog(
    isRecoveryInstallRunning: Boolean,
    isOpenClawUpdateRunning: Boolean,
    progress: Float,
    stepLabel: String,
) {
    val titleText = when {
        isOpenClawUpdateRunning -> stringResource(R.string.settings_openclaw_update_action)
        else -> stringResource(R.string.dashboard_update_action_recover)
    }
    val statusText = when {
        isOpenClawUpdateRunning -> null
        else -> stringResource(R.string.settings_openclaw_doctor_fix_running)
    }
    val descriptionText = when {
        isOpenClawUpdateRunning -> stringResource(R.string.settings_openclaw_update_running)
        else -> stringResource(R.string.settings_recovery_install_confirm_message)
    }

    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(54.dp))
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (statusText != null) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = descriptionText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                    )
                    Text(
                        text = "${(progress.coerceIn(0f, 1f) * 100).toInt()}% · $stepLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}


@Composable
private fun ChannelDisconnectProgressDialog(channelLabel: String) {
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(42.dp))
                Text(
                    text = channelLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.settings_channel_disconnect_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── Reusable Components ──

internal fun resolveApiKeyDialogModelId(
    provider: String,
    openAiCompatibleModelId: String,
    ollamaModelId: String,
    selectedModel: String,
): String {
    return when (provider) {
        "openai-compatible" -> openAiCompatibleModelId.removePrefix("openai-compatible/")
        "ollama", "ollama-cloud" -> ollamaModelId.removePrefix("ollama/").removePrefix("ollama-cloud/")
        else -> selectedModel
    }
}

internal fun shouldActivateCustomProviderApiSave(
    dialogProvider: String,
    globalPrimaryProvider: String,
): Boolean {
    return dialogProvider == globalPrimaryProvider &&
        (dialogProvider == "openai-compatible" || dialogProvider == "ollama")
}

internal fun customProviderBaseUrl(
    provider: String,
    openAiCompatibleBaseUrl: String,
    ollamaBaseUrl: String,
): String {
    return when (provider) {
        "openai-compatible" -> openAiCompatibleBaseUrl
        "ollama" -> ollamaBaseUrl
        else -> ""
    }
}

internal fun canSaveApiKeyDialog(
    provider: String,
    apiKey: String,
    baseUrl: String,
    modelId: String = "",
): Boolean {
    return when (provider) {
        "openai-compatible" -> {
            baseUrl.isNotBlank() &&
                modelId.isNotBlank() &&
                (
                    apiKey.isNotBlank() ||
                        PreferencesManager.isKnownKeylessOpenAiCompatibleBaseUrl(baseUrl)
                )
        }
        "ollama" -> baseUrl.isNotBlank()
        else -> apiKey.isNotBlank()
    }
}

internal data class ApiKeyRowState(
    val value: String,
    val isError: Boolean,
)

internal fun resolveApiKeyRowState(
    provider: String,
    apiKey: String,
    baseUrl: String,
    configuredLabel: String,
    notConfiguredLabel: String,
    notRequiredLabel: String,
): ApiKeyRowState {
    val trimmedProvider = provider.trim().lowercase(Locale.US)
    val trimmedApiKey = apiKey.trim()
    val isKeylessCompat =
        trimmedProvider == "openai-compatible" &&
            trimmedApiKey.isBlank() &&
            PreferencesManager.isKnownKeylessOpenAiCompatibleBaseUrl(baseUrl)
    val isKeylessOllama = trimmedProvider == "ollama" && trimmedApiKey.isBlank()
    return when {
        trimmedApiKey.isNotBlank() ->
            ApiKeyRowState(
                value = "$configuredLabel (${trimmedApiKey.take(8)}...)",
                isError = false,
            )

        isKeylessCompat ->
            ApiKeyRowState(
                value = notRequiredLabel,
                isError = false,
            )

        isKeylessOllama ->
            ApiKeyRowState(
                value = notRequiredLabel,
                isError = false,
            )

        else ->
            ApiKeyRowState(
                value = notConfiguredLabel,
                isError = true,
            )
    }
}

internal fun formatCanonicalSelectedModelId(
    provider: String,
    modelId: String,
): String {
    val canonicalProvider = canonicalSettingsProviderId(provider)
    val trimmedModelId = modelId.trim()
    if (trimmedModelId.isBlank()) return ""
    return when (canonicalProvider) {
        "openai" -> PreferencesManager.canonicalizeModelId("openai", trimmedModelId)
        "openai-compatible" -> trimmedModelId.removePrefix("openai-compatible/").trim()
        "ollama", "ollama-cloud" ->
            trimmedModelId.removePrefix("ollama/").removePrefix("ollama-cloud/").trim()
        else -> trimmedModelId
    }
}

internal fun formatSelectedModelLabel(
    provider: String,
    modelId: String,
): String {
    val canonicalProvider = canonicalSettingsProviderId(provider)
    val canonicalModelId = formatCanonicalSelectedModelId(canonicalProvider, modelId)
    return when (canonicalProvider) {
        "openrouter" -> canonicalModelId.removePrefix("openrouter/")
        "anthropic" -> canonicalModelId.removePrefix("anthropic/")
        "openai" -> canonicalModelId
        "github-copilot" -> canonicalModelId.removePrefix("github-copilot/")
        "google" -> canonicalModelId.removePrefix("google/")
        "zai" -> canonicalModelId.removePrefix("zai/")
        "kimi-coding" -> canonicalModelId.removePrefix("kimi-coding/")
        "minimax" -> canonicalModelId.removePrefix("minimax/")
        "ollama", "ollama-cloud" ->
            canonicalModelId.removePrefix("ollama/").removePrefix("ollama-cloud/")
        else -> canonicalModelId
    }
}

private fun copyTransferUriToCache(context: android.content.Context, uri: Uri): File? {
    return runCatching {
        val metadata = readTransferImportMetadata(context, uri)
        if (!isValidTransferImportCandidate(metadata)) {
            return null
        }
        val targetDir = context.cacheDir.resolve("shared").apply { mkdirs() }
        val targetFile = targetDir.resolve("imported-transfer.atf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                copyTransferStreamWithLimit(input, output)
            }
        } ?: return null
        targetFile
    }.getOrNull()
}

internal data class TransferImportMetadata(
    val displayName: String?,
    val sizeBytes: Long?,
)

private const val MAX_TRANSFER_IMPORT_BYTES = TransferSizeLimits.MAX_TRANSFER_PAYLOAD_BYTES

private fun readTransferImportMetadata(context: android.content.Context, uri: Uri): TransferImportMetadata {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
    return runCatching {
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                TransferImportMetadata(
                    displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null,
                    sizeBytes = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                )
            } else {
                TransferImportMetadata(displayName = null, sizeBytes = null)
            }
        } ?: TransferImportMetadata(displayName = null, sizeBytes = null)
    }.getOrDefault(TransferImportMetadata(displayName = null, sizeBytes = null))
}

internal fun isValidTransferImportCandidate(metadata: TransferImportMetadata): Boolean {
    val sizeBytes = metadata.sizeBytes
    if (sizeBytes != null && (sizeBytes <= 0L || sizeBytes > MAX_TRANSFER_IMPORT_BYTES)) {
        return false
    }
    val displayName = metadata.displayName?.trim().orEmpty()
    if (displayName.isBlank()) {
        return true
    }
    return displayName.lowercase(Locale.US).endsWith(".transfer")
}

internal fun copyTransferStreamWithLimit(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long = MAX_TRANSFER_IMPORT_BYTES,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var totalBytes = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        totalBytes += read
        if (totalBytes > maxBytes) {
            throw IllegalArgumentException("Transfer file is too large")
        }
        output.write(buffer, 0, read)
    }
}

private fun shareTransferArtifact(context: android.content.Context, artifactFile: File) {
    runCatching {
        val shareDir = context.cacheDir.resolve("shared").apply { mkdirs() }
        val sharedFile = shareDir.resolve(artifactFile.name)
        artifactFile.copyTo(sharedFile, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sharedFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.settings_transfer_share)))
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.settings_transfer_share_failed), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun TransferStatusDialog(
    title: String,
    message: String,
) {
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(42.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TransferPasswordDialog(
    title: String,
    password: String,
    confirmLabel: String,
    supportingText: String? = null,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                supportingText?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text(stringResource(R.string.settings_transfer_password_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = password.isNotBlank()) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun SectionHeader(
    title: String,
    icon: ImageVector,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun SettingToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    indent: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(
                start = if (indent) 36.dp else 20.dp,
                end = 20.dp,
                top = 14.dp,
                bottom = 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingClickableRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    valueColor: androidx.compose.ui.graphics.Color? = null,
    indent: Boolean = false,
    enabled: Boolean = true,
    valueMaxLines: Int = 1,
) {
    val titleColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }
    val resolvedValueColor = (valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant).let { color ->
        if (enabled) color else color.copy(alpha = 0.5f)
    }
    val arrowColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(
                start = if (indent) 36.dp else 20.dp,
                end = 20.dp,
                top = 14.dp,
                bottom = 14.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = if (indent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                color = titleColor,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = resolvedValueColor,
                maxLines = valueMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = arrowColor,
            modifier = Modifier.size(20.dp),
        )
    }
}

// ── Dialogs ──

@Composable
private fun ProviderSelectionDialog(
    currentProvider: String,
    onSelectProvider: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.settings_change_provider)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SETTINGS_PROVIDER_IDS.forEach { id ->
                    val label = when (id) {
                        "openrouter" -> stringResource(R.string.onboarding_provider_openrouter)
                        "anthropic" -> stringResource(R.string.onboarding_provider_anthropic)
                        "openai" -> stringResource(R.string.settings_memory_search_provider_openai)
                        "github-copilot" -> stringResource(R.string.onboarding_provider_github_copilot)
                        "google" -> stringResource(R.string.onboarding_provider_google)
                        "zai" -> stringResource(R.string.onboarding_provider_zai)
                        "kimi-coding" -> stringResource(R.string.onboarding_provider_kimi_coding)
                        "minimax" -> stringResource(R.string.onboarding_provider_minimax)
                        "ollama" -> stringResource(R.string.onboarding_provider_ollama)
                        "ollama-cloud" -> stringResource(R.string.onboarding_provider_ollama_cloud)
                        "openai-compatible" ->
                            stringResource(R.string.onboarding_provider_openai_compatible)
                        else -> id
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectProvider(id) }
                            .heightIn(min = 48.dp)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentProvider == id,
                            onClick = { onSelectProvider(id) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_api_key_cancel))
            }
        },
    )
}

@StringRes
internal fun openAiConnectionModeTitle(mode: OpenAiConnectionMode): Int = when (mode) {
    OpenAiConnectionMode.CODEX_SUBSCRIPTION -> R.string.settings_openai_codex_subscription
    OpenAiConnectionMode.PLATFORM_API_KEY -> R.string.settings_openai_platform_api_key
}

@StringRes
internal fun openAiConnectionModeDescription(mode: OpenAiConnectionMode): Int = when (mode) {
    OpenAiConnectionMode.CODEX_SUBSCRIPTION -> R.string.settings_openai_codex_subscription_desc
    OpenAiConnectionMode.PLATFORM_API_KEY -> R.string.settings_openai_platform_api_key_desc
}

@Composable
private fun OpenAiConnectionModeDialog(
    items: List<OpenAiConnectionModeUiItem>,
    isTransitionPending: Boolean,
    onSelectMode: (OpenAiConnectionMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.settings_openai_connection_method)) },
        text = {
            Column {
                items.forEachIndexed { index, item ->
                    val enabled = !isTransitionPending
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = enabled) { onSelectMode(item.mode) }
                            .heightIn(min = 72.dp)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = item.isCurrent,
                            enabled = enabled,
                            onClick = { onSelectMode(item.mode) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(openAiConnectionModeTitle(item.mode)),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = stringResource(openAiConnectionModeDescription(item.mode)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = when (item.credentialStatus) {
                                    OpenAiCredentialUiStatus.CONFIGURED ->
                                        stringResource(R.string.settings_connection_status_connected)
                                    OpenAiCredentialUiStatus.NOT_CONFIGURED ->
                                        stringResource(R.string.settings_connection_status_not_connected)
                                    OpenAiCredentialUiStatus.IN_PROGRESS ->
                                        stringResource(R.string.settings_codex_oauth_signing_in)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (item.credentialStatus) {
                                    OpenAiCredentialUiStatus.CONFIGURED ->
                                        MaterialTheme.colorScheme.primary
                                    OpenAiCredentialUiStatus.NOT_CONFIGURED ->
                                        MaterialTheme.colorScheme.error
                                    OpenAiCredentialUiStatus.IN_PROGRESS ->
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (index < items.lastIndex) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_api_key_cancel))
            }
        },
    )
}

@Composable
private fun MemorySearchProviderDialog(
    currentProvider: String,
    onSelectProvider: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val providers = listOf(
        "auto" to stringResource(R.string.settings_memory_search_provider_auto),
        "openai" to stringResource(R.string.settings_memory_search_provider_openai),
        "gemini" to stringResource(R.string.settings_memory_search_provider_gemini),
        "voyage" to stringResource(R.string.settings_memory_search_provider_voyage),
        "mistral" to stringResource(R.string.settings_memory_search_provider_mistral),
        "local" to stringResource(R.string.settings_memory_search_provider_local),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.settings_memory_search_provider_dialog_title)) },
        text = {
            Column {
                providers.forEach { (id, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectProvider(id) }
                            .heightIn(min = 48.dp)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentProvider == id,
                            onClick = { onSelectProvider(id) },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_api_key_cancel))
            }
        },
    )
}

internal fun shouldUseApiKeyDialog(provider: String): Boolean {
    return canonicalSettingsProviderId(provider) != "github-copilot"
}

@Composable
private fun ApiKeyInputDialog(
    currentKey: String,
    provider: String,
    currentBaseUrl: String,
    currentModelId: String,
    onSave: (String) -> Unit,
    onSaveOpenAiCompatible: (String, String, String) -> Unit,
    onSaveOllama: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyText by remember { mutableStateOf(currentKey) }
    var baseUrlText by remember { mutableStateOf(currentBaseUrl) }
    var modelIdText by remember { mutableStateOf(currentModelId) }
    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isOpenAiCompatible = provider == "openai-compatible"
    val isOllama = provider == "ollama"
    val isCustomProvider = isOpenAiCompatible || isOllama
    val needsModelIdInput = isOpenAiCompatible
    val providerDisplayName = when (canonicalSettingsProviderId(provider)) {
        "openrouter" -> stringResource(R.string.onboarding_provider_openrouter)
        "anthropic" -> stringResource(R.string.onboarding_provider_anthropic)
        "openai" -> stringResource(R.string.settings_memory_search_provider_openai)
        "github-copilot" -> stringResource(R.string.onboarding_provider_github_copilot)
        "google" -> stringResource(R.string.onboarding_provider_google)
        "zai" -> stringResource(R.string.onboarding_provider_zai)
        "kimi-coding" -> stringResource(R.string.onboarding_provider_kimi_coding)
        "minimax" -> stringResource(R.string.onboarding_provider_minimax)
        "ollama" -> stringResource(R.string.onboarding_provider_ollama)
        "ollama-cloud" -> stringResource(R.string.onboarding_provider_ollama_cloud)
        "openai-compatible" -> stringResource(R.string.onboarding_provider_openai_compatible)
        else -> provider.replaceFirstChar { it.uppercase() }
    }

    val settingsUrl = when (provider) {
        "openrouter" -> "https://openrouter.ai/keys"
        "anthropic" -> "https://console.anthropic.com/settings/keys"
        "openai" -> "https://platform.openai.com/api-keys"
        "github-copilot" -> "https://github.com/settings/copilot"
        "google" -> "https://aistudio.google.com/apikey"
        "zai" -> "https://docs.z.ai/guides/overview/quick-start"
        "kimi-coding" -> "https://www.kimi.com/coding/docs/en/"
        "minimax" -> "https://platform.minimax.io/document/Quickstart"
        "ollama" -> "https://docs.openclaw.ai/providers/ollama"
        "ollama-cloud" -> "https://ollama.com/settings/keys"
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                if (isOllama) {
                    stringResource(R.string.settings_ollama_host_address)
                } else {
                    stringResource(R.string.settings_api_key)
                }
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isOllama) {
                    OutlinedTextField(
                        value = keyText,
                        onValueChange = { keyText = it },
                        label = { Text(stringResource(R.string.settings_api_key_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                )
                            }
                        },
                    )
                }
                if (isCustomProvider) {
                    OutlinedTextField(
                        value = baseUrlText,
                        onValueChange = { baseUrlText = it },
                        label = {
                            Text(
                                if (isOllama) {
                                    stringResource(R.string.settings_ollama_base_url)
                                } else {
                                    stringResource(R.string.settings_openai_compatible_base_url)
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (needsModelIdInput) {
                        OutlinedTextField(
                            value = modelIdText,
                            onValueChange = { modelIdText = it },
                            label = { Text(stringResource(R.string.settings_openai_compatible_model_id)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (settingsUrl != null) {
                    Text(
                        text = if (isOllama) {
                            stringResource(R.string.settings_ollama_docs_link)
                        } else {
                            stringResource(R.string.settings_api_key_get_link, providerDisplayName)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(settingsUrl)))
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (isOpenAiCompatible) {
                        onSaveOpenAiCompatible(
                            keyText.trim(),
                            baseUrlText.trim(),
                            modelIdText.trim(),
                        )
                    } else if (isOllama) {
                        onSaveOllama(
                            keyText.trim(),
                            baseUrlText.trim(),
                        )
                    } else {
                        onSave(keyText.trim())
                    }
                },
                enabled = canSaveApiKeyDialog(
                    provider = provider,
                    apiKey = keyText.trim(),
                    baseUrl = baseUrlText.trim(),
                    modelId = modelIdText.trim(),
                ),
            ) {
                Text(stringResource(R.string.settings_api_key_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_api_key_cancel))
            }
        },
    )
}

@Composable
private fun BotTokenInputDialog(
    currentToken: String,
    channelName: String,
    tokenHint: String,
    helpUrl: String,
    helpText: String,
    onSave: (String) -> Unit,
    onDisconnect: (() -> Unit)? = null,
    disconnectEnabled: Boolean = true,
    onDismiss: () -> Unit,
) {
    var tokenText by remember { mutableStateOf(currentToken) }
    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.settings_bot_token_dialog_title, channelName)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it },
                    label = { Text(tokenHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
                Text(
                    text = helpText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(helpUrl)))
                    },
                )
                if (onDisconnect != null) {
                    TextButton(
                        onClick = onDisconnect,
                        enabled = disconnectEnabled,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_channel_disconnect_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(tokenText.trim()) },
                enabled = tokenText.isNotBlank(),
            ) {
                Text(stringResource(R.string.settings_api_key_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_api_key_cancel))
            }
        },
    )
}

@Composable
private fun BraveSearchKeyDialog(
    currentKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyText by remember { mutableStateOf(currentKey) }
    var passwordVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.settings_brave_search_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_brave_search_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text(stringResource(R.string.settings_api_key_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
                Text(
                    text = stringResource(R.string.settings_brave_search_get_key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://brave.com/search/api/"))
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(keyText.trim()) },
            ) {
                Text(stringResource(R.string.settings_api_key_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_api_key_cancel))
            }
        },
    )
}

@Composable
private fun MemorySearchApiKeyDialog(
    currentKey: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var keyText by remember { mutableStateOf(currentKey) }
    var passwordVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.settings_memory_search_api_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_memory_search_api_key_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = keyText,
                    onValueChange = { keyText = it },
                    label = { Text(stringResource(R.string.settings_api_key_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(keyText.trim()) }) {
                Text(stringResource(R.string.settings_api_key_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_api_key_cancel))
            }
        },
    )
}

@Composable
private fun MultilineTextInputDialog(
    title: String,
    currentValue: String,
    hint: String,
    helpText: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(hint) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                )
                Text(
                    text = helpText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(R.string.settings_api_key_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_api_key_cancel))
            }
        },
    )
}

@Composable
private fun BugReportDialog(
    state: SettingsViewModel.BugReportUiState,
    onDismiss: () -> Unit,
    onConsentChanged: (Boolean) -> Unit,
    onGenerate: () -> Unit,
    onSend: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = { Text(stringResource(R.string.bug_report_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.bug_report_included),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.bug_report_included_session_errors, state.preview.sessionErrorCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        R.string.bug_report_included_gateway_process,
                        stringResource(
                            if (state.preview.hasGatewayError || state.preview.hasProcessError) {
                                R.string.bug_report_presence_present
                            } else {
                                R.string.bug_report_presence_none
                            }
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.bug_report_included_app_device_metadata),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.bug_report_included_gateway_logs, state.preview.gatewayLogCount),
                    style = MaterialTheme.typography.bodySmall,
                )

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.bug_report_excluded),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.bug_report_excluded_conversation),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(R.string.bug_report_excluded_content_preview),
                    style = MaterialTheme.typography.bodySmall,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onConsentChanged(!state.hasConsent) }
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.hasConsent,
                        onCheckedChange = { onConsentChanged(it) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.bug_report_consent_checkbox),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                if (state.isGenerating) {
                    Text(
                        text = stringResource(R.string.bug_report_generating),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                state.artifactInfo?.let { artifact ->
                    Text(
                        text = stringResource(
                            R.string.bug_report_generated,
                            artifact.fileName,
                            formatFileSize(artifact.sizeBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                state.generationErrorMessage?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.artifact != null) {
                    TextButton(
                        onClick = onSend,
                        enabled = !state.isGenerating,
                    ) {
                        Text(stringResource(R.string.bug_report_send_email))
                    }
                }
                TextButton(
                    onClick = onGenerate,
                    enabled = state.hasConsent && !state.isGenerating,
                ) {
                    Text(stringResource(R.string.bug_report_generate_action))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isGenerating,
            ) {
                Text(stringResource(R.string.bug_report_close))
            }
        },
    )
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

internal fun formatExecutionRuntimeLabel(
    runtime: String,
    prorootLabel: String,
    prootLabel: String,
): String {
    return when (ExecutionRuntime.fromStorageValue(runtime)) {
        ExecutionRuntime.PROOT -> prootLabel
        ExecutionRuntime.PROROOT -> prorootLabel
    }
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

private fun discordGuildAllowlistSummary(
    raw: String,
    notConfigured: String,
    configuredFormat: String,
): String {
    val ids = parseDiscordGuildAllowlist(raw)
    return if (ids.isEmpty()) {
        notConfigured
    } else {
        String.format(configuredFormat, ids.size)
    }
}

private fun formatFileSize(sizeBytes: Long): String {
    if (sizeBytes < 1024) return "$sizeBytes B"
    if (sizeBytes < 1024 * 1024) {
        val kb = sizeBytes / 1024.0
        return String.format("%.1f KB", kb)
    }
    val mb = sizeBytes / (1024.0 * 1024.0)
    return String.format("%.2f MB", mb)
}
