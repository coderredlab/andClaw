package com.coderred.andclaw.ui.screen.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coderred.andclaw.R
import com.coderred.andclaw.data.SetupStep
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.PairingRequest
import com.coderred.andclaw.proroot.BundleUpdateFailureState
import com.coderred.andclaw.ui.component.KeepScreenOnEffect
import com.coderred.andclaw.ui.component.SessionLogsDialog
import com.coderred.andclaw.ui.screen.settings.SETTINGS_SECTION_TOOLS
import com.coderred.andclaw.ui.theme.StatusError
import com.coderred.andclaw.ui.theme.StatusRunning
import com.coderred.andclaw.ui.theme.StatusStopped
import com.coderred.andclaw.ui.theme.StatusWarning
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToSettings: (String?, String?) -> Unit,
    viewModel: DashboardViewModel = viewModel(),
) {
    val gatewayUiState by viewModel.gatewayUiState.collectAsState()
    val logLines by viewModel.logLines.collectAsState()
    val batteryLevel by viewModel.batteryLevel.collectAsState()
    val isCharging by viewModel.isCharging.collectAsState()
    val memoryUsageMb by viewModel.memoryUsageMb.collectAsState()
    val isLogSectionUnlocked by viewModel.isLogSectionUnlocked.collectAsState()
    val pairingRequests by viewModel.pairingRequests.collectAsState()
    val pairingActionProgress by viewModel.pairingActionProgress.collectAsState()
    val sessionLogs by viewModel.sessionLogs.collectAsState()
    val isLoadingSessionLogs by viewModel.isLoadingSessionLogs.collectAsState()
    val bundleUpdateFailure by viewModel.bundleUpdateFailure.collectAsState()
    val bundleActionInProgress by viewModel.bundleActionInProgress.collectAsState()
    val bundleActionMessage by viewModel.bundleActionMessage.collectAsState()
    val bundleActionType by viewModel.bundleActionType.collectAsState()
    val runtimeChangeGuidanceDialog by viewModel.runtimeChangeGuidanceDialog.collectAsState()
    val setupState by viewModel.setupState.collectAsState()
    val context = LocalContext.current
    KeepScreenOnEffect(enabled = bundleActionInProgress)

    var showApiKeyWarning by remember { mutableStateOf(false) }
    var apiKeyWarningProvider by remember { mutableStateOf<String?>(null) }
    var showSessionLogs by remember { mutableStateOf(false) }
    var logsExpanded by rememberSaveable { mutableStateOf(false) }

    // 알림 권한 요청 (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 권한 허용/거부 관계없이 게이트웨이 시작 (알림은 선택사항)
        viewModel.startGateway()
    }

    val requestStartGateway: () -> Unit = {
        viewModel.getLaunchApiKeyWarning { warning ->
            when (
                resolveDashboardStartAction(
                    warning = warning,
                    shouldRequestNotificationPermission =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED,
                )
            ) {
                is DashboardStartAction.ShowNoModelsNotice -> {
                    viewModel.stopGatewayForMissingModelSelection()
                }

                is DashboardStartAction.ShowApiKeyWarning -> {
                    apiKeyWarningProvider = warning.provider
                    showApiKeyWarning = true
                }

                is DashboardStartAction.RequestNotificationPermission -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                is DashboardStartAction.StartGateway -> {
                    viewModel.startGateway()
                }
            }
        }
    }

    val logListState = rememberLazyListState()

    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty() && logsExpanded) {
            logListState.animateScrollToItem(logLines.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("andClaw") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
                actions = {
                    IconButton(onClick = { onNavigateToSettings(null, null) }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.dashboard_cd_settings),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Hero Section — full bleed, colored background ──
            StatusHero(
                status = gatewayUiState.status,
                error = gatewayUiState.error,
                onErrorAcknowledged = viewModel::acknowledgeError,
                onStart = requestStartGateway,
                onStop = { viewModel.stopGateway() },
                onRestart = { viewModel.restartGateway() },
                onOpenDashboard = { viewModel.openDashboard(context) },
                dashboardReady = gatewayUiState.dashboardReady,
                actionsEnabled = gatewayUiState.actionsEnabled,
            )

            // ── Content below hero ──
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                bundleUpdateFailure?.let { failure ->
                    BundleUpdateFailureBanner(
                        failure = failure,
                        inProgress = bundleActionInProgress,
                        actionMessage = bundleActionMessage,
                        onRetry = { viewModel.retryBundleUpdate() },
                        onRecover = { viewModel.recoverBundleInstall() },
                    )
                }

                // ── Pairing Requests ──
                if (pairingRequests.isNotEmpty()) {
                    PairingRequestsCard(
                        requests = pairingRequests,
                        actionsEnabled = pairingActionProgress == null,
                        onApprove = { req -> viewModel.approvePairing(req) },
                        onDeny = { req -> viewModel.denyPairing(req) },
                    )
                }

                // ── Stats Row ──
                StatsRow(
                    memoryMb = memoryUsageMb.toInt(),
                    batteryLevel = batteryLevel,
                    isCharging = isCharging,
                )

                if (isLogSectionUnlocked) {
                    // ── Logs ──
                    LogSection(
                        logLines = logLines,
                        expanded = logsExpanded,
                        onToggle = { logsExpanded = !logsExpanded },
                        listState = logListState,
                        onShowSessionLogs = {
                            showSessionLogs = true
                            viewModel.loadSessionLogs()
                        },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    if (showSessionLogs) {
        SessionLogsDialog(
            entries = sessionLogs,
            isLoading = isLoadingSessionLogs,
            onDismiss = { showSessionLogs = false },
        )
    }

    if (showApiKeyWarning) {
        val warningProviderLabel = apiKeyWarningProvider?.let { provider ->
            when (provider) {
                "openrouter" -> stringResource(R.string.onboarding_provider_openrouter)
                "anthropic" -> stringResource(R.string.onboarding_provider_anthropic)
                "openai" -> stringResource(R.string.settings_provider_openai_api)
                "openai-codex" -> stringResource(R.string.settings_provider_openai_codex)
                "github-copilot" -> stringResource(R.string.onboarding_provider_github_copilot)
                "zai" -> stringResource(R.string.onboarding_provider_zai)
                "kimi-coding" -> stringResource(R.string.onboarding_provider_kimi_coding)
                "minimax" -> stringResource(R.string.onboarding_provider_minimax)
                "openai-compatible" -> stringResource(R.string.onboarding_provider_openai_compatible)
                "google" -> stringResource(R.string.onboarding_provider_google)
                else -> provider
            }
        }
        AlertDialog(
            onDismissRequest = {
                showApiKeyWarning = false
                apiKeyWarningProvider = null
            },
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.dashboard_apikey_required_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.dashboard_apikey_required_message))
                    if (!warningProviderLabel.isNullOrBlank()) {
                        Text(
                            text = warningProviderLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showApiKeyWarning = false
                    onNavigateToSettings(apiKeyWarningProvider, null)
                    apiKeyWarningProvider = null
                }) {
                    Text(stringResource(R.string.dashboard_apikey_go_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showApiKeyWarning = false
                    apiKeyWarningProvider = null
                }) {
                    Text(stringResource(R.string.dashboard_apikey_dismiss))
                }
            },
        )
    }

    runtimeChangeGuidanceDialog?.let {
        AlertDialog(
            onDismissRequest = {},
            shape = RoundedCornerShape(24.dp),
            title = { Text(stringResource(R.string.dashboard_runtime_change_required_title)) },
            text = { Text(stringResource(R.string.dashboard_runtime_change_required_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.consumeRuntimeChangeGuidanceDialog()
                    onNavigateToSettings(null, SETTINGS_SECTION_TOOLS)
                }) {
                    Text(stringResource(R.string.dashboard_runtime_change_required_action))
                }
            },
        )
    }

    pairingActionProgress?.let { progress ->
        PairingActionProgressDialog(
            actionLabel = when (progress.action) {
                PairingActionType.APPROVE -> stringResource(R.string.pairing_approve)
                PairingActionType.DENY -> stringResource(R.string.pairing_deny)
            },
            request = progress.request,
        )
    }

    if (bundleActionInProgress) {
        BundleActionProgressDialog(
            titleText = when (bundleActionType) {
                BundleActionType.RECOVERY -> stringResource(R.string.dashboard_update_action_recover)
                else -> stringResource(R.string.dashboard_update_action_retry)
            },
            descriptionText = when (bundleActionType) {
                BundleActionType.RECOVERY -> stringResource(R.string.settings_recovery_install_confirm_message)
                else -> stringResource(R.string.dashboard_update_failed_title)
            },
            stepLabel = stringResource(setupState.currentStep.displayNameRes),
            progress = setupState.progress,
        )
    }
}

internal sealed interface DashboardStartAction {
    data object ShowNoModelsNotice : DashboardStartAction
    data object RequestNotificationPermission : DashboardStartAction
    data object StartGateway : DashboardStartAction
    data class ShowApiKeyWarning(val provider: String) : DashboardStartAction
}

internal fun resolveDashboardStartAction(
    warning: com.coderred.andclaw.data.LaunchApiKeyWarning,
    shouldRequestNotificationPermission: Boolean,
): DashboardStartAction {
    return when {
        !warning.hasSelectedModels -> DashboardStartAction.ShowNoModelsNotice
        warning.shouldWarn -> DashboardStartAction.ShowApiKeyWarning(warning.provider)
        shouldRequestNotificationPermission -> DashboardStartAction.RequestNotificationPermission
        else -> DashboardStartAction.StartGateway
    }
}

@Composable
private fun BundleUpdateFailureBanner(
    failure: BundleUpdateFailureState,
    inProgress: Boolean,
    actionMessage: String?,
    onRetry: () -> Unit,
    onRecover: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.dashboard_update_failed_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = stringResource(
                    R.string.dashboard_update_failed_reason,
                    failure.lastFailureType ?: "UNKNOWN",
                    failure.lastError ?: stringResource(R.string.notification_unknown),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (failure.inCooldown) {
                Text(
                    text = stringResource(
                        R.string.dashboard_update_failed_cooldown,
                        ((failure.cooldownRemainingMs / 60000L).coerceAtLeast(0L)).toInt(),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            failure.lastFailAtEpochMs?.let { failedAt ->
                Text(
                    text = stringResource(
                        R.string.dashboard_update_failed_last_at,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(failedAt)),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            actionMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onRetry,
                    enabled = !inProgress,
                ) {
                    Text(stringResource(R.string.dashboard_update_action_retry))
                }
                OutlinedButton(
                    onClick = onRecover,
                    enabled = !inProgress,
                ) {
                    Text(stringResource(R.string.dashboard_update_action_recover))
                }
            }
        }
    }
}

// ── Hero Section ──

@Composable
private fun StatusHero(
    status: GatewayStatus,
    error: DashboardErrorUiState?,
    onErrorAcknowledged: (DashboardErrorUiState) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenDashboard: () -> Unit,
    dashboardReady: Boolean,
    actionsEnabled: Boolean,
) {
    val statusColor by animateColorAsState(
        targetValue = when (status) {
            GatewayStatus.RUNNING -> StatusRunning
            GatewayStatus.STARTING, GatewayStatus.STOPPING -> StatusWarning
            GatewayStatus.ERROR -> StatusError
            GatewayStatus.STOPPED -> StatusStopped
        },
        label = "statusColor",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    val dotAlpha = when (status) {
        GatewayStatus.STARTING, GatewayStatus.STOPPING -> pulseAlpha
        else -> 1f
    }

    // Full-width gradient hero
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        statusColor.copy(alpha = 0.08f),
                        Color.Transparent,
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Big status dot
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .alpha(dotAlpha)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                statusColor,
                                statusColor.copy(alpha = 0.3f),
                            ),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(statusColor),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status text — big and centered
            Text(
                text = when (status) {
                    GatewayStatus.RUNNING -> stringResource(R.string.dashboard_status_running)
                    GatewayStatus.STARTING -> stringResource(R.string.dashboard_status_starting)
                    GatewayStatus.STOPPING -> stringResource(R.string.dashboard_status_stopping)
                    GatewayStatus.ERROR -> stringResource(R.string.dashboard_status_error)
                    GatewayStatus.STOPPED -> stringResource(R.string.dashboard_status_stopped)
                },
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Error chip
            error?.let { visibleError ->
                Spacer(modifier = Modifier.height(8.dp))
                val noticeContainerColor =
                    if (status == GatewayStatus.ERROR) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                val noticeContentColor =
                    if (status == GatewayStatus.ERROR) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = noticeContainerColor,
                ) {
                    if (visibleError.source == DashboardErrorSource.OPEN_AI_TRANSITION) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = visibleError.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = noticeContentColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 8.dp),
                            )
                            IconButton(onClick = { onErrorAcknowledged(visibleError) }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.settings_model_close),
                                    tint = noticeContentColor,
                                )
                            }
                        }
                    } else {
                        Text(
                            text = visibleError.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = noticeContentColor,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Action buttons — full width, stacked
            when (status) {
                GatewayStatus.STOPPED, GatewayStatus.ERROR -> {
                    Button(
                        onClick = onStart,
                        enabled = actionsEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.dashboard_btn_start),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                GatewayStatus.RUNNING -> {
                    Button(
                        onClick = onOpenDashboard,
                        enabled = dashboardReady && actionsEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Dashboard",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilledTonalButton(
                            onClick = onStop,
                            enabled = actionsEnabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dashboard_btn_stop))
                        }
                        OutlinedButton(
                            onClick = onRestart,
                            enabled = actionsEnabled,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.dashboard_btn_restart))
                        }
                    }
                }
                GatewayStatus.STARTING -> {
                    OutlinedButton(
                        onClick = onStop,
                        enabled = actionsEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.dashboard_btn_stop))
                    }
                }
                else -> { /* Stopping — no buttons */ }
            }
        }
    }
}

// ── Stats Row ──

@Composable
private fun StatsRow(
    memoryMb: Int,
    batteryLevel: Int,
    isCharging: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Header
        Text(
            text = "Status",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        // Stats as horizontal items inside a card
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ) {
            Column {
                StatItem(
                    icon = Icons.Default.Memory,
                    iconTint = MaterialTheme.colorScheme.secondary,
                    label = stringResource(R.string.dashboard_label_memory),
                    value = stringResource(R.string.dashboard_memory_value, memoryMb),
                )
                StatItem(
                    icon = Icons.Default.BatteryChargingFull,
                    iconTint = if (isCharging) StatusRunning else MaterialTheme.colorScheme.onSurfaceVariant,
                    label = stringResource(R.string.dashboard_label_battery),
                    value = "${batteryLevel}%${if (isCharging) " ⚡" else ""}",
                    isLast = true,
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    isLast: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = iconTint.copy(alpha = 0.12f),
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    if (!isLast) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(0.5.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        )
    }
}

// ── Log Section ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSection(
    logLines: List<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onShowSessionLogs: () -> Unit,
) {
    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.dashboard_log_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (logLines.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Text(
                            text = "${logLines.size}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }
                IconButton(
                    onClick = onShowSessionLogs,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = "Session Logs",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Collapsed: last 2 lines preview
            if (!expanded && logLines.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    logLines.takeLast(2).forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Expanded: full log
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                if (logLines.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(80.dp)
                            .padding(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_log_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .height(220.dp)
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            items(logLines) { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                    ),
                                    color = when {
                                        line.contains("error", ignoreCase = true) -> StatusError
                                        line.contains("[andClaw]") -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Pairing Requests Card ──

@Composable
private fun PairingRequestsCard(
    requests: List<PairingRequest>,
    actionsEnabled: Boolean,
    onApprove: (PairingRequest) -> Unit,
    onDeny: (PairingRequest) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Default.Cable,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.pairing_requests_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            requests.forEach { request ->
                PairingRequestItem(
                    request = request,
                    actionsEnabled = actionsEnabled,
                    onApprove = { onApprove(request) },
                    onDeny = { onDeny(request) },
                )
            }
        }
    }
}

@Composable
private fun PairingRequestItem(
    request: PairingRequest,
    actionsEnabled: Boolean,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = request.channel.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (request.username.isNotBlank()) request.username else request.code,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (request.username.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.pairing_code_label, request.code),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDeny,
                    enabled = actionsEnabled,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Text(
                        stringResource(R.string.pairing_deny),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Button(
                    onClick = onApprove,
                    enabled = actionsEnabled,
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Text(
                        stringResource(R.string.pairing_approve),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingActionProgressDialog(
    actionLabel: String,
    request: PairingRequest,
) {
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Text(
                    text = "$actionLabel...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (request.username.isNotBlank()) request.username else request.code,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.pairing_code_label, request.code),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun BundleActionProgressDialog(
    titleText: String,
    descriptionText: String,
    stepLabel: String,
    progress: Float,
) {
    val safeProgress = progress.coerceIn(0f, 1f)
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
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = descriptionText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { safeProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                    )
                    Text(
                        text = "${(safeProgress * 100).toInt()}% · $stepLabel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

