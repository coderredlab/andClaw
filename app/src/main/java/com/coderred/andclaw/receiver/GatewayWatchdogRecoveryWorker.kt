package com.coderred.andclaw.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.coderred.andclaw.MainActivity
import com.coderred.andclaw.R
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.data.resolveGatewaySurvivorRuntime
import com.coderred.andclaw.data.resolveGatewaySurvivorStartupAttemptAgeSeconds
import com.coderred.andclaw.proroot.ExecutionRuntime
import com.coderred.andclaw.service.GatewayService
import kotlinx.coroutines.flow.first

class GatewayWatchdogRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val UNIQUE_WORK_NAME = "gateway_watchdog_recovery"
        private const val CHANNEL_ID = "andclaw_watchdog_recovery"
        private const val NOTIFICATION_ID = 30101
        private const val HEALTH_PROBE_TIMEOUT_MS = 15_000L
        private const val RUNNING_HEALTH_GRACE_PERIOD_SECONDS = 90L

        internal enum class RecoveryAction {
            NONE,
            ATTACH,
            RESTART,
            START,
        }

        internal fun determineRecoveryAction(
            status: GatewayStatus?,
            serviceActive: Boolean,
            runningHealthy: Boolean,
            startupAttemptActive: Boolean = false,
            startupUptimeSeconds: Long = 0L,
            startupGracePeriodSeconds: Long = GatewayWatchdogReceiver.resolveStartingRecoveryGracePeriodSeconds(
                ExecutionRuntime.PROROOT,
            ),
            runningUptimeSeconds: Long = Long.MAX_VALUE,
        ): RecoveryAction {
            if (startupAttemptActive && startupUptimeSeconds < startupGracePeriodSeconds) {
                return RecoveryAction.NONE
            }
            if (startupAttemptActive && startupUptimeSeconds >= startupGracePeriodSeconds) {
                return RecoveryAction.RESTART
            }

            return when (status) {
                null,
                GatewayStatus.STOPPED,
                GatewayStatus.ERROR -> RecoveryAction.START
                GatewayStatus.RUNNING -> {
                    when {
                        !serviceActive && runningHealthy -> RecoveryAction.ATTACH
                        !runningHealthy && runningUptimeSeconds < RUNNING_HEALTH_GRACE_PERIOD_SECONDS -> RecoveryAction.NONE
                        !runningHealthy -> RecoveryAction.RESTART
                        serviceActive -> RecoveryAction.NONE
                        else -> RecoveryAction.RESTART
                    }
                }
                GatewayStatus.STARTING -> {
                    if (startupUptimeSeconds >= startupGracePeriodSeconds) {
                        RecoveryAction.RESTART
                    } else {
                        RecoveryAction.NONE
                    }
                }
                GatewayStatus.STOPPING -> RecoveryAction.NONE
            }
        }

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<GatewayWatchdogRecoveryWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }

    override suspend fun doWork(): Result {
        val prefs = PreferencesManager(applicationContext)
        val shouldKeepRunning = prefs.gatewayWasRunning.first() && prefs.isSetupComplete.first()
        if (!shouldKeepRunning) {
            GatewayWatchdogReceiver.cancel(applicationContext)
            return Result.success()
        }

        val processManager = GatewayService.processManager
        var gatewayState = processManager?.gatewayState?.value
        var status = gatewayState?.status
        val serviceActive = GatewayService.isInstanceActive
        val survivorMetadata = prefs.getGatewaySurvivorMetadata()
        val startupAttemptAgeSeconds = resolveGatewaySurvivorStartupAttemptAgeSeconds(
            liveStartupAttemptAgeSeconds = processManager?.startupAttemptAgeSeconds(),
            processManagerAvailable = processManager != null,
            survivorMetadata = survivorMetadata,
            nowEpochMs = System.currentTimeMillis(),
        )
        val startupAttemptActive = startupAttemptAgeSeconds != null
        val selectedRuntime = ExecutionRuntime.fromStorageValue(prefs.executionRuntime.first())
        val runtime = resolveGatewaySurvivorRuntime(
            startupAttemptActive = startupAttemptActive,
            runningRuntime = processManager?.runningExecutionRuntime,
            startingRuntime = processManager?.startingExecutionRuntime,
            survivorMetadata = survivorMetadata,
            selectedRuntime = selectedRuntime,
        )
        val startupGracePeriodSeconds = GatewayWatchdogReceiver.resolveStartingRecoveryGracePeriodSeconds(runtime)
        val runningHealthy = if (status == GatewayStatus.RUNNING) {
            val healthy = processManager?.probeGatewayHealth(timeoutMs = HEALTH_PROBE_TIMEOUT_MS) == true
            gatewayState = processManager?.gatewayState?.value ?: gatewayState
            status = gatewayState?.status ?: status
            if (status != GatewayStatus.RUNNING) {
                true
            } else {
                healthy
            }
        } else {
            true
        }
        val runningUptimeSeconds = if (status == GatewayStatus.RUNNING) {
            gatewayState?.uptime ?: 0L
        } else {
            Long.MAX_VALUE
        }
        val recoveryAction = determineRecoveryAction(
            status = status,
            serviceActive = serviceActive,
            runningHealthy = runningHealthy,
            startupAttemptActive = startupAttemptActive,
            startupUptimeSeconds = startupAttemptAgeSeconds ?: 0L,
            startupGracePeriodSeconds = startupGracePeriodSeconds,
            runningUptimeSeconds = runningUptimeSeconds,
        )
        val needsRecovery = recoveryAction != RecoveryAction.NONE
        if (!needsRecovery) {
            return Result.success()
        }

        return try {
            setForeground(createForegroundInfo())
            when (recoveryAction) {
                RecoveryAction.ATTACH -> {
                    processManager?.appendGatewayDiagnosticLog(
                        "[andClaw][Diag] watchdog_recover service_missing action=attach_service",
                    )
                    GatewayService.attachToRunningGateway(
                        applicationContext,
                        fromWatchdog = true,
                        source = "watchdog:attach_service",
                    )
                }
                RecoveryAction.RESTART -> {
                    if (status == GatewayStatus.RUNNING && !runningHealthy) {
                        Log.w("GatewayWatchdogWorker", "Gateway RUNNING but unhealthy, restarting")
                    }
                    GatewayService.restart(
                        applicationContext,
                        userInitiated = false,
                        fromWatchdog = true,
                        source = "watchdog:recovery_restart",
                    )
                }
                RecoveryAction.START -> {
                    GatewayService.start(
                        applicationContext,
                        fromWatchdog = true,
                        userInitiated = false,
                        source = "watchdog:recovery_start",
                    )
                }
                RecoveryAction.NONE -> Unit
            }
            Result.success()
        } catch (error: Exception) {
            Log.e("GatewayWatchdogWorker", "Failed to start gateway from watchdog worker", error)
            Result.retry()
        }
    }

    private fun createForegroundInfo(): ForegroundInfo {
        ensureNotificationChannel()
        val openIntent = Intent(applicationContext, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.notification_starting))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = applicationContext.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
