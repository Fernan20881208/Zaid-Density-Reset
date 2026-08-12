package com.zaid.densityreset.booster

import android.os.Build
import com.zaid.densityreset.gameprofile.shizuku.ShizukuCommandExecutor

class DeviceProfileDetector(
    private val commandExecutor: ShizukuCommandExecutor = ShizukuCommandExecutor()
) {

    suspend fun detect(): DeviceProfile {
        val props = readProperties()
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val brand = Build.BRAND.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val device = Build.DEVICE.orEmpty().trim()
        val fingerprint = Build.FINGERPRINT.orEmpty().trim()

        val identity = listOf(manufacturer, brand, model, device, fingerprint)
            .joinToString(" ")
            .lowercase()

        val vendor = when {
            identity.containsAny("xiaomi", "redmi", "poco") -> DeviceVendor.XIAOMI
            identity.contains("samsung") -> DeviceVendor.SAMSUNG
            identity.containsAny("oppo", "oplus", "oneplus", "realme") -> DeviceVendor.OPLUS
            else -> DeviceVendor.GENERIC
        }

        val rom = when (vendor) {
            DeviceVendor.XIAOMI -> detectXiaomiRom(props, fingerprint)
            DeviceVendor.SAMSUNG -> RomFamily.ONE_UI
            DeviceVendor.OPLUS -> detectOplusRom(props, identity)
            DeviceVendor.GENERIC -> detectGenericRom(identity)
        }

        return DeviceProfile(
            manufacturer = manufacturer.ifBlank { "Android" },
            brand = brand.ifBlank { manufacturer.ifBlank { "Android" } },
            model = model.ifBlank { device.ifBlank { "Dispositivo Android" } },
            device = device,
            fingerprint = fingerprint,
            vendor = vendor,
            romFamily = rom
        )
    }

    private suspend fun readProperties(): Map<String, String> {
        val result = commandExecutor.execute(
            arrayOf("/system/bin/getprop"),
            timeoutSeconds = GETPROP_TIMEOUT_SECONDS
        ).getOrNull() ?: return emptyMap()
        if (!result.isSuccess) return emptyMap()

        return buildMap {
            GETPROP_LINE.findAll(result.stdout).forEach { match ->
                val key = match.groupValues.getOrNull(1).orEmpty().trim()
                val value = match.groupValues.getOrNull(2).orEmpty().trim()
                if (key.isNotEmpty()) put(key, value)
            }
        }
    }

    private fun detectXiaomiRom(
        props: Map<String, String>,
        fingerprint: String
    ): RomFamily {
        val hyperOsSignals = listOf(
            "ro.mi.os.version.name",
            "ro.mi.os.version.code",
            "ro.mi.os.version.incremental"
        ).any { !props[it].isNullOrBlank() }
        if (hyperOsSignals || fingerprint.contains("hyperos", ignoreCase = true)) {
            return RomFamily.HYPER_OS
        }

        val miuiSignals = listOf(
            "ro.miui.ui.version.name",
            "ro.miui.ui.version.code",
            "ro.miui.version.code_time"
        ).any { !props[it].isNullOrBlank() }
        return if (miuiSignals) RomFamily.MIUI else RomFamily.UNKNOWN
    }

    private fun detectOplusRom(
        props: Map<String, String>,
        identity: String
    ): RomFamily {
        if (identity.contains("realme")) return RomFamily.REALME_UI

        val realmeSignals = props.entries.any { (key, value) ->
            key.contains("realme", ignoreCase = true) ||
                value.contains("realme", ignoreCase = true)
        }
        if (realmeSignals) return RomFamily.REALME_UI

        val colorSignals = props.entries.any { (key, value) ->
            key.contains("coloros", ignoreCase = true) ||
                key.contains("oplusrom", ignoreCase = true) ||
                value.contains("coloros", ignoreCase = true)
        }
        return if (colorSignals || identity.contains("oppo")) {
            RomFamily.COLOR_OS
        } else {
            RomFamily.UNKNOWN
        }
    }

    private fun detectGenericRom(identity: String): RomFamily =
        if (
            identity.contains("google") ||
            identity.contains("pixel") ||
            identity.contains("aosp") ||
            identity.contains("generic")
        ) {
            RomFamily.AOSP
        } else {
            RomFamily.UNKNOWN
        }

    private fun String.containsAny(vararg values: String): Boolean =
        values.any(::contains)

    private companion object {
        const val GETPROP_TIMEOUT_SECONDS = 5L
        val GETPROP_LINE = Regex("""^\[([^]]+)]\s*:\s*\[(.*)]$""", RegexOption.MULTILINE)
    }
}
