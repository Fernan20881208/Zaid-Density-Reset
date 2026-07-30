package com.zaid.densityreset.density

enum class DensityPreset(
    val title: String,
    val density: Int,
    val description: String
) {
    ULTRA(
        title = "Sensi Ultra",
        density = 20,
        description = "Sensibilidad extrema"
    ),
    HIGH(
        title = "Sensi Alta",
        density = 72,
        description = "Sensibilidad alta"
    ),
    LOW(
        title = "Sensi Baja",
        density = 280,
        description = "Sensibilidad estable"
    );

    companion object {
        fun fromDensity(density: Int): DensityPreset? =
            entries.firstOrNull { it.density == density }
    }
}
