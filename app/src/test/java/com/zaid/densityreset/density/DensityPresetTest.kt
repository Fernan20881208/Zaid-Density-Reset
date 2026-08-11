package com.zaid.densityreset.density

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DensityPresetTest {

    @Test
    fun exposesExactlyFivePresetsWithRequiredFallbackDensities() {
        assertEquals(5, DensityPreset.entries.size)
        assertEquals(20, DensityPreset.ULTRA.fallbackDensity)
        assertEquals(46, DensityPreset.VERY_HIGH.fallbackDensity)
        assertEquals(72, DensityPreset.HIGH.fallbackDensity)
        assertEquals(176, DensityPreset.MEDIUM_HIGH.fallbackDensity)
        assertEquals(280, DensityPreset.LOW.fallbackDensity)
    }

    @Test
    fun visualOrderRunsFromLeastToMostAggressive() {
        assertEquals(
            listOf(
                DensityPreset.LOW,
                DensityPreset.MEDIUM_HIGH,
                DensityPreset.HIGH,
                DensityPreset.VERY_HIGH,
                DensityPreset.ULTRA
            ),
            DensityPreset.visualOrder
        )
    }

    @Test
    fun onlyFallbackDensitiesBelow72RequireBinderRoute() {
        assertTrue(DensityPreset.ULTRA.fallbackDensity < DensityPreset.STANDARD_DENSITY_THRESHOLD)
        assertTrue(DensityPreset.VERY_HIGH.fallbackDensity < DensityPreset.STANDARD_DENSITY_THRESHOLD)
        assertFalse(DensityPreset.HIGH.fallbackDensity < DensityPreset.STANDARD_DENSITY_THRESHOLD)
        assertFalse(DensityPreset.MEDIUM_HIGH.fallbackDensity < DensityPreset.STANDARD_DENSITY_THRESHOLD)
        assertFalse(DensityPreset.LOW.fallbackDensity < DensityPreset.STANDARD_DENSITY_THRESHOLD)
    }
}
