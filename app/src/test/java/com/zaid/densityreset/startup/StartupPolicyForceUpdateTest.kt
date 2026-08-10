package com.zaid.densityreset.startup

import com.zaid.densityreset.remoteconfig.RemoteAppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartupPolicyForceUpdateTest {

    @Test
    fun forceUpdateDoesNotInventANewerVersionWhenLatestEqualsCurrent() {
        assertNull(
            requiredVersionCode(
                currentVersion = 12L,
                config = RemoteAppConfig.DEFAULT.copy(
                    minSupportedVersionCode = 12L,
                    latestVersionCode = 12L,
                    forceUpdate = true
                )
            )
        )
    }

    @Test
    fun forceUpdateStillBlocksWhenLatestIsNewer() {
        assertEquals(
            13L,
            requiredVersionCode(
                currentVersion = 12L,
                config = RemoteAppConfig.DEFAULT.copy(
                    minSupportedVersionCode = 12L,
                    latestVersionCode = 13L,
                    forceUpdate = true
                )
            )
        )
    }
}
