package com.zaid.densityreset.startup

import com.zaid.densityreset.remoteconfig.RemoteAppConfig

internal fun requiredVersionCode(
    currentVersion: Long,
    config: RemoteAppConfig
): Long? {
    val blocked = currentVersion in config.blockedVersionCodes
    val belowMinimum = currentVersion < config.minSupportedVersionCode
    val belowLatest = config.latestVersionCode?.let { currentVersion < it } ?: false

    if (!blocked && !belowMinimum && !belowLatest) return null

    val nextVersion = currentVersion + 1L
    return listOfNotNull(
        config.latestVersionCode?.takeIf { it > currentVersion },
        config.minSupportedVersionCode.takeIf { it > currentVersion },
        nextVersion.takeIf { blocked }
    ).maxOrNull() ?: nextVersion
}
