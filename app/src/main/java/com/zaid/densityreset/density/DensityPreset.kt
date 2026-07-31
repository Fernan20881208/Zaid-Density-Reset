package com.zaid.densityreset.density

enum class DensityPreset(
    val displayName: String,
    val density: Int,
    val description: String
) {
    ULTRA(
        displayName = "Sensi Ultra",
        density = 20,
        description = "Escala extrema"
    ),
    HIGH(
        displayName = "Sensi Alta",
        density = 72,
        description = "Escala competitiva"
    ),
    LOW(
        displayName = "Sensi Baja",
        density = 280,
        description = "Escala equilibrada"
    );

    val title: String
        get() = displayName

    companion object {
        fun fromDensity(density: Int): DensityPreset? =
            entries.firstOrNull { it.density == density }
    }
}
