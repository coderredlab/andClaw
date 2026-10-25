package com.coderred.andclaw.data

internal const val GATEWAY_STARTUP_PHASE_TIMEOUT_MS = 300_000L
internal const val GATEWAY_STARTUP_MAX_TIMEOUT_MS = 2 * GATEWAY_STARTUP_PHASE_TIMEOUT_MS

internal enum class GatewayStartupPhase {
    PREPARATION,
    GATEWAY,
}

internal fun gatewayStartupPhase(state: GatewayState): GatewayStartupPhase {
    val preparationStartedAt = state.startupStartedAtElapsedMs
    val gatewayStartedAt = state.gatewayStartedAtElapsedMs
    return if (
        preparationStartedAt >= 0L &&
        gatewayStartedAt >= preparationStartedAt &&
        gatewayStartedAt <= preparationStartedAt + GATEWAY_STARTUP_PHASE_TIMEOUT_MS
    ) {
        GatewayStartupPhase.GATEWAY
    } else {
        GatewayStartupPhase.PREPARATION
    }
}

internal fun gatewayStartupDeadlineElapsedMs(state: GatewayState): Long {
    if (state.startupStartedAtElapsedMs < 0L) return -1L
    val phaseStartedAt = if (gatewayStartupPhase(state) == GatewayStartupPhase.GATEWAY) {
        state.gatewayStartedAtElapsedMs
    } else {
        state.startupStartedAtElapsedMs
    }
    return phaseStartedAt + GATEWAY_STARTUP_PHASE_TIMEOUT_MS
}
