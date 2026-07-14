package com.coderred.andclaw.data

import com.coderred.andclaw.proroot.ExecutionRuntime

internal fun parseGatewaySurvivorRuntime(raw: String?): ExecutionRuntime? {
    val normalized = raw?.trim()?.lowercase() ?: return null
    return ExecutionRuntime.entries.singleOrNull { it.storageValue == normalized }
}

internal fun resolveGatewaySurvivorRuntime(
    startupAttemptActive: Boolean,
    runningRuntime: ExecutionRuntime?,
    startingRuntime: ExecutionRuntime? = null,
    survivorMetadata: GatewaySurvivorMetadata?,
    selectedRuntime: ExecutionRuntime,
): ExecutionRuntime {
    return if (startupAttemptActive) {
        startingRuntime ?: survivorMetadata?.runtime ?: runningRuntime ?: selectedRuntime
    } else {
        runningRuntime ?: survivorMetadata?.runtime ?: selectedRuntime
    }
}

internal fun resolveGatewaySurvivorStartupAttemptAgeSeconds(
    liveStartupAttemptAgeSeconds: Long?,
    processManagerAvailable: Boolean,
    survivorMetadata: GatewaySurvivorMetadata?,
    nowEpochMs: Long,
): Long? {
    if (liveStartupAttemptAgeSeconds != null) return liveStartupAttemptAgeSeconds
    if (processManagerAvailable || survivorMetadata?.startupAttemptActive != true) return null
    return ((nowEpochMs - survivorMetadata.launchedAtEpochMs).coerceAtLeast(0L)) / 1_000L
}
