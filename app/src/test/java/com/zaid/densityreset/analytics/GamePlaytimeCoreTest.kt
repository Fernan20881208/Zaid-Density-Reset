package com.zaid.densityreset.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class GamePlaytimeCoreTest {

    @Test
    fun playtimeRejectsNegativeDurationsAndCapsCorruptSessions() {
        assertEquals(0L, sanitizePlaytimeDuration(2_000L, 1_000L))
        assertEquals(60_000L, sanitizePlaytimeDuration(1_000L, 61_000L))
        assertEquals(
            24L * 60L * 60L * 1_000L,
            sanitizePlaytimeDuration(1L, Long.MAX_VALUE)
        )
    }
}
