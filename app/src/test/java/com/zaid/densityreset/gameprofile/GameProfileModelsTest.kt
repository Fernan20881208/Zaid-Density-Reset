package com.zaid.densityreset.gameprofile

import com.zaid.densityreset.density.DensityPreset
import com.zaid.densityreset.gameprofile.domain.DensitySnapshot
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import com.zaid.densityreset.gameprofile.service.DpiGameSessionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameProfileModelsTest {

    @Test
    fun supportedGamesUseExactPackages() {
        assertEquals("Free Fire", SupportedGame.FREE_FIRE.displayName)
        assertEquals("com.dts.freefireth", SupportedGame.FREE_FIRE.packageName)
        assertEquals("Free Fire MAX", SupportedGame.FREE_FIRE_MAX.displayName)
        assertEquals("com.dts.freefiremax", SupportedGame.FREE_FIRE_MAX.packageName)
    }

    @Test
    fun densityPresetsUseExactValues() {
        assertEquals(5, DensityPreset.entries.size)

        assertEquals(280, DensityPreset.LOW.density)
        assertEquals("Escala equilibrada", DensityPreset.LOW.description)

        assertEquals(176, DensityPreset.MEDIUM_HIGH.density)
        assertEquals("Sensibilidad media-alta", DensityPreset.MEDIUM_HIGH.description)

        assertEquals(72, DensityPreset.HIGH.density)
        assertEquals("Sensibilidad alta", DensityPreset.HIGH.description)

        assertEquals(46, DensityPreset.VERY_HIGH.density)
        assertEquals("Sensibilidad muy alta", DensityPreset.VERY_HIGH.description)

        assertEquals(20, DensityPreset.ULTRA.density)
        assertEquals("Sensibilidad extrema", DensityPreset.ULTRA.description)
    }

    @Test
    fun gameSensitivityUsesExactTwentySecondWindow() {
        assertEquals(20, DpiGameSessionService.SESSION_DURATION_SECONDS)
    }

    @Test
    fun customOverrideSnapshotKeepsExactPreviousDensity() {
        val snapshot = DensitySnapshot(
            physicalDensity = 440,
            effectiveDensity = 320,
            hadOverride = true,
            previousOverrideDensity = 320
        )

        assertTrue(snapshot.hadOverride)
        assertEquals(320, snapshot.effectiveDensity)
        assertEquals(320, snapshot.previousOverrideDensity)
    }

    @Test
    fun factorySnapshotRequiresClearingOverride() {
        val snapshot = DensitySnapshot(
            physicalDensity = 440,
            effectiveDensity = 440,
            hadOverride = false,
            previousOverrideDensity = null
        )

        assertFalse(snapshot.hadOverride)
        assertEquals(snapshot.physicalDensity, snapshot.effectiveDensity)
        assertNull(snapshot.previousOverrideDensity)
    }
}
