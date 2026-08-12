package com.zaid.densityreset.booster

import kotlinx.coroutines.flow.Flow

enum class BoosterMode(
    val displayName: String,
    val shortDescription: String,
    val userDescription: String
) {
    GAME(
        displayName = "Modo Juego",
        shortDescription = "Equilibrado",
        userDescription = "Buen rendimiento sin aumentar demasiado el consumo."
    ),
    BATTERY(
        displayName = "Ahorro de batería",
        shortDescription = "Mayor autonomía",
        userDescription = "Reduce el consumo para poder jugar durante más tiempo."
    ),
    MAX_PERFORMANCE(
        displayName = "Máximo rendimiento",
        shortDescription = "Más rendimiento",
        userDescription = "Prioriza la fluidez. Puede consumir más batería y generar más temperatura."
    )
}

enum class RomFamily {
    HYPER_OS,
    MIUI,
    ONE_UI,
    COLOR_OS,
    REALME_UI,
    AOSP,
    UNKNOWN
}

enum class DeviceVendor {
    XIAOMI,
    SAMSUNG,
    OPLUS,
    GENERIC
}

data class DeviceProfile(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val fingerprint: String,
    val vendor: DeviceVendor,
    val romFamily: RomFamily
) {
    val romDisplayName: String
        get() = when (romFamily) {
            RomFamily.HYPER_OS -> "HyperOS"
            RomFamily.MIUI -> "MIUI"
            RomFamily.ONE_UI -> "One UI"
            RomFamily.COLOR_OS -> "ColorOS"
            RomFamily.REALME_UI -> "Realme UI"
            RomFamily.AOSP -> "AOSP"
            RomFamily.UNKNOWN -> when (vendor) {
                DeviceVendor.XIAOMI -> "Xiaomi"
                DeviceVendor.SAMSUNG -> "Samsung"
                DeviceVendor.OPLUS -> "Oplus"
                DeviceVendor.GENERIC -> "ROM desconocida"
            }
        }

    val adapterDisplayName: String
        get() = when (vendor) {
            DeviceVendor.XIAOMI -> "Xiaomi / $romDisplayName"
            DeviceVendor.SAMSUNG -> "Samsung / $romDisplayName"
            DeviceVendor.OPLUS -> "Oplus / $romDisplayName"
            DeviceVendor.GENERIC -> "AOSP / $romDisplayName"
        }
}

data class BoosterCapabilities(
    val gameManagerAvailable: Boolean = false,
    val standardModeAvailable: Boolean = false,
    val performanceModeAvailable: Boolean = false,
    val batteryModeAvailable: Boolean = false,
    val fpsMonitoringAvailable: Boolean = false,
    val thermalMonitoringAvailable: Boolean = false,
    val memoryMonitoringAvailable: Boolean = false,
    val vendorGameServiceAvailable: Boolean = false
)

data class BoosterSnapshot(
    val packageName: String,
    val romFamily: RomFamily,
    val previousGameMode: String?,
    val previousValues: Map<String, String>,
    val startedAt: Long,
    val selectedMode: BoosterMode? = null,
    val vendor: DeviceVendor = DeviceVendor.GENERIC,
    val gameModeChanged: Boolean = false
)

data class BoosterAction(
    val name: String,
    val detail: String,
    val applied: Boolean
)

enum class RamLevel(val displayName: String) {
    EXCELLENT("Excelente"),
    NORMAL("Normal"),
    LOW("Baja")
}

data class RamInfo(
    val availableBytes: Long,
    val totalBytes: Long,
    val lowMemory: Boolean,
    val thresholdBytes: Long,
    val level: RamLevel
)

data class BatteryInfo(
    val percent: Int,
    val charging: Boolean,
    val startPercent: Int?,
    val consumedSinceStart: Int?,
    val sessionStartedAt: Long
)

enum class ThermalLevel(val displayName: String) {
    NORMAL("Normal"),
    WARM("Templado"),
    HOT("Caliente"),
    VERY_HOT("Muy caliente"),
    UNKNOWN("No disponible")
}

enum class ThermalSource(val displayName: String) {
    ANDROID_THERMAL_STATUS("Estado térmico de Android"),
    BATTERY("Batería"),
    UNKNOWN("No disponible")
}

data class ThermalInfo(
    val temperatureCelsius: Float?,
    val level: ThermalLevel,
    val source: ThermalSource
)

enum class FpsConfidence {
    HIGH,
    MEDIUM,
    UNAVAILABLE
}

enum class FpsSource {
    GFXINFO_FRAMESTATS,
    UNAVAILABLE
}

data class FpsInfo(
    val fps: Float?,
    val confidence: FpsConfidence,
    val source: FpsSource
)

data class GamePerformanceState(
    val ram: RamInfo? = null,
    val battery: BatteryInfo? = null,
    val thermal: ThermalInfo? = null,
    val fps: FpsInfo? = null
)

data class GameBoosterState(
    val active: Boolean = false,
    val mode: BoosterMode? = null,
    val packageName: String? = null,
    val rom: RomFamily = RomFamily.UNKNOWN,
    val deviceProfile: DeviceProfile? = null,
    val capabilities: BoosterCapabilities = BoosterCapabilities(),
    val actionsApplied: List<BoosterAction> = emptyList(),
    val monitor: GamePerformanceState = GamePerformanceState(),
    val message: String? = null
)

sealed interface BoosterResult {
    data class Success(
        val message: String,
        val actions: List<BoosterAction> = emptyList()
    ) : BoosterResult

    data class Failure(val message: String) : BoosterResult
}

interface GameBoosterController {
    suspend fun prepare(
        packageName: String,
        mode: BoosterMode
    ): BoosterResult

    suspend fun restore(): BoosterResult

    fun observeState(): Flow<GameBoosterState>
}
