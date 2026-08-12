package com.zaid.densityreset.remoteconfig

data class RemoteAppConfig(
    val maintenanceMode: Boolean,
    val maintenanceMessage: String?,
    val minSupportedVersionCode: Long,
    val latestVersionCode: Long?,
    val forceUpdate: Boolean,
    val freeFireEnabled: Boolean,
    val freeFireMaxEnabled: Boolean,
    val ultraEnabled: Boolean,
    val veryHighEnabled: Boolean,
    val highEnabled: Boolean,
    val mediumHighEnabled: Boolean,
    val lowEnabled: Boolean,
    val ultraDensity: Int,
    val veryHighDensity: Int,
    val highDensity: Int,
    val mediumHighDensity: Int,
    val lowDensity: Int,
    val gameSessionDurationSeconds: Int,
    val announcementEnabled: Boolean,
    val announcementTitle: String?,
    val announcementMessage: String?,
    val quickTileEnabled: Boolean,
    val githubUpdatesEnabled: Boolean,
    val gameBoosterEnabled: Boolean,
    val gameModeEnabled: Boolean,
    val batteryModeEnabled: Boolean,
    val maxPerformanceEnabled: Boolean,
    val ramMonitorEnabled: Boolean,
    val batteryMonitorEnabled: Boolean,
    val thermalMonitorEnabled: Boolean,
    val fpsMonitorEnabled: Boolean,
    val xiaomiAdapterEnabled: Boolean,
    val samsungAdapterEnabled: Boolean,
    val oplusAdapterEnabled: Boolean,
    val aospAdapterEnabled: Boolean,
    val blockedVersionCodes: Set<Long> = emptySet()
) {
    fun validated(): RemoteAppConfig = copy(
        maintenanceMessage = maintenanceMessage.clean(MAX_MESSAGE_LENGTH),
        minSupportedVersionCode = minSupportedVersionCode
            .takeIf { it > 0L }
            ?: DEFAULT.minSupportedVersionCode,
        latestVersionCode = latestVersionCode?.takeIf { it > 0L },
        ultraDensity = ultraDensity.validDensityOr(DEFAULT.ultraDensity),
        veryHighDensity = veryHighDensity.validDensityOr(DEFAULT.veryHighDensity),
        highDensity = highDensity.validDensityOr(DEFAULT.highDensity),
        mediumHighDensity = mediumHighDensity.validDensityOr(DEFAULT.mediumHighDensity),
        lowDensity = lowDensity.validDensityOr(DEFAULT.lowDensity),
        gameSessionDurationSeconds = gameSessionDurationSeconds
            .takeIf { it in MIN_SESSION_SECONDS..MAX_SESSION_SECONDS }
            ?: DEFAULT.gameSessionDurationSeconds,
        announcementTitle = announcementTitle.clean(MAX_TITLE_LENGTH),
        announcementMessage = announcementMessage.clean(MAX_ANNOUNCEMENT_LENGTH),
        blockedVersionCodes = blockedVersionCodes
            .asSequence()
            .filter { it > 0L }
            .take(MAX_BLOCKED_VERSIONS)
            .toSet()
    )

    companion object {
        const val MIN_DENSITY = 20
        const val MAX_DENSITY = 1_000
        const val MIN_SESSION_SECONDS = 5
        const val MAX_SESSION_SECONDS = 150

        private const val MAX_MESSAGE_LENGTH = 1_000
        private const val MAX_TITLE_LENGTH = 120
        private const val MAX_ANNOUNCEMENT_LENGTH = 2_000
        private const val MAX_BLOCKED_VERSIONS = 128

        val DEFAULT = RemoteAppConfig(
            maintenanceMode = false,
            maintenanceMessage = null,
            minSupportedVersionCode = 1L,
            latestVersionCode = null,
            forceUpdate = false,
            freeFireEnabled = true,
            freeFireMaxEnabled = true,
            ultraEnabled = true,
            veryHighEnabled = true,
            highEnabled = true,
            mediumHighEnabled = true,
            lowEnabled = true,
            ultraDensity = 20,
            veryHighDensity = 46,
            highDensity = 72,
            mediumHighDensity = 176,
            lowDensity = 280,
            gameSessionDurationSeconds = 20,
            announcementEnabled = false,
            announcementTitle = null,
            announcementMessage = null,
            quickTileEnabled = true,
            githubUpdatesEnabled = true,
            gameBoosterEnabled = true,
            gameModeEnabled = true,
            batteryModeEnabled = true,
            maxPerformanceEnabled = true,
            ramMonitorEnabled = true,
            batteryMonitorEnabled = true,
            thermalMonitorEnabled = true,
            fpsMonitorEnabled = true,
            xiaomiAdapterEnabled = true,
            samsungAdapterEnabled = true,
            oplusAdapterEnabled = true,
            aospAdapterEnabled = true,
            blockedVersionCodes = emptySet()
        )
    }
}

private fun Int.validDensityOr(fallback: Int): Int =
    takeIf { it in RemoteAppConfig.MIN_DENSITY..RemoteAppConfig.MAX_DENSITY }
        ?: fallback

private fun String?.clean(maxLength: Int): String? =
    this
        ?.trim()
        ?.take(maxLength)
        ?.takeIf { it.isNotEmpty() }
