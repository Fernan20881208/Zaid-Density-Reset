package com.zaid.densityreset.booster

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.zaid.densityreset.gameprofile.shizuku.ShizukuCommandExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

interface FpsMonitor {
    suspend fun start(packageName: String)
    suspend fun stop()
    fun observeFps(): Flow<FpsInfo>
}

data class MonitorFlags(
    val ram: Boolean,
    val battery: Boolean,
    val thermal: Boolean,
    val fps: Boolean
)

class GamePerformanceMonitor(
    context: Context,
    private val commandExecutor: ShizukuCommandExecutor = ShizukuCommandExecutor()
) {
    private val appContext = context.applicationContext
    private val activityManager = appContext.getSystemService(ActivityManager::class.java)
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fpsMonitor: FpsMonitor = ShellFpsMonitor(commandExecutor)

    private val _state = MutableStateFlow(GamePerformanceState())
    val state: StateFlow<GamePerformanceState> = _state.asStateFlow()

    private var monitorJob: Job? = null
    private var fpsCollectorJob: Job? = null
    private var sessionStartedAt = 0L
    private var startBatteryPercent: Int? = null

    suspend fun start(
        packageName: String,
        flags: MonitorFlags,
        capabilities: BoosterCapabilities
    ) {
        stop()
        sessionStartedAt = System.currentTimeMillis()
        startBatteryPercent = readBattery()?.percent
        _state.value = GamePerformanceState(
            ram = if (flags.ram && capabilities.memoryMonitoringAvailable) readRam() else null,
            battery = if (flags.battery) readBattery() else null,
            thermal = if (flags.thermal && capabilities.thermalMonitoringAvailable) readThermal() else null,
            fps = if (flags.fps && capabilities.fpsMonitoringAvailable) unavailableFps() else null
        )

        if (flags.fps && capabilities.fpsMonitoringAvailable) {
            fpsMonitor.start(packageName)
            fpsCollectorJob = scope.launch {
                fpsMonitor.observeFps().collect { fps ->
                    _state.value = _state.value.copy(fps = fps)
                }
            }
        }

        monitorJob = scope.launch {
            var tick = 0
            while (isActive) {
                val current = _state.value
                var next = current
                if (flags.ram && capabilities.memoryMonitoringAvailable && tick % RAM_TICKS == 0) {
                    next = next.copy(ram = readRam())
                }
                if (flags.battery && tick % BATTERY_TICKS == 0) {
                    next = next.copy(battery = readBattery())
                }
                if (flags.thermal && capabilities.thermalMonitoringAvailable && tick % THERMAL_TICKS == 0) {
                    next = next.copy(thermal = readThermal())
                }
                _state.value = next
                tick++
                delay(BASE_TICK_MILLIS)
            }
        }
    }

    suspend fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        fpsCollectorJob?.cancel()
        fpsCollectorJob = null
        fpsMonitor.stop()
    }

    fun close() {
        scope.cancel()
    }

    private fun readRam(): RamInfo? {
        val manager = activityManager ?: return null
        val memory = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(memory)
        val total = memory.totalMem.takeIf { it > 0L } ?: return null
        val ratio = memory.availMem.toDouble() / total.toDouble()
        val level = when {
            memory.lowMemory || ratio < LOW_RAM_RATIO -> RamLevel.LOW
            ratio < NORMAL_RAM_RATIO -> RamLevel.NORMAL
            else -> RamLevel.EXCELLENT
        }
        return RamInfo(
            availableBytes = memory.availMem,
            totalBytes = total,
            lowMemory = memory.lowMemory,
            thresholdBytes = memory.threshold,
            level = level
        )
    }

    private fun readBattery(): BatteryInfo? {
        val intent = stickyBatteryIntent() ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val percent = ((level.toDouble() / scale.toDouble()) * 100.0)
            .roundToInt()
            .coerceIn(0, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val start = startBatteryPercent ?: percent.also { startBatteryPercent = it }
        return BatteryInfo(
            percent = percent,
            charging = charging,
            startPercent = start,
            consumedSinceStart = (start - percent).coerceAtLeast(0),
            sessionStartedAt = sessionStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
    }

    private fun readThermal(): ThermalInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val status = powerManager?.currentThermalStatus ?: return null
        val temperature = readBatteryTemperature()
        return ThermalInfo(
            temperatureCelsius = temperature,
            level = thermalLevel(status),
            source = if (temperature != null) {
                ThermalSource.BATTERY
            } else {
                ThermalSource.ANDROID_THERMAL_STATUS
            }
        )
    }

    private fun readBatteryTemperature(): Float? {
        val raw = stickyBatteryIntent()
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: return null
        if (raw == Int.MIN_VALUE) return null
        val value = raw / 10f
        return value.takeIf { it in MIN_PLAUSIBLE_BATTERY_C..MAX_PLAUSIBLE_BATTERY_C }
    }

    @Suppress("DEPRECATION")
    private fun stickyBatteryIntent(): Intent? =
        appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun thermalLevel(status: Int): ThermalLevel = when (status) {
        PowerManager.THERMAL_STATUS_NONE,
        PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.NORMAL
        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.WARM
        PowerManager.THERMAL_STATUS_SEVERE -> ThermalLevel.HOT
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.VERY_HOT
        else -> ThermalLevel.UNKNOWN
    }

    private companion object {
        const val BASE_TICK_MILLIS = 1_000L
        const val RAM_TICKS = 2
        const val BATTERY_TICKS = 5
        const val THERMAL_TICKS = 3
        const val LOW_RAM_RATIO = 0.15
        const val NORMAL_RAM_RATIO = 0.40
        const val MIN_PLAUSIBLE_BATTERY_C = -10f
        const val MAX_PLAUSIBLE_BATTERY_C = 80f
    }
}

private class ShellFpsMonitor(
    private val commandExecutor: ShizukuCommandExecutor
) : FpsMonitor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _fps = MutableStateFlow(unavailableFps())
    private var job: Job? = null
    private var previousLatestTimestamp: Long? = null

    override suspend fun start(packageName: String) {
        stop()
        previousLatestTimestamp = null
        _fps.value = unavailableFps()
        job = scope.launch {
            while (isActive) {
                val result = commandExecutor.execute(
                    arrayOf("/system/bin/dumpsys", "gfxinfo", packageName, "framestats"),
                    timeoutSeconds = FPS_COMMAND_TIMEOUT_SECONDS
                ).getOrNull()

                _fps.value = if (result?.isSuccess == true) {
                    calculateFpsFromFrameStats(
                        output = result.stdout,
                        previousLatestTimestamp = previousLatestTimestamp
                    ).also { sample ->
                        parseFrameTimestamps(result.stdout).lastOrNull()?.let {
                            previousLatestTimestamp = it
                        }
                    }
                } else {
                    unavailableFps()
                }
                delay(FPS_SAMPLE_MILLIS)
            }
        }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
        previousLatestTimestamp = null
        _fps.value = unavailableFps()
    }

    override fun observeFps(): Flow<FpsInfo> = _fps.asStateFlow()

    private companion object {
        const val FPS_SAMPLE_MILLIS = 1_000L
        const val FPS_COMMAND_TIMEOUT_SECONDS = 5L
    }
}

internal fun calculateFpsFromFrameStats(
    output: String,
    previousLatestTimestamp: Long?
): FpsInfo {
    val timestamps = parseFrameTimestamps(output)
    if (timestamps.size < MIN_VALID_FRAMES) return unavailableFps()
    val latest = timestamps.last()
    if (previousLatestTimestamp != null && latest <= previousLatestTimestamp) {
        return unavailableFps()
    }

    val windowStart = latest - FPS_WINDOW_NANOS
    val recent = timestamps.filter { it >= windowStart }
    if (recent.size < MIN_VALID_FRAMES) return unavailableFps()
    val duration = recent.last() - recent.first()
    if (duration < MIN_WINDOW_NANOS) return unavailableFps()

    val fps = ((recent.size - 1).toDouble() * NANOS_PER_SECOND / duration.toDouble()).toFloat()
    if (!fps.isFinite() || fps !in MIN_PLAUSIBLE_FPS..MAX_PLAUSIBLE_FPS) {
        return unavailableFps()
    }

    val confidence = if (recent.size >= HIGH_CONFIDENCE_FRAMES && duration >= HIGH_CONFIDENCE_WINDOW_NANOS) {
        FpsConfidence.HIGH
    } else {
        FpsConfidence.MEDIUM
    }
    return FpsInfo(
        fps = fps,
        confidence = confidence,
        source = FpsSource.GFXINFO_FRAMESTATS
    )
}

internal fun parseFrameTimestamps(output: String): List<Long> {
    if (!containsFrameStatsStructure(output)) return emptyList()
    val result = linkedSetOf<Long>()
    var intendedIndex = -1
    var inProfileData = false

    output.lineSequence().forEach { raw ->
        val line = raw.trim()
        when {
            line == "---PROFILEDATA---" -> {
                inProfileData = !inProfileData
                intendedIndex = -1
            }
            !inProfileData -> Unit
            line.startsWith("Flags,", ignoreCase = true) -> {
                val headers = line.split(',')
                intendedIndex = headers.indexOfFirst { it.equals("IntendedVsync", ignoreCase = true) }
            }
            intendedIndex >= 0 && line.isNotBlank() && line.firstOrNull()?.isDigit() == true -> {
                val values = line.split(',')
                values.getOrNull(intendedIndex)
                    ?.trim()
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.let(result::add)
            }
        }
    }
    return result.sorted()
}

internal fun unavailableFps(): FpsInfo = FpsInfo(
    fps = null,
    confidence = FpsConfidence.UNAVAILABLE,
    source = FpsSource.UNAVAILABLE
)

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val FPS_WINDOW_NANOS = 1_500_000_000L
private const val MIN_WINDOW_NANOS = 250_000_000L
private const val HIGH_CONFIDENCE_WINDOW_NANOS = 500_000_000L
private const val MIN_VALID_FRAMES = 3
private const val HIGH_CONFIDENCE_FRAMES = 20
private const val MIN_PLAUSIBLE_FPS = 1f
private const val MAX_PLAUSIBLE_FPS = 240f
