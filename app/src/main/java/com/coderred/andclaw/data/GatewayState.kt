package com.coderred.andclaw.data

import androidx.annotation.StringRes
import com.coderred.andclaw.R

enum class GatewayStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}

data class GatewayState(
    val status: GatewayStatus = GatewayStatus.STOPPED,
    val uptime: Long = 0L,
    val connectedChannels: Int = 0,
    val errorMessage: String? = null,
    val pid: Int? = null,
    val channelStatus: ChannelStatus = ChannelStatus(),
    val dashboardReady: Boolean = false,
    val startupAttemptGeneration: Long = -1L,
    val startupStartedAtElapsedMs: Long = -1L,
    val gatewayStartedAtElapsedMs: Long = -1L,
)

data class SetupState(
    val isInProgress: Boolean = false,
    val currentStep: SetupStep = SetupStep.IDLE,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val logLines: List<String> = emptyList(),
    val error: String? = null,
)

data class PairingRequest(
    val channel: String,
    val code: String,
    val username: String = "",
)

enum class SetupStep(@StringRes val displayNameRes: Int) {
    IDLE(R.string.step_idle),
    CHECKING_PROROOT(R.string.step_checking_proroot),
    EXTRACTING_ROOTFS(R.string.step_extracting_rootfs),
    CONFIGURING_ROOTFS(R.string.step_configuring_rootfs),
    EXTRACTING_NODEJS(R.string.step_extracting_nodejs),
    INSTALLING_TOOLS(R.string.step_installing_tools),
    INSTALLING_OPENCLAW(R.string.step_installing_openclaw),
    INSTALLING_CHROMIUM(R.string.step_installing_chromium),
    APPLYING_PATCHES(R.string.step_applying_patches),
    VERIFYING(R.string.step_verifying),
    ONBOARDING(R.string.step_onboarding),
    COMPLETE(R.string.step_complete),
    FAILED(R.string.step_failed),
}
