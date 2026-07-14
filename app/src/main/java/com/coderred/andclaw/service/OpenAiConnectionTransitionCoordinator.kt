package com.coderred.andclaw.service

import com.coderred.andclaw.data.GatewayLaunchConfigSnapshot
import com.coderred.andclaw.data.OpenAiConnectionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

internal enum class GatewayActionCancellationIntent {
    STOP,
}

internal class GatewayActionCancellationException(
    val intent: GatewayActionCancellationIntent,
) : CancellationException("Gateway action cancelled for ${intent.name}.")

internal enum class OpenAiConnectionTransitionPhase {
    IDLE,
    AWAITING_CONFIRMATION,
    STOPPING_OLD_GATEWAY,
    STAGING_TARGET,
    STARTING_TARGET_GATEWAY,
    ROLLING_BACK,
}

internal data class OpenAiConnectionTransitionFailure(
    val failureId: Long,
    val message: String,
)

internal data class OpenAiConnectionTransitionUiState(
    val pendingMode: OpenAiConnectionMode? = null,
    val attemptId: Long? = null,
    val phase: OpenAiConnectionTransitionPhase = OpenAiConnectionTransitionPhase.IDLE,
    val failure: OpenAiConnectionTransitionFailure? = null,
) {
    val isPending: Boolean
        get() = pendingMode != null

    val errorMessage: String?
        get() = failure?.message
}

internal object OpenAiConnectionTransitionState {
    private val _state = MutableStateFlow(OpenAiConnectionTransitionUiState())
    private val failureSequence = AtomicLong(0L)
    private val attemptSequence = AtomicLong(0L)
    val state: StateFlow<OpenAiConnectionTransitionUiState> = _state.asStateFlow()

    @Synchronized
    fun awaitConfirmation(targetMode: OpenAiConnectionMode): Long =
        beginAttempt(targetMode, OpenAiConnectionTransitionPhase.AWAITING_CONFIRMATION)

    @Synchronized
    fun update(
        targetMode: OpenAiConnectionMode,
        phase: OpenAiConnectionTransitionPhase,
    ): Long {
        val current = _state.value
        if (current.attemptId != null && current.pendingMode == targetMode) {
            _state.value = current.copy(phase = phase, failure = null)
            return current.attemptId
        }
        return beginAttempt(targetMode, phase)
    }

    @Synchronized
    fun beginAction(targetMode: OpenAiConnectionMode): Long {
        val current = _state.value
        val initialPhase = current.phase.takeIf {
            current.pendingMode == targetMode && it != OpenAiConnectionTransitionPhase.IDLE
        } ?: OpenAiConnectionTransitionPhase.STAGING_TARGET
        return beginAttempt(targetMode, initialPhase)
    }

    fun update(
        attemptId: Long,
        targetMode: OpenAiConnectionMode,
        phase: OpenAiConnectionTransitionPhase,
    ) {
        _state.update { current ->
            if (current.attemptId == attemptId) {
                OpenAiConnectionTransitionUiState(
                    pendingMode = targetMode,
                    attemptId = attemptId,
                    phase = phase,
                )
            } else {
                current
            }
        }
    }

    fun complete() {
        _state.value = OpenAiConnectionTransitionUiState()
    }

    fun complete(attemptId: Long) {
        _state.update { current ->
            if (current.attemptId == attemptId) OpenAiConnectionTransitionUiState() else current
        }
    }

    fun fail(message: String) {
        _state.value = failureState(message)
    }

    fun fail(attemptId: Long, message: String) {
        _state.update { current ->
            if (current.attemptId == attemptId) failureState(message) else current
        }
    }

    private fun beginAttempt(
        targetMode: OpenAiConnectionMode,
        phase: OpenAiConnectionTransitionPhase,
    ): Long {
        val attemptId = attemptSequence.incrementAndGet()
        _state.value = OpenAiConnectionTransitionUiState(
            pendingMode = targetMode,
            attemptId = attemptId,
            phase = phase,
        )
        return attemptId
    }

    private fun failureState(message: String) = OpenAiConnectionTransitionUiState(
        failure = OpenAiConnectionTransitionFailure(
            failureId = failureSequence.incrementAndGet(),
            message = message,
        ),
    )

    fun acknowledgeFailure(failureId: Long) {
        _state.update { current ->
            if (current.failure?.failureId == failureId) OpenAiConnectionTransitionUiState() else current
        }
    }

    internal fun resetForTest() {
        _state.value = OpenAiConnectionTransitionUiState()
        attemptSequence.set(0L)
    }
}

internal sealed interface OpenAiConnectionTransitionResult {
    data class Success(
        val mode: OpenAiConnectionMode,
        val gatewayRestarted: Boolean,
    ) : OpenAiConnectionTransitionResult

    data object RequiresCodexLogin : OpenAiConnectionTransitionResult
    data object RequiresApiKey : OpenAiConnectionTransitionResult

    data class Failed(
        val targetMode: OpenAiConnectionMode,
        val restoredMode: OpenAiConnectionMode,
        val oldGatewayRestored: Boolean,
        val cause: Throwable,
    ) : OpenAiConnectionTransitionResult
}

private fun verifyOpenAiTransitionManagedLaunchFields(
    previous: GatewayLaunchConfigSnapshot,
    restored: GatewayLaunchConfigSnapshot,
) {
    val mismatches = mutableListOf<String>()
    if (restored.apiProvider != previous.apiProvider) mismatches += "apiProvider"
    if (restored.openAiConnectionMode != previous.openAiConnectionMode) {
        mismatches += "openAiConnectionMode"
    }
    if (restored.apiKey != previous.apiKey) mismatches += "apiKey"
    if (restored.selectedModel != previous.selectedModel) mismatches += "selectedModel"
    if (restored.selectedModelEntries != previous.selectedModelEntries) {
        mismatches += "selectedModelEntries"
    }
    if (restored.primaryModelId != previous.primaryModelId) mismatches += "primaryModelId"
    if (
        previous.apiProvider == "openai-compatible" &&
            restored.openAiCompatibleBaseUrl != previous.openAiCompatibleBaseUrl
    ) {
        mismatches += "openAiCompatibleBaseUrl"
    }
    if (
        previous.apiProvider == "ollama" &&
            restored.ollamaBaseUrl != previous.ollamaBaseUrl
    ) {
        mismatches += "ollamaBaseUrl"
    }
    if (restored.modelReasoning != previous.modelReasoning) mismatches += "modelReasoning"
    if (restored.modelImages != previous.modelImages) mismatches += "modelImages"
    if (restored.modelContext != previous.modelContext) mismatches += "modelContext"
    if (restored.modelMaxOutput != previous.modelMaxOutput) mismatches += "modelMaxOutput"
    check(mismatches.isEmpty()) {
        "Previous gateway OpenAI-managed launch fields were not restored: " +
            mismatches.joinToString()
    }
}

internal class OpenAiConnectionTransitionCoordinator(
    private val backend: Backend,
    private val onPhase: (OpenAiConnectionMode, OpenAiConnectionTransitionPhase) -> Unit = { _, _ -> },
    private val onCancellation: () -> Unit = {},
) {
    interface Backend {
        suspend fun currentMode(): OpenAiConnectionMode
        suspend fun hasUsableCredential(mode: OpenAiConnectionMode): Boolean
        suspend fun readLaunchSnapshot(): GatewayLaunchConfigSnapshot
        suspend fun stopGateway()
        suspend fun stageCredential(mode: OpenAiConnectionMode)
        suspend fun persistModeAndCanonicalConfig(mode: OpenAiConnectionMode): GatewayLaunchConfigSnapshot
        suspend fun persistedMode(): OpenAiConnectionMode
        suspend fun startGateway(snapshot: GatewayLaunchConfigSnapshot): Boolean
        suspend fun restoreCanonicalConfig(
            mode: OpenAiConnectionMode,
            previousSnapshot: GatewayLaunchConfigSnapshot,
        ): GatewayLaunchConfigSnapshot
    }

    suspend fun transition(
        targetMode: OpenAiConnectionMode,
        gatewayActive: Boolean,
    ): OpenAiConnectionTransitionResult {
        var lockAcquired = false
        return try {
            transitionMutex.withLock {
                lockAcquired = true
                transitionLocked(targetMode, gatewayActive)
            }
        } catch (cancellation: CancellationException) {
            if (lockAcquired) {
                withContext(NonCancellable) {
                    onCancellation()
                }
            }
            throw cancellation
        }
    }

    private suspend fun transitionLocked(
        targetMode: OpenAiConnectionMode,
        gatewayActive: Boolean,
    ): OpenAiConnectionTransitionResult {
        if (!backend.hasUsableCredential(targetMode)) {
            return targetMode.missingCredentialResult()
        }

        val oldMode = backend.currentMode()
        val oldSnapshot = backend.readLaunchSnapshot()
        var oldGatewayStopInitiated = false

        return try {
            if (gatewayActive) {
                oldGatewayStopInitiated = true
                onPhase(targetMode, OpenAiConnectionTransitionPhase.STOPPING_OLD_GATEWAY)
                backend.stopGateway()
            }

            if (!backend.hasUsableCredential(targetMode)) {
                throw MissingTargetCredentialException(targetMode)
            }

            onPhase(targetMode, OpenAiConnectionTransitionPhase.STAGING_TARGET)
            backend.stageCredential(targetMode)
            val targetSnapshot = backend.persistModeAndCanonicalConfig(targetMode)
            check(targetSnapshot.openAiConnectionMode == targetMode) {
                "Target launch snapshot mode did not match persisted OpenAI mode."
            }
            check(backend.persistedMode() == targetMode) {
                "Persisted OpenAI mode changed before target process start."
            }

            if (gatewayActive) {
                onPhase(targetMode, OpenAiConnectionTransitionPhase.STARTING_TARGET_GATEWAY)
                check(backend.startGateway(targetSnapshot)) {
                    "Target gateway failed readiness verification."
                }
            }

            OpenAiConnectionTransitionResult.Success(
                mode = targetMode,
                gatewayRestarted = gatewayActive,
            )
        } catch (cause: Throwable) {
            val restartOldGateway =
                cause !is GatewayActionCancellationException ||
                    cause.intent != GatewayActionCancellationIntent.STOP
            val rollback = withContext(NonCancellable) {
                onPhase(targetMode, OpenAiConnectionTransitionPhase.ROLLING_BACK)
                var oldGatewayRestored = !gatewayActive
                val rollbackFailure = try {
                    if (gatewayActive && oldGatewayStopInitiated) {
                        backend.stopGateway()
                    }
                    val restoredSnapshot = backend.restoreCanonicalConfig(oldMode, oldSnapshot)
                    verifyOpenAiTransitionManagedLaunchFields(oldSnapshot, restoredSnapshot)
                    check(backend.persistedMode() == oldMode) {
                        "Persisted OpenAI mode did not roll back to the previous mode."
                    }
                    if (gatewayActive && oldGatewayStopInitiated && restartOldGateway) {
                        oldGatewayRestored = backend.startGateway(restoredSnapshot)
                        check(oldGatewayRestored) {
                            "Previous gateway failed readiness verification during rollback."
                        }
                    }
                    null
                } catch (rollbackError: Throwable) {
                    rollbackError
                }
                RollbackOutcome(oldGatewayRestored, rollbackFailure)
            }

            if (cause is CancellationException) {
                rollback.failure?.let(cause::addSuppressed)
                throw cause
            }

            val effectiveCause = rollback.failure?.let { rollbackError ->
                IllegalStateException(cause.message ?: "OpenAI mode transition failed.", cause).apply {
                    addSuppressed(rollbackError)
                }
            } ?: cause
            OpenAiConnectionTransitionResult.Failed(
                targetMode = targetMode,
                restoredMode = oldMode,
                oldGatewayRestored = rollback.oldGatewayRestored,
                cause = effectiveCause,
            )
        }
    }

    private fun OpenAiConnectionMode.missingCredentialResult(): OpenAiConnectionTransitionResult =
        when (this) {
            OpenAiConnectionMode.CODEX_SUBSCRIPTION -> OpenAiConnectionTransitionResult.RequiresCodexLogin
            OpenAiConnectionMode.PLATFORM_API_KEY -> OpenAiConnectionTransitionResult.RequiresApiKey
        }

    private data class RollbackOutcome(
        val oldGatewayRestored: Boolean,
        val failure: Throwable?,
    )

    private class MissingTargetCredentialException(mode: OpenAiConnectionMode) :
        IllegalStateException("Target credential became unavailable for ${mode.storageValue}.")

    private companion object {
        val transitionMutex = Mutex()
    }
}
