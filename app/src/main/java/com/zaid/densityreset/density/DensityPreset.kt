package com.zaid.densityreset.density

import com.zaid.densityreset.remoteconfig.RemoteConfigManager

enum class DensityPreset(
    val displayName: String,
    val fallbackDensity: Int,
    val description: String
) {
    ULTRA(
        displayName = "Sensi Ultra",
        fallbackDensity = 20,
        description = "Escala extrema"
    ),
    HIGH(
        displayName = "Sensi Alta",
        fallbackDensity = 72,
        description = "Escala competitiva"
    ),
    LOW(
        displayName = "Sensi Baja",
        fallbackDensity = 280,
        description = "Escala equilibrada"
    );

    val density: Int
        get() = when (this) {
            ULTRA -> RemoteConfigManager.currentConfig().ultraDensity
            HIGH -> RemoteConfigManager.currentConfig().highDensity
            LOW -> RemoteConfigManager.currentConfig().lowDensity
        }

    val enabled: Boolean
        get() = when (this) {
            ULTRA -> RemoteConfigManager.currentConfig().ultraEnabled
            HIGH -> RemoteConfigManager.currentConfig().highEnabled
            LOW -> RemoteConfigManager.currentConfig().lowEnabled
        }

    val title: String
        get() = displayName

    companion object {
        fun fromDensity(density: Int): DensityPreset? =
            entries.firstOrNull { it.density == density }
    }
}
