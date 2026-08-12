package com.zaid.densityreset.booster

import com.zaid.densityreset.remoteconfig.RemoteAppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GameBoosterCoreTest {

    @Test
    fun exposesExactlyThreeBoosterModes() {
        assertEquals(
            listOf(
                BoosterMode.GAME,
                BoosterMode.BATTERY,
                BoosterMode.MAX_PERFORMANCE
            ),
            BoosterMode.entries
        )
    }

    @Test
    fun parsesGameManagerCurrentAndAvailableModes() {
        val output = """
            Game mode interventions: com.dts.freefireth
            Current mode: performance
            Available game modes: [standard, performance, battery, custom]
        """.trimIndent()

        val parsed = parseGameModeList(output)

        assertEquals("performance", parsed.first)
        assertEquals(
            setOf("standard", "performance", "battery", "custom"),
            parsed.second
        )
    }

    @Test
    fun detectsHyperOsOnlyFromExplicitSignals() {
        val profile = profile(
            manufacturer = "Xiaomi",
            brand = "POCO",
            properties = mapOf("ro.mi.os.version.name" to "OS3.0")
        )

        assertEquals(DeviceVendor.XIAOMI, profile.vendor)
        assertEquals(RomFamily.HYPER_OS, profile.romFamily)
    }

    @Test
    fun detectsMiuiFromMiuiProperties() {
        val profile = profile(
            manufacturer = "Xiaomi",
            brand = "Redmi",
            properties = mapOf("ro.miui.ui.version.name" to "V14")
        )

        assertEquals(DeviceVendor.XIAOMI, profile.vendor)
        assertEquals(RomFamily.MIUI, profile.romFamily)
    }

    @Test
    fun xiaomiWithoutReliableRomSignalStaysUnknown() {
        val profile = profile(manufacturer = "Xiaomi", brand = "POCO")

        assertEquals(DeviceVendor.XIAOMI, profile.vendor)
        assertEquals(RomFamily.UNKNOWN, profile.romFamily)
        assertFalse(profile.romDisplayName.contains("3"))
    }

    @Test
    fun detectsSamsungOneUiWithoutInventingVersion() {
        val profile = profile(manufacturer = "samsung", brand = "samsung")

        assertEquals(DeviceVendor.SAMSUNG, profile.vendor)
        assertEquals(RomFamily.ONE_UI, profile.romFamily)
        assertEquals("One UI", profile.romDisplayName)
    }

    @Test
    fun detectsColorOsForOppo() {
        val profile = profile(
            manufacturer = "OPPO",
            brand = "OPPO",
            properties = mapOf("ro.build.version.oplusrom" to "ColorOS")
        )

        assertEquals(DeviceVendor.OPLUS, profile.vendor)
        assertEquals(RomFamily.COLOR_OS, profile.romFamily)
    }

    @Test
    fun detectsRealmeUi() {
        val profile = profile(manufacturer = "realme", brand = "realme")

        assertEquals(DeviceVendor.OPLUS, profile.vendor)
        assertEquals(RomFamily.REALME_UI, profile.romFamily)
    }

    @Test
    fun detectsPixelAsAosp() {
        val profile = profile(
            manufacturer = "Google",
            brand = "google",
            model = "Pixel 10"
        )

        assertEquals(DeviceVendor.GENERIC, profile.vendor)
        assertEquals(RomFamily.AOSP, profile.romFamily)
    }

    @Test
    fun unknownDeviceRemainsUnknown() {
        val profile = profile(
            manufacturer = "ExampleVendor",
            brand = "ExampleBrand",
            model = "Example Phone"
        )

        assertEquals(DeviceVendor.GENERIC, profile.vendor)
        assertEquals(RomFamily.UNKNOWN, profile.romFamily)
    }

    @Test
    fun parsesGetpropWithoutAssumingKeysExist() {
        val parsed = parseGetprop(
            """
            [ro.mi.os.version.name]: [OS3.0]
            [ro.product.device]: [duchamp]
            ignored line
            """.trimIndent()
        )

        assertEquals("OS3.0", parsed["ro.mi.os.version.name"])
        assertEquals("duchamp", parsed["ro.product.device"])
        assertEquals(2, parsed.size)
    }

    @Test
    fun ramLevelsUsePercentageOfTotalMemory() {
        val total = 8L * 1024L * 1024L * 1024L

        assertEquals(
            RamLevel.EXCELLENT,
            classifyRamLevel((total * 0.60).toLong(), total, lowMemory = false)
        )
        assertEquals(
            RamLevel.NORMAL,
            classifyRamLevel((total * 0.25).toLong(), total, lowMemory = false)
        )
        assertEquals(
            RamLevel.LOW,
            classifyRamLevel((total * 0.10).toLong(), total, lowMemory = false)
        )
        assertEquals(
            RamLevel.LOW,
            classifyRamLevel((total * 0.70).toLong(), total, lowMemory = true)
        )
    }

    @Test
    fun thermalStatusesMapToSimpleLanguage() {
        assertEquals(ThermalLevel.NORMAL, mapThermalStatus(0))
        assertEquals(ThermalLevel.NORMAL, mapThermalStatus(1))
        assertEquals(ThermalLevel.WARM, mapThermalStatus(2))
        assertEquals(ThermalLevel.HOT, mapThermalStatus(3))
        assertEquals(ThermalLevel.VERY_HOT, mapThermalStatus(4))
        assertEquals(ThermalLevel.VERY_HOT, mapThermalStatus(5))
        assertEquals(ThermalLevel.VERY_HOT, mapThermalStatus(6))
        assertEquals(ThermalLevel.UNKNOWN, mapThermalStatus(99))
    }

    @Test
    fun calculatesRealFpsFromRecentFrameTimestamps() {
        val output = frameStats(frameCount = 61, intervalNanos = 16_666_667L)

        val sample = calculateFpsFromFrameStats(output, previousLatestTimestamp = null)

        assertTrue(sample.fps != null)
        assertTrue(abs(sample.fps!! - 60f) < 1.5f)
        assertEquals(FpsSource.GFXINFO_FRAMESTATS, sample.source)
        assertTrue(sample.confidence != FpsConfidence.UNAVAILABLE)
    }

    @Test
    fun staleFrameStatsRemainUnavailable() {
        val output = frameStats(frameCount = 40, intervalNanos = 16_666_667L)
        val latest = parseFrameTimestamps(output).last()

        val sample = calculateFpsFromFrameStats(output, previousLatestTimestamp = latest)

        assertNull(sample.fps)
        assertEquals(FpsConfidence.UNAVAILABLE, sample.confidence)
    }

    @Test
    fun refreshRateTextNeverBecomesFpsFallback() {
        listOf(60, 90, 120, 144).forEach { hz ->
            val output = "Display refresh rate: $hz Hz\nTotal frames rendered: $hz"
            val sample = calculateFpsFromFrameStats(output, previousLatestTimestamp = null)
            assertNull("$hz Hz must not be reported as FPS", sample.fps)
            assertEquals(FpsSource.UNAVAILABLE, sample.source)
        }
    }

    @Test
    fun malformedOrEmptyFrameStatsRemainUnavailable() {
        listOf(
            "",
            "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted\n---PROFILEDATA---",
            "No process found for: com.dts.freefireth"
        ).forEach { output ->
            val sample = calculateFpsFromFrameStats(output, null)
            assertNull(sample.fps)
        }
    }

    @Test
    fun remoteConfigOnlyEnablesCompiledBoosterBehaviors() {
        val config = RemoteAppConfig.DEFAULT

        assertTrue(config.gameBoosterEnabled)
        assertTrue(config.gameModeEnabled)
        assertTrue(config.batteryModeEnabled)
        assertTrue(config.maxPerformanceEnabled)
        assertTrue(config.ramMonitorEnabled)
        assertTrue(config.batteryMonitorEnabled)
        assertTrue(config.thermalMonitorEnabled)
        assertTrue(config.fpsMonitorEnabled)
        assertTrue(config.xiaomiAdapterEnabled)
        assertTrue(config.samsungAdapterEnabled)
        assertTrue(config.oplusAdapterEnabled)
        assertTrue(config.aospAdapterEnabled)
    }

    private fun profile(
        manufacturer: String,
        brand: String,
        model: String = "Phone",
        device: String = "device",
        fingerprint: String = "vendor/product/device:16/build:user/release-keys",
        properties: Map<String, String> = emptyMap()
    ): DeviceProfile = classifyDeviceProfile(
        manufacturer = manufacturer,
        brand = brand,
        model = model,
        device = device,
        fingerprint = fingerprint,
        properties = properties
    )

    private fun frameStats(frameCount: Int, intervalNanos: Long): String {
        val start = 10_000_000_000L
        return buildString {
            appendLine("Stats since: 1ns")
            appendLine("---PROFILEDATA---")
            appendLine("Flags,IntendedVsync,FrameCompleted")
            repeat(frameCount) { index ->
                val intended = start + index * intervalNanos
                appendLine("0,$intended,${intended + intervalNanos}")
            }
            appendLine("---PROFILEDATA---")
        }
    }
}
