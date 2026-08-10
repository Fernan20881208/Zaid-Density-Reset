package com.zaid.densityreset.remoteconfig

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteAppConfigTest {

    @Test
    fun invalidRemoteValuesFallBackToSafeDefaults() {
        val invalid = RemoteAppConfig.DEFAULT.copy(
            minSupportedVersionCode = -20L,
            latestVersionCode = -4L,
            ultraDensity = 0,
            highDensity = 10_000,
            lowDensity = -1,
            gameSessionDurationSeconds = 2,
            blockedVersionCodes = setOf(-5L, 0L, 11L, 13L)
        ).validated()

        assertEquals(RemoteAppConfig.DEFAULT.minSupportedVersionCode, invalid.minSupportedVersionCode)
        assertNull(invalid.latestVersionCode)
        assertEquals(20, invalid.ultraDensity)
        assertEquals(72, invalid.highDensity)
        assertEquals(280, invalid.lowDensity)
        assertEquals(30, invalid.gameSessionDurationSeconds)
        assertEquals(setOf(11L, 13L), invalid.blockedVersionCodes)
    }

    @Test
    fun validRemoteValuesArePreserved() {
        val valid = RemoteAppConfig.DEFAULT.copy(
            maintenanceMode = true,
            maintenanceMessage = "Mantenimiento programado",
            minSupportedVersionCode = 15L,
            latestVersionCode = 18L,
            forceUpdate = true,
            freeFireEnabled = false,
            ultraDensity = 44,
            highDensity = 96,
            lowDensity = 320,
            gameSessionDurationSeconds = 45,
            blockedVersionCodes = setOf(11L, 13L)
        ).validated()

        assertEquals(15L, valid.minSupportedVersionCode)
        assertEquals(18L, valid.latestVersionCode)
        assertEquals(44, valid.ultraDensity)
        assertEquals(96, valid.highDensity)
        assertEquals(320, valid.lowDensity)
        assertEquals(45, valid.gameSessionDurationSeconds)
        assertFalse(valid.freeFireEnabled)
        assertEquals(setOf(11L, 13L), valid.blockedVersionCodes)
    }
}
