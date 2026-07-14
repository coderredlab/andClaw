package com.coderred.andclaw.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.PreferencesManager
import com.coderred.andclaw.data.resolveGatewaySurvivorRuntime
import com.coderred.andclaw.data.resolveGatewaySurvivorStartupAttemptAgeSeconds
import com.coderred.andclaw.proroot.ExecutionRuntime
import com.coderred.andclaw.service.GatewayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

class GatewayWatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION_WATCHDOG = "com.coderred.andclaw.action.GATEWAY_WATCHDOG"
        private const val REQUEST_CODE = 21001
        private const val WATCHDOG_INTERVAL_MS = 180_000L
        private const val MIN_DELAY_MS = 5_000L
        private const val HEALTH_PROBE_TIMEOUT_MS = 20_000L
        private const val RUNNING_UNHEALTHY_RECOVERY_THRESHOLD = 3
        private const val STARTING_RECOVERY_GRACE_PERIOD_SECONDS = 300L
        private const val PROOT_STARTING_RECOVERY_GRACE_PERIOD_SECONDS = 21_600L
        private val runningUnhealthyFailures = AtomicInteger(0)

        fun intervalMs(): Long = WATCHDOG_INTERVAL_MS

        fun schedule(context: Context, delayMs: Long = WATCHDOG_INTERVAL_MS) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(MIN_DELAY_MS)
            val pendingIntent = buildPendingIntent(appContext)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (canUseExactAlarm(alarmManager)) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                }
            } else {
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            }
        }

        fun cancel(context: Context) {
            val appContext = context.applicationContext
            val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(buildPendingIntent(appContext))
            runningUnhealthyFailures.set(0)
        }

        internal fun resolveStartingRecoveryGracePeriodSeconds(runtime: ExecutionRuntime): Long {
            return when (runtime) {
                ExecutionRuntime.PROOT -> PROOT_STARTING_RECOVERY_GRACE_PERIOD_SECONDS
                ExecutionRuntime.PROROOT -> STARTING_RECOVERY_GRACE_PERIOD_SECONDS
            }
        }

        internal data class RecoveryDecision(
            val needsRecovery: Boolean,
            val nextRunningUnhealthyFailures: Int,
        )

        internal fun decideRecovery(
            status: GatewayStatus?,
            runningHealthy: Boolean = true,
            serviceActive: Boolean = true,
            startupAttemptActive: Boolean = false,
            startupUptimeSeconds: Long = 0L,
            startupGracePeriodSeconds: Long = STARTING_RECOVERY_GRACE_PERIOD_SECONDS,
            previousRunningUnhealthyFailures: Int = 0,
            runningUnhealthyRecoveryThreshold: Int = RUNNING_UNHEALTHY_RECOVERY_THRESHOLD,
        ): RecoveryDecision {
            if (startupAttemptActive && startupUptimeSeconds < startupGracePeriodSeconds) {
                // 시작 시도 중이고 grace period 내 → 서비스 재생성/재시작 중일 수 있으므로 복구하지 않음
                return RecoveryDecision(
                    needsRecovery = false,
                    nextRunningUnhealthyFailures = 0,
                )
            }
            if (startupAttemptActive && startupUptimeSeconds >= startupGracePeriodSeconds) {
                // grace period 초과 → 시작 실패로 판단
                return RecoveryDecision(
                    needsRecovery = true,
                    nextRunningUnhealthyFailures = 0,
                )
            }

            return when (status) {
                null,
                GatewayStatus.STOPPED,
                GatewayStatus.ERROR -> RecoveryDecision(
                    needsRecovery = true,
                    nextRunningUnhealthyFailures = 0,
                )
                GatewayStatus.RUNNING -> {
                    if (!serviceActive) {
                        // 게이트웨이가 RUNNING이면 죽이지 않음 — RecoveryWorker에서 ATTACH로 처리
                        RecoveryDecision(
                            needsRecovery = true,
                            nextRunningUnhealthyFailures = 0,
                        )
                    } else if (runningHealthy) {
                        RecoveryDecision(
                            needsRecovery = false,
                            nextRunningUnhealthyFailures = 0,
                        )
                    } else {
                        val nextFailureCount = (previousRunningUnhealthyFailures + 1).coerceAtLeast(1)
                        val shouldRecover = nextFailureCount >= runningUnhealthyRecoveryThreshold
                        RecoveryDecision(
                            needsRecovery = shouldRecover,
                            nextRunningUnhealthyFailures = if (shouldRecover) 0 else nextFailureCount,
                        )
                    }
                }
                GatewayStatus.STARTING -> {
                    if (startupUptimeSeconds >= startupGracePeriodSeconds) {
                        RecoveryDecision(
                            needsRecovery = true,
                            nextRunningUnhealthyFailures = 0,
                        )
                    } else {
                        RecoveryDecision(
                            needsRecovery = false,
                            nextRunningUnhealthyFailures = 0,
                        )
                    }
                }
                GatewayStatus.STOPPING -> RecoveryDecision(
                    needsRecovery = false,
                    nextRunningUnhealthyFailures = 0,
                )
            }
        }

        internal fun shouldUseExactAlarm(sdkInt: Int, canScheduleExact: Boolean): Boolean {
            if (sdkInt < Build.VERSION_CODES.M) return false
            if (sdkInt < Build.VERSION_CODES.S) return true
            return canScheduleExact
        }

        private fun canUseExactAlarm(alarmManager: AlarmManager): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    shouldUseExactAlarm(
                        sdkInt = Build.VERSION.SDK_INT,
                        canScheduleExact = alarmManager.canScheduleExactAlarms(),
                    )
                } catch (_: SecurityException) {
                    false
                }
            } else {
                shouldUseExactAlarm(
                    sdkInt = Build.VERSION.SDK_INT,
                    canScheduleExact = true,
                )
            }
        }

        private fun buildPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, GatewayWatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG
            }
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WATCHDOG) return

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            var shouldKeepRunning = false
            var shouldScheduleNext = true
            try {
                val prefs = PreferencesManager(appContext)
                shouldKeepRunning = prefs.gatewayWasRunning.first() && prefs.isSetupComplete.first()
                if (!shouldKeepRunning) {
                    cancel(appContext)
                    shouldScheduleNext = false
                    return@launch
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
                val startupGracePeriodSeconds = resolveStartingRecoveryGracePeriodSeconds(runtime)
                val previousRunningUnhealthyFailures = runningUnhealthyFailures.get()
                val runningHealthy = if (status == GatewayStatus.RUNNING) {
                    val healthy = processManager?.probeGatewayHealth(timeoutMs = HEALTH_PROBE_TIMEOUT_MS) == true
                    // health probe 중 상태가 변했으면 갱신
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
                val decision = decideRecovery(
                    status = status,
                    runningHealthy = runningHealthy,
                    serviceActive = serviceActive,
                    startupAttemptActive = startupAttemptActive,
                    startupUptimeSeconds = startupAttemptAgeSeconds ?: 0L,
                    startupGracePeriodSeconds = startupGracePeriodSeconds,
                    previousRunningUnhealthyFailures = previousRunningUnhealthyFailures,
                )
                runningUnhealthyFailures.set(decision.nextRunningUnhealthyFailures)

                if ((status == GatewayStatus.RUNNING || status == GatewayStatus.STARTING) && !serviceActive) {
                    processManager?.appendGatewayDiagnosticLog(
                        "[andClaw][Diag] watchdog_detected service_missing status=${status ?: "null"}",
                    )
                }

                if (status == GatewayStatus.RUNNING && !runningHealthy) {
                    val failureForLog = if (decision.needsRecovery) {
                        previousRunningUnhealthyFailures + 1
                    } else {
                        decision.nextRunningUnhealthyFailures
                    }
                    Log.w(
                        "GatewayWatchdog",
                        "Gateway RUNNING but unhealthy ($failureForLog/$RUNNING_UNHEALTHY_RECOVERY_THRESHOLD)",
                    )
                }

                if (decision.needsRecovery) {
                    runningUnhealthyFailures.set(0)
                    // AlarmReceiver에서 직접 FGS를 시작하지 않고 WorkManager 경유로 복구를 시도한다.
                    GatewayWatchdogRecoveryWorker.enqueue(appContext)
                }
            } catch (error: Exception) {
                Log.e("GatewayWatchdog", "Watchdog check failed", error)
            } finally {
                if (shouldScheduleNext) {
                    schedule(appContext)
                }
                pendingResult.finish()
            }
        }
    }
}
