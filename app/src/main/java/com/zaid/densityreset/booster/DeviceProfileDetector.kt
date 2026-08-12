package com.zaid.densityreset.booster

import android.os.Build
import com.zaid.densityreset.gameprofile.shizuku.ShizukuCommandExecutor

class DeviceProfileDetector(
    private val commandExecutor: ShizukuCommandExecutor = ShizukuCommandExecutor()
) {

    suspend fun detect(): DeviceProfile {
        val props = readProperties()
        return classifyDeviceProfile(
            manufacturer = Build.MANUFACTURER.orEmpty().trim(),
            brand = Build.BRAND.orEmpty().trim(),
            model = Build.MODEL.orEmpty().trim(),
            device = Build.DEVICE.orEmpty().trim(),
            fingerprint = Build.FINGERPRINT.orEmpty().trim(),
            properties = props
        )
    }

    private suspend fun readProperties(): Map<String, String> {
        val result = commandExecutor.execute(
            arrayOf("/system/bin/getprop"),
            timeoutSeconds = GETPROP_TIMEOUT_SECONDS
        ).getOrNull() ?: return emptyMap()
        if (!result.isSuccess) return emptyMap()

        return parseGetprop(result.stdout)
    }

    private companion object {
        const val GETPROP_TIMEOUT_SECONDS = 5L
    }
}

internal fun classifyDeviceProfile(
    manufacturer: String,
    brand: String,
    model: String,
    device: String,
    fingerprint: String,
    properties: Map<String, String>
): DeviceProfile {
    val cleanManufacturer = manufacturer.trim()
    val cleanBrand = brand.trim()
    val cleanModel = model.trim()
    val cleanDevice = device.trim()
    val cleanFingerprint = fingerprint.trim()
    val identity = listOf(
        cleanManufacturer,
        cleanBrand,
        cleanModel,
        cleanDevice,
        cleanFingerprint
    ).joinToString(" ").lowercase()

    val vendor = when {
        identity.containsAny("xiaomi", "redmi", "poco") -> DeviceVendor.XIAOMI
        identity.contains("samsung") -> DeviceVendor.SAMSUNG
        identity.containsAny("oppo", "oplus", "oneplus", "realme") -> DeviceVendor.OPLUS
        else -> DeviceVendor.GENERIC
    }

    val rom = when (vendor) {
        DeviceVendor.XIAOMI -> detectXiaomiRom(properties, cleanFingerprint)
        DeviceVendor.SAMSUNG -> RomFamily.ONE_UI
        DeviceVendor.OPLUS -> detectOplusRom(properties, identity)
        DeviceVendor.GENERIC -> detectGenericRom(identity)
    }

    return DeviceProfile(
        manufacturer = cleanManufacturer.ifBlank { "Android" },
        brand = cleanBrand.ifBlank { cleanManufacturer.ifBlank { "Android" } },
        model = cleanModel.ifBlank { cleanDevice.ifBlank { "Dispositivo Android" } },
        device = cleanDevice,
        fingerprint = cleanFingerprint,
        vendor = vendor,
        romFamily = rom
    )
}

internal fun parseGetprop(output: String): Map<String, String> = buildMap {
    GETPROP_LINE.findAll(output).forEach { match ->
        val key = match.groupValues.getOrNull(1).orEmpty().trim()
        val value = match.groupValues.getOrNull(2).orEmpty().trim()
        if (key.isNotEmpty()) put(key, value)
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

private val GETPROP_LINE = Regex(
    """^\[([^]]+)]\s*:\s*\[(.*)]$""",
    RegexOption.MULTILINE
)
