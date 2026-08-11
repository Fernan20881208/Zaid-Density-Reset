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
            veryHighDensity = 1,
            highDensity = 10_000,
            mediumHighDensity = -100,
            lowDensity = -1,
            gameSessionDurationSeconds = 2,
            blockedVersionCodes = setOf(-5L, 0L, 11L, 13L)
        ).validated()

        assertEquals(RemoteAppConfig.DEFAULT.minSupportedVersionCode, invalid.minSupportedVersionCode)
        assertNull(invalid.latestVersionCode)
        assertEquals(20, invalid.ultraDensity)
        assertEquals(46, invalid.veryHighDensity)
        assertEquals(72, invalid.highDensity)
        assertEquals(176, invalid.mediumHighDensity)
        assertEquals(280, invalid.lowDensity)
        assertEquals(30, invalid.gameSessionDurationSeconds)
        assertEquals(setOf(11L, 13L), invalid.blockedVersionCodes)
    }

    @Test
    fun sessionDurationAboveShortServiceSafetyBudgetFallsBack() {
        val invalid = RemoteAppConfig.DEFAULT.copy(
            gameSessionDurationSeconds = RemoteAppConfig.MAX_SESSION_SECONDS + 1
        ).validated()

        assertEquals(30, invalid.gameSessionDurationSeconds)
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
            veryHighDensity = 48,
            highDensity = 96,
            mediumHighDensity = 190,
            lowDensity = 320,
            gameSessionDurationSeconds = 45,
            blockedVersionCodes = setOf(11L, 13L)
        ).validated()

        assertEquals(15L, valid.minSupportedVersionCode)
        assertEquals(18L, valid.latestVersionCode)
        assertEquals(44, valid.ultraDensity)
        assertEquals(48, valid.veryHighDensity)
        assertEquals(96, valid.highDensity)
        assertEquals(190, valid.mediumHighDensity)
        assertEquals(320, valid.lowDensity)
        assertEquals(45, valid.gameSessionDurationSeconds)
        assertFalse(valid.freeFireEnabled)
        assertEquals(setOf(11L, 13L), valid.blockedVersionCodes)
    }
}
