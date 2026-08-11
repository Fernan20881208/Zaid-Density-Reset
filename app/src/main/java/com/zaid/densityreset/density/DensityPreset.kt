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
        description = "Sensibilidad extrema"
    ),
    VERY_HIGH(
        displayName = "Sensi Muy Alta",
        fallbackDensity = 46,
        description = "Sensibilidad muy alta"
    ),
    HIGH(
        displayName = "Sensi Alta",
        fallbackDensity = 72,
        description = "Sensibilidad alta"
    ),
    MEDIUM_HIGH(
        displayName = "Sensi Media Alta",
        fallbackDensity = 176,
        description = "Sensibilidad media-alta"
    ),
    LOW(
        displayName = "Sensi Baja",
        fallbackDensity = 280,
        description = "Escala equilibrada"
    );

    val density: Int
        get() = when (this) {
            ULTRA -> RemoteConfigManager.currentConfig().ultraDensity
            VERY_HIGH -> RemoteConfigManager.currentConfig().veryHighDensity
            HIGH -> RemoteConfigManager.currentConfig().highDensity
            MEDIUM_HIGH -> RemoteConfigManager.currentConfig().mediumHighDensity
            LOW -> RemoteConfigManager.currentConfig().lowDensity
        }

    val enabled: Boolean
        get() = when (this) {
            ULTRA -> RemoteConfigManager.currentConfig().ultraEnabled
            VERY_HIGH -> RemoteConfigManager.currentConfig().veryHighEnabled
            HIGH -> RemoteConfigManager.currentConfig().highEnabled
            MEDIUM_HIGH -> RemoteConfigManager.currentConfig().mediumHighEnabled
            LOW -> RemoteConfigManager.currentConfig().lowEnabled
        }

    val title: String
        get() = displayName

    val requiresLowDensityBinder: Boolean
        get() = density < STANDARD_DENSITY_THRESHOLD

    companion object {
        const val STANDARD_DENSITY_THRESHOLD = 72

        val visualOrder: List<DensityPreset> = listOf(
            LOW,
            MEDIUM_HIGH,
            HIGH,
            VERY_HIGH,
            ULTRA
        )

        fun fromDensity(density: Int): DensityPreset? =
            entries.firstOrNull { it.density == density }
    }
}
