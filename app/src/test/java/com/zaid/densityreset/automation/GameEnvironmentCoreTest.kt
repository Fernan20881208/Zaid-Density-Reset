package com.zaid.densityreset.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class GameEnvironmentCoreTest {

    @Test
    fun brightnessPercentageIsClampedToSafeSystemRange() {
        assertEquals(1, percentToSystemBrightness(-10))
        assertEquals(128, percentToSystemBrightness(50))
        assertEquals(255, percentToSystemBrightness(120))
    }

    @Test
    fun mediaVolumePercentageUsesTheRealStreamMaximum() {
        assertEquals(0, percentToStreamVolume(-1, 15))
        assertEquals(8, percentToStreamVolume(50, 15))
        assertEquals(15, percentToStreamVolume(110, 15))
        assertEquals(0, percentToStreamVolume(80, 0))
    }
}
