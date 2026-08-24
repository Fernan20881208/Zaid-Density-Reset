package com.zaid.densityreset.startup

import com.zaid.densityreset.gameprofile.domain.GameSessionState
import com.zaid.densityreset.gameprofile.domain.SessionStep
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupSessionRecoveryTest {

    @Test
    fun boosterTrackingIsSafeAfterDpiWasRestored() {
        assertTrue(
            isCriticalDensityResolved(
                GameSessionState(
                    sessionActive = true,
                    currentStep = SessionStep.BOOSTER_ACTIVE
                )
            )
        )
        assertFalse(
            isCriticalDensityResolved(
                GameSessionState(
                    sessionActive = true,
                    currentStep = SessionStep.APPLYING_DENSITY
                )
            )
        )
        assertTrue(isCriticalDensityResolved(GameSessionState(sessionActive = false)))
    }
}
