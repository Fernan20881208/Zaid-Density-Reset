package com.zaid.densityreset.booster

import android.os.Build
import com.zaid.densityreset.gameprofile.shizuku.ShizukuCommandExecutor

interface RomGameAdapter {
    val romFamily: RomFamily

    suspend fun detectCapabilities(): BoosterCapabilities

    suspend fun applyGameMode(packageName: String): Result<Unit>

    suspend fun applyBatteryMode(packageName: String): Result<Unit>

    suspend fun applyPerformanceMode(packageName: String): Result<Unit>

    suspend fun restore(snapshot: BoosterSnapshot): Result<Unit>
}

internal data class GameModeDiagnostic(
    val gameManagerAvailable: Boolean,
    val currentMode: String?,
    val availableModes: Set<String>,
    val helpText: String,
    val fpsBackendAvailable: Boolean
) {
    fun supports(mode: String): Boolean = mode.lowercase() in availableModes
}

internal class GameModeCapabilityProbe(
    private val commandExecutor: ShizukuCommandExecutor
) {
    suspend fun diagnose(packageName: String): GameModeDiagnostic {
        val helpResult = commandExecutor.execute(
            arrayOf("/system/bin/cmd", "game", "help"),
            timeoutSeconds = COMMAND_TIMEOUT_SECONDS
        ).getOrNull()
        val help = helpResult?.takeIf { it.isSuccess }
            ?.let { listOf(it.stdout, it.stderr).joinToString("\n") }
            .orEmpty()
        val gameManagerAvailable = help.contains("game manager", ignoreCase = true) ||
            help.contains("list-modes", ignoreCase = true) ||
            help.contains("mode", ignoreCase = true)

        var currentMode: String? = null
        var availableModes = emptySet<String>()
        if (gameManagerAvailable && help.contains("list-modes", ignoreCase = true)) {
            val modesResult = commandExecutor.execute(
                arrayOf("/system/bin/cmd", "game", "list-modes", packageName),
                timeoutSeconds = COMMAND_TIMEOUT_SECONDS
            ).getOrNull()
            if (modesResult?.isSuccess == true) {
                val parsed = parseGameModeList(modesResult.stdout)
                currentMode = parsed.first
                availableModes = parsed.second
            }
        }

        // This only checks that the gfxinfo framestats backend itself is callable.
        // Real frame data may not exist until the game has rendered frames; the
        // runtime FPS monitor validates every sample and keeps FPS unavailable
        // instead of inventing a fallback value.
        val fpsBackendAvailable = commandExecutor.execute(
            arrayOf("/system/bin/dumpsys", "gfxinfo", packageName, "framestats"),
            timeoutSeconds = COMMAND_TIMEOUT_SECONDS
        ).getOrNull()?.let { result ->
            result.isSuccess &&
                !result.stdout.contains("no process found", ignoreCase = true) &&
                !result.stderr.contains("not found", ignoreCase = true)
        } ?: false

        return GameModeDiagnostic(
            gameManagerAvailable = gameManagerAvailable,
            currentMode = currentMode,
            availableModes = availableModes,
            helpText = help,
            fpsBackendAvailable = fpsBackendAvailable
        )
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 5L
    }
}

abstract class BaseRomGameAdapter(
    protected val packageName: String,
    protected val commandExecutor: ShizukuCommandExecutor,
    protected val diagnostic: GameModeDiagnostic
) : RomGameAdapter {

    override suspend fun detectCapabilities(): BoosterCapabilities = BoosterCapabilities(
        gameManagerAvailable = diagnostic.gameManagerAvailable && diagnostic.currentMode != null,
        standardModeAvailable = diagnostic.currentMode != null && diagnostic.supports(MODE_STANDARD),
        performanceModeAvailable = diagnostic.currentMode != null && diagnostic.supports(MODE_PERFORMANCE),
        batteryModeAvailable = diagnostic.currentMode != null && diagnostic.supports(MODE_BATTERY),
        fpsMonitoringAvailable = diagnostic.fpsBackendAvailable,
        thermalMonitoringAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        memoryMonitoringAvailable = true,
        vendorGameServiceAvailable = detectVendorGameService()
    )

    override suspend fun applyGameMode(packageName: String): Result<Unit> =
        applyMode(packageName, MODE_STANDARD)

    override suspend fun applyBatteryMode(packageName: String): Result<Unit> =
        applyMode(packageName, MODE_BATTERY)

    override suspend fun applyPerformanceMode(packageName: String): Result<Unit> =
        applyMode(packageName, MODE_PERFORMANCE)

    override suspend fun restore(snapshot: BoosterSnapshot): Result<Unit> {
        val previous = snapshot.previousGameMode
            ?.trim()
            ?.lowercase()
            ?.takeIf { it in RESTORABLE_MODES }
            ?: return Result.failure(
                IllegalStateException("No se conoce el Game Mode anterior para restaurarlo.")
            )

        if (!diagnostic.supports(previous) && previous != MODE_STANDARD) {
            return Result.failure(
                IllegalStateException("El modo anterior ya no está disponible para restaurarlo.")
            )
        }
        return setAndVerifyMode(snapshot.packageName, previous)
    }

    protected open suspend fun detectVendorGameService(): Boolean = false

    protected suspend fun packageExists(packageName: String): Boolean {
        val result = commandExecutor.execute(
            arrayOf("/system/bin/pm", "path", packageName),
            timeoutSeconds = 4L
        ).getOrNull() ?: return false
        return result.isSuccess && result.stdout.contains("package:")
    }

    private suspend fun applyMode(packageName: String, mode: String): Result<Unit> {
        if (diagnostic.currentMode == null) {
            return Result.failure(
                IllegalStateException("Game Mode no puede restaurarse de forma segura en este dispositivo.")
            )
        }
        if (!diagnostic.supports(mode)) {
            return Result.failure(
                IllegalStateException("${mode.userModeName()} no está disponible para este juego.")
            )
        }
        return setAndVerifyMode(packageName, mode)
    }

    private suspend fun setAndVerifyMode(packageName: String, mode: String): Result<Unit> {
        val result = commandExecutor.execute(
            arrayOf("/system/bin/cmd", "game", "mode", mode, packageName),
            timeoutSeconds = 6L
        ).getOrElse { return Result.failure(it) }
        if (!result.isSuccess) {
            return Result.failure(
                IllegalStateException(
                    result.stderr.ifBlank { result.stdout.ifBlank { "Game Mode rechazó el cambio." } }
                )
            )
        }

        val verify = commandExecutor.execute(
            arrayOf("/system/bin/cmd", "game", "list-modes", packageName),
            timeoutSeconds = 5L
        ).getOrElse { return Result.failure(it) }
        if (!verify.isSuccess) {
            return Result.failure(IllegalStateException("No se pudo verificar Game Mode."))
        }
        val current = parseGameModeList(verify.stdout).first
        return if (current == mode) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException("Game Mode no confirmó ${mode.userModeName()}.")
            )
        }
    }

    protected companion object {
        const val MODE_STANDARD = "standard"
        const val MODE_PERFORMANCE = "performance"
        const val MODE_BATTERY = "battery"
        const val MODE_CUSTOM = "custom"
        val RESTORABLE_MODES = setOf(
            MODE_STANDARD,
            MODE_PERFORMANCE,
            MODE_BATTERY,
            MODE_CUSTOM
        )
    }
}

class XiaomiRomAdapter internal constructor(
    packageName: String,
    commandExecutor: ShizukuCommandExecutor,
    diagnostic: GameModeDiagnostic,
    override val romFamily: RomFamily
) : BaseRomGameAdapter(packageName, commandExecutor, diagnostic) {
    override suspend fun detectVendorGameService(): Boolean =
        packageExists("com.xiaomi.joyose") ||
            packageExists("com.miui.securitycenter")
}

class SamsungRomAdapter internal constructor(
    packageName: String,
    commandExecutor: ShizukuCommandExecutor,
    diagnostic: GameModeDiagnostic
) : BaseRomGameAdapter(packageName, commandExecutor, diagnostic) {
    override val romFamily: RomFamily = RomFamily.ONE_UI

    override suspend fun detectVendorGameService(): Boolean =
        packageExists("com.samsung.android.game.gos") ||
            packageExists("com.samsung.android.game.gamehome")
}

class OplusRomAdapter internal constructor(
    packageName: String,
    commandExecutor: ShizukuCommandExecutor,
    diagnostic: GameModeDiagnostic,
    override val romFamily: RomFamily
) : BaseRomGameAdapter(packageName, commandExecutor, diagnostic) {
    override suspend fun detectVendorGameService(): Boolean =
        packageExists("com.oplus.games") ||
            packageExists("com.coloros.gamespaceui") ||
            packageExists("com.oppo.gamespace")
}

class AospRomAdapter internal constructor(
    packageName: String,
    commandExecutor: ShizukuCommandExecutor,
    diagnostic: GameModeDiagnostic,
    override val romFamily: RomFamily
) : BaseRomGameAdapter(packageName, commandExecutor, diagnostic)

private class DisabledRomGameAdapter(
    override val romFamily: RomFamily,
    private val diagnostic: GameModeDiagnostic
) : RomGameAdapter {
    override suspend fun detectCapabilities(): BoosterCapabilities = BoosterCapabilities(
        gameManagerAvailable = false,
        standardModeAvailable = false,
        performanceModeAvailable = false,
        batteryModeAvailable = false,
        fpsMonitoringAvailable = diagnostic.fpsBackendAvailable,
        thermalMonitoringAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        memoryMonitoringAvailable = true,
        vendorGameServiceAvailable = false
    )

    override suspend fun applyGameMode(packageName: String): Result<Unit> = unavailable()
    override suspend fun applyBatteryMode(packageName: String): Result<Unit> = unavailable()
    override suspend fun applyPerformanceMode(packageName: String): Result<Unit> = unavailable()
    override suspend fun restore(snapshot: BoosterSnapshot): Result<Unit> = Result.success(Unit)

    private fun unavailable(): Result<Unit> = Result.failure(
        IllegalStateException("El adaptador de Game Mode está desactivado por configuración remota.")
    )
}

internal fun createRomGameAdapter(
    profile: DeviceProfile,
    packageName: String,
    commandExecutor: ShizukuCommandExecutor,
    diagnostic: GameModeDiagnostic,
    xiaomiEnabled: Boolean,
    samsungEnabled: Boolean,
    oplusEnabled: Boolean,
    aospEnabled: Boolean
): RomGameAdapter {
    fun aospOrDisabled(): RomGameAdapter = if (aospEnabled) {
        AospRomAdapter(packageName, commandExecutor, diagnostic, profile.romFamily)
    } else {
        DisabledRomGameAdapter(profile.romFamily, diagnostic)
    }

    return when (profile.vendor) {
        DeviceVendor.XIAOMI -> if (xiaomiEnabled) {
            XiaomiRomAdapter(packageName, commandExecutor, diagnostic, profile.romFamily)
        } else {
            aospOrDisabled()
        }
        DeviceVendor.SAMSUNG -> if (samsungEnabled) {
            SamsungRomAdapter(packageName, commandExecutor, diagnostic)
        } else {
            aospOrDisabled()
        }
        DeviceVendor.OPLUS -> if (oplusEnabled) {
            OplusRomAdapter(packageName, commandExecutor, diagnostic, profile.romFamily)
        } else {
            aospOrDisabled()
        }
        DeviceVendor.GENERIC -> aospOrDisabled()
    }
}

internal fun parseGameModeList(output: String): Pair<String?, Set<String>> {
    val current = Regex(
        "current\\s+mode\\s*:\\s*([a-zA-Z0-9_-]+)",
        RegexOption.IGNORE_CASE
    ).find(output)?.groupValues?.getOrNull(1)?.lowercase()

    val list = Regex(
        "available\\s+game\\s+modes\\s*:\\s*\\[([^]]*)]",
        RegexOption.IGNORE_CASE
    ).find(output)?.groupValues?.getOrNull(1)
        ?.split(',')
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()

    return current to list
}

internal fun containsFrameStatsStructure(output: String): Boolean =
    output.contains("IntendedVsync", ignoreCase = true) &&
        output.contains("FrameCompleted", ignoreCase = true)

private fun String.userModeName(): String = when (this) {
    "standard" -> "Modo Juego"
    "battery" -> "Ahorro de batería"
    "performance" -> "Máximo rendimiento"
    else -> this
}
