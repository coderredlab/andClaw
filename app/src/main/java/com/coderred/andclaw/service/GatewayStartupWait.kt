package com.coderred.andclaw.service

import com.coderred.andclaw.data.GatewayStartupPhase
import com.coderred.andclaw.data.GatewayState
import com.coderred.andclaw.data.GatewayStatus
import com.coderred.andclaw.data.gatewayStartupDeadlineElapsedMs
import com.coderred.andclaw.data.gatewayStartupPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal enum class GatewayStartupWaitResult(
    val status: GatewayStatus? = null,
    val timedOutPhase: GatewayStartupPhase? = null,
) {
    RUNNING(status = GatewayStatus.RUNNING),
    ERROR(status = GatewayStatus.ERROR),
    STOPPED(status = GatewayStatus.STOPPED),
    PREPARATION_TIMEOUT(timedOutPhase = GatewayStartupPhase.PREPARATION),
    GATEWAY_TIMEOUT(timedOutPhase = GatewayStartupPhase.GATEWAY),
    LEGACY_TIMEOUT,
}

internal suspend fun awaitGatewayStartupWithinPhaseBudgets(
    states: StateFlow<GatewayState>,
    nowElapsedMs: () -> Long,
): GatewayStartupWaitResult {
    var state = states.value
    val generation = state.startupAttemptGeneration
    while (true) {
        currentCoroutineContext().ensureActive()
        if (state.startupAttemptGeneration >= 0L && state.startupAttemptGeneration != generation) {
            throw CancellationException("Gateway startup attempt was replaced")
        }
        when (state.status) {
            GatewayStatus.RUNNING -> return GatewayStartupWaitResult.RUNNING
            GatewayStatus.ERROR -> return GatewayStartupWaitResult.ERROR
            GatewayStatus.STOPPED -> return GatewayStartupWaitResult.STOPPED
            GatewayStatus.STOPPING -> {
                // Startup has ended; wait for the existing stop barrier, not another startup budget.
                state = states.first {
                    it.status != GatewayStatus.STOPPING ||
                        (it.startupAttemptGeneration >= 0L && it.startupAttemptGeneration != generation)
                }
                continue
            }
            GatewayStatus.STARTING -> Unit
        }

        val phase = gatewayStartupPhase(state)
        val deadline = gatewayStartupDeadlineElapsedMs(state)
        val remainingMs = deadline - nowElapsedMs()
        if (deadline < 0L || remainingMs <= 0L) {
            // Timeout delivery may race with readiness, stop, or a valid launch publication.
            val latest = states.value
            if (
                latest.status != GatewayStatus.STARTING ||
                latest.startupAttemptGeneration != state.startupAttemptGeneration ||
                gatewayStartupDeadlineElapsedMs(latest) != deadline ||
                gatewayStartupPhase(latest) != phase
            ) {
                state = latest
                continue
            }
            return when (phase) {
                GatewayStartupPhase.PREPARATION -> GatewayStartupWaitResult.PREPARATION_TIMEOUT
                GatewayStartupPhase.GATEWAY -> GatewayStartupWaitResult.GATEWAY_TIMEOUT
            }
        }

        state = withTimeoutOrNull(remainingMs) {
            states.first {
                it.status != GatewayStatus.STARTING ||
                    it.startupAttemptGeneration != state.startupAttemptGeneration ||
                    gatewayStartupDeadlineElapsedMs(it) != deadline ||
                    gatewayStartupPhase(it) != phase
            }
        } ?: states.value
    }
}
