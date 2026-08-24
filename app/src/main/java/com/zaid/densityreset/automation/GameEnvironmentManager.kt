package com.zaid.densityreset.automation

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

class GameEnvironmentManager(context: Context) {
    private val appContext = context.applicationContext
    private val automationRepository = GameAutomationRepositoryImpl(appContext)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val snapshotStore = GameEnvironmentSnapshotStore(appContext)

    suspend fun apply(game: SupportedGame): List<GameEnvironmentAction> {
        val preference = automationRepository.read(game)
        val wantsSystemChange = preference.priorityDndEnabled ||
            preference.brightnessEnabled ||
            preference.rotationLockEnabled ||
            preference.mediaVolumeEnabled
        if (!wantsSystemChange) {
            snapshotStore.clear()
            return emptyList()
        }

        val canWriteSettings = Settings.System.canWrite(appContext)
        val canWriteDnd = notificationManager?.isNotificationPolicyAccessGranted == true
        val resolver = appContext.contentResolver
        var snapshot = GameEnvironmentSnapshot(
            packageName = game.packageName,
            capturedAt = System.currentTimeMillis(),
            previousInterruptionFilter = notificationManager
                ?.currentInterruptionFilter
                ?.takeIf { preference.priorityDndEnabled && canWriteDnd },
            previousBrightnessMode = Settings.System.getInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            ).takeIf { preference.brightnessEnabled && canWriteSettings },
            previousBrightness = Settings.System.getInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS,
                DEFAULT_SYSTEM_BRIGHTNESS
            ).takeIf { preference.brightnessEnabled && canWriteSettings },
            previousAccelerometerRotation = Settings.System.getInt(
                resolver,
                Settings.System.ACCELEROMETER_ROTATION,
                1
            ).takeIf { preference.rotationLockEnabled && canWriteSettings },
            previousUserRotation = Settings.System.getInt(
                resolver,
                Settings.System.USER_ROTATION,
                0
            ).takeIf { preference.rotationLockEnabled && canWriteSettings },
            previousMediaVolume = audioManager
                ?.getStreamVolume(AudioManager.STREAM_MUSIC)
                ?.takeIf { preference.mediaVolumeEnabled }
        )
        snapshotStore.save(snapshot)

        val actions = mutableListOf<GameEnvironmentAction>()
        if (preference.priorityDndEnabled) {
            val previous = snapshot.previousInterruptionFilter
            if (!canWriteDnd || previous == null ||
                previous == NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            ) {
                actions += GameEnvironmentAction(
                    "No molestar",
                    "Concede acceso a No molestar para usar Prioridad.",
                    false
                )
            } else {
                snapshot = snapshot.copy(dndApplied = true)
                snapshotStore.save(snapshot)
                val applied = runCatching {
                    notificationManager?.setInterruptionFilter(
                        NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    )
                }.isSuccess
                actions += GameEnvironmentAction(
                    "No molestar",
                    if (applied) "Prioridad activa durante la sesión" else "Android rechazó el cambio",
                    applied
                )
            }
        }

        if (preference.brightnessEnabled) {
            if (!canWriteSettings || snapshot.previousBrightness == null ||
                snapshot.previousBrightnessMode == null
            ) {
                actions += GameEnvironmentAction(
                    "Brillo",
                    "Concede Modificar ajustes del sistema.",
                    false
                )
            } else {
                // Persist the restoration intent before the first system write.
                // If the process dies between the two brightness writes, recovery
                // still restores both values from this snapshot.
                snapshot = snapshot.copy(brightnessApplied = true)
                snapshotStore.save(snapshot)
                val modeWritten = Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                val valueWritten = Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    percentToSystemBrightness(preference.normalizedBrightnessPercent)
                )
                actions += GameEnvironmentAction(
                    "Brillo",
                    if (modeWritten && valueWritten) {
                        "${preference.normalizedBrightnessPercent}% durante la sesión"
                    } else {
                        "Android no confirmó todos los valores"
                    },
                    modeWritten && valueWritten
                )
            }
        }

        if (preference.rotationLockEnabled) {
            if (!canWriteSettings || snapshot.previousAccelerometerRotation == null ||
                snapshot.previousUserRotation == null
            ) {
                actions += GameEnvironmentAction(
                    "Rotación",
                    "Concede Modificar ajustes del sistema.",
                    false
                )
            } else {
                snapshot = snapshot.copy(rotationApplied = true)
                snapshotStore.save(snapshot)
                val applied = Settings.System.putInt(
                    resolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    0
                )
                actions += GameEnvironmentAction(
                    "Rotación",
                    if (applied) "Bloqueada durante la sesión" else "Android rechazó el cambio",
                    applied
                )
            }
        }

        if (preference.mediaVolumeEnabled) {
            val manager = audioManager
            val previous = snapshot.previousMediaVolume
            if (manager == null || previous == null) {
                actions += GameEnvironmentAction("Volumen", "No disponible", false)
            } else {
                snapshot = snapshot.copy(mediaVolumeApplied = true)
                snapshotStore.save(snapshot)
                val applied = runCatching {
                    manager.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        percentToStreamVolume(
                            preference.normalizedMediaVolumePercent,
                            manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        ),
                        0
                    )
                }.isSuccess
                actions += GameEnvironmentAction(
                    "Volumen multimedia",
                    if (applied) {
                        "${preference.normalizedMediaVolumePercent}% durante la sesión"
                    } else {
                        "Android rechazó el cambio"
                    },
                    applied
                )
            }
        }

        if (!snapshot.hasAppliedChange) snapshotStore.clear()
        return actions
    }

    suspend fun restore(): Result<Unit> {
        val snapshot = snapshotStore.read() ?: return Result.success(Unit)
        val failures = mutableListOf<String>()
        val resolver = appContext.contentResolver

        if (snapshot.dndApplied) {
            val previous = snapshot.previousInterruptionFilter
            val restored = previous != null &&
                notificationManager?.isNotificationPolicyAccessGranted == true &&
                runCatching { notificationManager?.setInterruptionFilter(previous) }.isSuccess
            if (!restored) failures += "No se pudo restaurar No molestar."
        }

        if (snapshot.brightnessApplied) {
            val previousValue = snapshot.previousBrightness
            val previousMode = snapshot.previousBrightnessMode
            val canRestore = Settings.System.canWrite(appContext) &&
                previousValue != null && previousMode != null
            val valueRestored = canRestore && Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    requireNotNull(previousValue)
                )
            val modeRestored = canRestore && Settings.System.putInt(
                    resolver,
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    requireNotNull(previousMode)
                )
            val restored = valueRestored && modeRestored
            if (!restored) failures += "No se pudo restaurar el brillo."
        }

        if (snapshot.rotationApplied) {
            val previousAccelerometer = snapshot.previousAccelerometerRotation
            val previousUserRotation = snapshot.previousUserRotation
            val canRestore = Settings.System.canWrite(appContext) &&
                previousAccelerometer != null && previousUserRotation != null
            val userRotationRestored = canRestore && Settings.System.putInt(
                    resolver,
                    Settings.System.USER_ROTATION,
                    requireNotNull(previousUserRotation)
                )
            val accelerometerRestored = canRestore && Settings.System.putInt(
                    resolver,
                    Settings.System.ACCELEROMETER_ROTATION,
                    requireNotNull(previousAccelerometer)
                )
            val restored = userRotationRestored && accelerometerRestored
            if (!restored) failures += "No se pudo restaurar la rotación."
        }

        if (snapshot.mediaVolumeApplied) {
            val restored = snapshot.previousMediaVolume?.let { previous ->
                runCatching {
                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, previous, 0)
                }.isSuccess
            } == true
            if (!restored) failures += "No se pudo restaurar el volumen multimedia."
        }

        return if (failures.isEmpty()) {
            snapshotStore.clear()
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException(failures.joinToString(" ")))
        }
    }

    suspend fun hasSnapshot(): Boolean = snapshotStore.read() != null
}

internal fun percentToSystemBrightness(percent: Int): Int =
    ((percent.coerceIn(0, 100) / 100f) * (MAX_SYSTEM_BRIGHTNESS - 1) + 1)
        .roundToInt()
        .coerceIn(1, MAX_SYSTEM_BRIGHTNESS)

internal fun percentToStreamVolume(percent: Int, maxVolume: Int): Int {
    if (maxVolume <= 0) return 0
    return ((percent.coerceIn(0, 100) / 100f) * maxVolume)
        .roundToInt()
        .coerceIn(0, maxVolume)
}

private data class GameEnvironmentSnapshot(
    val packageName: String,
    val capturedAt: Long,
    val previousInterruptionFilter: Int? = null,
    val previousBrightnessMode: Int? = null,
    val previousBrightness: Int? = null,
    val previousAccelerometerRotation: Int? = null,
    val previousUserRotation: Int? = null,
    val previousMediaVolume: Int? = null,
    val dndApplied: Boolean = false,
    val brightnessApplied: Boolean = false,
    val rotationApplied: Boolean = false,
    val mediaVolumeApplied: Boolean = false
) {
    val hasAppliedChange: Boolean
        get() = dndApplied || brightnessApplied || rotationApplied || mediaVolumeApplied
}

private class GameEnvironmentSnapshotStore(private val context: Context) {
    suspend fun save(snapshot: GameEnvironmentSnapshot) {
        context.gameEnvironmentSnapshotDataStore.edit { preferences ->
            preferences[Keys.packageName] = snapshot.packageName
            preferences[Keys.capturedAt] = snapshot.capturedAt
            preferences.putNullable(Keys.previousInterruptionFilter, snapshot.previousInterruptionFilter)
            preferences.putNullable(Keys.previousBrightnessMode, snapshot.previousBrightnessMode)
            preferences.putNullable(Keys.previousBrightness, snapshot.previousBrightness)
            preferences.putNullable(
                Keys.previousAccelerometerRotation,
                snapshot.previousAccelerometerRotation
            )
            preferences.putNullable(Keys.previousUserRotation, snapshot.previousUserRotation)
            preferences.putNullable(Keys.previousMediaVolume, snapshot.previousMediaVolume)
            preferences[Keys.dndApplied] = snapshot.dndApplied
            preferences[Keys.brightnessApplied] = snapshot.brightnessApplied
            preferences[Keys.rotationApplied] = snapshot.rotationApplied
            preferences[Keys.mediaVolumeApplied] = snapshot.mediaVolumeApplied
        }
    }

    suspend fun read(): GameEnvironmentSnapshot? {
        val preferences = context.gameEnvironmentSnapshotDataStore.data.first()
        val packageName = preferences[Keys.packageName] ?: return null
        return GameEnvironmentSnapshot(
            packageName = packageName,
            capturedAt = preferences[Keys.capturedAt] ?: 0L,
            previousInterruptionFilter = preferences[Keys.previousInterruptionFilter],
            previousBrightnessMode = preferences[Keys.previousBrightnessMode],
            previousBrightness = preferences[Keys.previousBrightness],
            previousAccelerometerRotation = preferences[Keys.previousAccelerometerRotation],
            previousUserRotation = preferences[Keys.previousUserRotation],
            previousMediaVolume = preferences[Keys.previousMediaVolume],
            dndApplied = preferences[Keys.dndApplied] ?: false,
            brightnessApplied = preferences[Keys.brightnessApplied] ?: false,
            rotationApplied = preferences[Keys.rotationApplied] ?: false,
            mediaVolumeApplied = preferences[Keys.mediaVolumeApplied] ?: false
        )
    }

    suspend fun clear() {
        context.gameEnvironmentSnapshotDataStore.edit { it.clear() }
    }

    private object Keys {
        val packageName = stringPreferencesKey("package_name")
        val capturedAt = longPreferencesKey("captured_at")
        val previousInterruptionFilter = intPreferencesKey("previous_interruption_filter")
        val previousBrightnessMode = intPreferencesKey("previous_brightness_mode")
        val previousBrightness = intPreferencesKey("previous_brightness")
        val previousAccelerometerRotation = intPreferencesKey("previous_accelerometer_rotation")
        val previousUserRotation = intPreferencesKey("previous_user_rotation")
        val previousMediaVolume = intPreferencesKey("previous_media_volume")
        val dndApplied = booleanPreferencesKey("dnd_applied")
        val brightnessApplied = booleanPreferencesKey("brightness_applied")
        val rotationApplied = booleanPreferencesKey("rotation_applied")
        val mediaVolumeApplied = booleanPreferencesKey("media_volume_applied")
    }
}

private val Context.gameEnvironmentSnapshotDataStore by preferencesDataStore(
    name = "game_environment_snapshot"
)

private fun MutablePreferences.putNullable(
    key: Preferences.Key<Int>,
    value: Int?
) {
    if (value == null) remove(key) else this[key] = value
}

private const val DEFAULT_SYSTEM_BRIGHTNESS = 128
private const val MAX_SYSTEM_BRIGHTNESS = 255
