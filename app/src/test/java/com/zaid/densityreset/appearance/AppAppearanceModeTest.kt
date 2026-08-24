package com.zaid.densityreset.appearance

import org.junit.Assert.assertEquals
import org.junit.Test

class AppAppearanceModeTest {

    @Test
    fun liquidGlassRemainsTheSafeDefault() {
        assertEquals(
            AppAppearanceMode.LIQUID_GLASS,
            AppAppearanceMode.fromPersistedValue(null)
        )
        assertEquals(
            AppAppearanceMode.LIQUID_GLASS,
            AppAppearanceMode.fromPersistedValue("unknown")
        )
    }

    @Test
    fun persistedModesRoundTrip() {
        AppAppearanceMode.entries.forEach { mode ->
            assertEquals(
                mode,
                AppAppearanceMode.fromPersistedValue(mode.persistedValue)
            )
        }
    }
}
