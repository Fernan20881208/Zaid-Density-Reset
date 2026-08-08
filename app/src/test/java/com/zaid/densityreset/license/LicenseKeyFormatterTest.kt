package com.zaid.densityreset.license

import com.zaid.densityreset.license.util.LicenseKeyFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LicenseKeyFormatterTest {
    @Test
    fun normalizesEquivalentInputs() {
        val expected = "DR-7K4P-M9QX-2W8N-R5TY"
        assertEquals(expected, LicenseKeyFormatter.normalize("dr-7k4p-m9qx-2w8n-r5ty"))
        assertEquals(expected, LicenseKeyFormatter.normalize("  DR-7K4P-M9QX-2W8N-R5TY  "))
        assertEquals(expected, LicenseKeyFormatter.normalize("DR 7K4P M9QX 2W8N R5TY"))
    }

    @Test
    fun validatesOnlyCompleteKeyShape() {
        assertTrue(LicenseKeyFormatter.isValid("DR-7K4P-M9QX-2W8N-R5TY"))
        assertFalse(LicenseKeyFormatter.isValid("DR-7K4P-M9QX"))
    }

    @Test
    fun formatsIncrementalInput() {
        assertEquals("DR-K9XP-4T2M", LicenseKeyFormatter.formatForInput("k9xp4t2m"))
    }
}
