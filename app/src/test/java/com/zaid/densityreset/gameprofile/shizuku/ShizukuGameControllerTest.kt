package com.zaid.densityreset.gameprofile.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShizukuGameControllerTest {

    @Test
    fun parsesCurrentResumedActivityPackage() {
        val output = """
            ACTIVITY MANAGER ACTIVITIES (dumpsys activity activities)
              mResumedActivity: ActivityRecord{123abc u0 com.dts.freefireth/com.dts.freefireth.FFMainActivity t42}
        """.trimIndent()

        assertEquals("com.dts.freefireth", parseForegroundPackage(output))
    }

    @Test
    fun parsesTopResumedActivityPackage() {
        val output = """
            RootTask #1
              topResumedActivity=ActivityRecord{456def u0 com.dts.freefiremax/com.dts.freefiremax.FFMainActivity t88}
        """.trimIndent()

        assertEquals("com.dts.freefiremax", parseForegroundPackage(output))
    }

    @Test
    fun returnsNullWhenNoResumedActivityExists() {
        assertNull(parseForegroundPackage("ACTIVITY MANAGER ACTIVITIES"))
    }
}
