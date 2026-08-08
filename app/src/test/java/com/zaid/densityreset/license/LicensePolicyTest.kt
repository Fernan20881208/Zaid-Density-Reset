package com.zaid.densityreset.license

import com.zaid.densityreset.license.util.LicensePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicensePolicyTest {
    private val hour = 60L * 60L * 1_000L

    @Test
    fun permitsValidGracePeriod() {
        val now = 1_000_000_000L
        assertTrue(
            LicensePolicy.canUseOffline(
                nowEpochMillis = now,
                lastSuccessfulValidationEpochMillis = now - 2 * hour,
                licenseExpiresAtEpochMillis = now + 24 * hour,
                tokenExpiresAtEpochMillis = now + 24 * hour,
                gracePeriodMillis = 12 * hour
            )
        )
    }

    @Test
    fun rejectsExpiredGracePeriod() {
        val now = 1_000_000_000L
        assertFalse(
            LicensePolicy.canUseOffline(
                nowEpochMillis = now,
                lastSuccessfulValidationEpochMillis = now - 13 * hour,
                licenseExpiresAtEpochMillis = now + 24 * hour,
                tokenExpiresAtEpochMillis = now + 24 * hour,
                gracePeriodMillis = 12 * hour
            )
        )
    }

    @Test
    fun neverExtendsTemporaryLicenseOrExpiredToken() {
        val now = 1_000_000_000L
        assertFalse(
            LicensePolicy.canUseOffline(now, now - hour, now - 1, now + hour, 12 * hour)
        )
        assertFalse(
            LicensePolicy.canUseOffline(now, now - hour, null, now - 1, 12 * hour)
        )
    }
}
