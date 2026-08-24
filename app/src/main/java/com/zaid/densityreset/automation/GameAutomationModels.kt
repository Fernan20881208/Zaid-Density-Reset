package com.zaid.densityreset.automation

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

data class GameAutomationPreference(
    val priorityDndEnabled: Boolean = false,
    val brightnessEnabled: Boolean = false,
    val brightnessPercent: Int = DEFAULT_PERCENT,
    val rotationLockEnabled: Boolean = false,
    val mediaVolumeEnabled: Boolean = false,
    val mediaVolumePercent: Int = DEFAULT_PERCENT,
    val screenRecordingEnabled: Boolean = false
) {
    val normalizedBrightnessPercent: Int
        get() = brightnessPercent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    val normalizedMediaVolumePercent: Int
        get() = mediaVolumePercent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    companion object {
        const val DEFAULT_PERCENT = 70
        const val MIN_PERCENT = 10
        const val MAX_PERCENT = 100
    }
}

data class GameSystemAccessState(
    val notificationPermissionGranted: Boolean,
    val notificationPolicyAccessGranted: Boolean,
    val writeSystemSettingsGranted: Boolean,
    val overlayPermissionGranted: Boolean,
    val recordAudioPermissionGranted: Boolean,
    val mediaProjectionAvailable: Boolean,
    val internalAudioCaptureAvailable: Boolean,
    val quickSettingsTilesAvailable: Boolean,
    val dynamicShortcutsAvailable: Boolean
)

object GameSystemAccessInspector {
    fun inspect(context: Context): GameSystemAccessState {
        val appContext = context.applicationContext
        val notificationManager = appContext.getSystemService(NotificationManager::class.java)
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        val recordAudioGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        return GameSystemAccessState(
            notificationPermissionGranted = notificationsGranted,
            notificationPolicyAccessGranted =
                notificationManager?.isNotificationPolicyAccessGranted == true,
            writeSystemSettingsGranted = Settings.System.canWrite(appContext),
            overlayPermissionGranted = Settings.canDrawOverlays(appContext),
            recordAudioPermissionGranted = recordAudioGranted,
            mediaProjectionAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP,
            internalAudioCaptureAvailable =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && recordAudioGranted,
            quickSettingsTilesAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N,
            dynamicShortcutsAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1
        )
    }
}

data class GameEnvironmentAction(
    val name: String,
    val detail: String,
    val applied: Boolean
)
