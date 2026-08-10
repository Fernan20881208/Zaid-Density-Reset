package com.zaid.densityreset.startup

import com.zaid.densityreset.remoteconfig.RemoteAppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupPolicyTest {

    @Test
    fun currentVersionBelowMinimumRequiresConfiguredMinimum() {
        assertEquals(
            15L,
            requiredVersionCode(
                currentVersion = 12L,
                config = RemoteAppConfig.DEFAULT.copy(
                    minSupportedVersionCode = 15L,
                    latestVersionCode = 14L
                )
            )
        )
    }

    @Test
    fun currentVersionBelowLatestRequiresLatestEvenWithoutForceFlag() {
        assertEquals(
            16L,
            requiredVersionCode(
                currentVersion = 15L,
                config = RemoteAppConfig.DEFAULT.copy(
                    minSupportedVersionCode = 12L,
                    latestVersionCode = 16L,
                    forceUpdate = false
                )
            )
        )
    }

    @Test
    fun blockedCurrentVersionRequiresAtLeastNextVersion() {
        assertEquals(
            14L,
            requiredVersionCode(
                currentVersion = 13L,
                config = RemoteAppConfig.DEFAULT.copy(
                    minSupportedVersionCode = 12L,
                    latestVersionCode = 13L,
                    blockedVersionCodes = setOf(13L)
                )
            )
        )
    }

    @Test
    fun compliantVersionHasNoRemoteBlock() {
        assertNull(
            requiredVersionCode(
                currentVersion = 16L,
                config = RemoteAppConfig.DEFAULT.copy(
                    minSupportedVersionCode = 12L,
                    latestVersionCode = 16L
                )
            )
        )
    }
}
