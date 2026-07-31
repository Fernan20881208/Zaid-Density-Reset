package com.zaid.densityreset.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.zaid.densityreset.density.DensityPreferencesRepository
import com.zaid.densityreset.density.ShizukuDensityController
import com.zaid.densityreset.gameprofile.data.GameSessionRepositoryImpl
import com.zaid.densityreset.gameprofile.service.DpiGameSessionService
import com.zaid.densityreset.shizuku.ShizukuManager
import com.zaid.densityreset.util.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class VolumeShortcutAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val densityController by lazy {
        ShizukuDensityController(applicationContext)
    }
    private val densityPreferences by lazy {
        DensityPreferencesRepository(applicationContext)
    }
    private val gameSessionRepository by lazy {
        GameSessionRepositoryImpl(applicationContext)
    }

    private var volumeUpPressed = false
    private var volumeDownPressed = false
    private var timerScheduled = false
    private var triggeredForCurrentCycle = false
    private var gestureCycleActive = false

    private val triggerRunnable = Runnable {
        timerScheduled = false
        if (
            volumeUpPressed &&
            volumeDownPressed &&
            !triggeredForCurrentCycle
        ) {
            triggeredForCurrentCycle = true
            executeDensityReset()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        resetGestureState()
        ShizukuManager.refresh()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val isVolumeUp = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        val isVolumeDown = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (!isVolumeUp && !isVolumeDown) return false

        val wasGestureCycleActive = gestureCycleActive

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    if (isVolumeUp) volumeUpPressed = true
                    if (isVolumeDown) volumeDownPressed = true

                    if (
                        volumeUpPressed &&
                        volumeDownPressed &&
                        !gestureCycleActive &&
                        !triggeredForCurrentCycle
                    ) {
                        gestureCycleActive = true
                        scheduleTrigger()
                    }
                }
            }

            KeyEvent.ACTION_UP -> {
                if (isVolumeUp) volumeUpPressed = false
                if (isVolumeDown) volumeDownPressed = false

                if (!volumeUpPressed || !volumeDownPressed) {
                    cancelTrigger()
                }

                if (!volumeUpPressed && !volumeDownPressed) {
                    triggeredForCurrentCycle = false
                    gestureCycleActive = false
                }
            }
        }

        val blockChanges = AppPreferences.shouldBlockVolumeChanges(this)
        return blockChanges && (gestureCycleActive || wasGestureCycleActive)
    }

    private fun scheduleTrigger() {
        if (timerScheduled || triggeredForCurrentCycle) return
        timerScheduled = true
        mainHandler.postDelayed(triggerRunnable, GESTURE_DURATION_MILLIS)
    }

    private fun cancelTrigger() {
        if (!timerScheduled) return
        mainHandler.removeCallbacks(triggerRunnable)
        timerScheduled = false
    }

    private fun executeDensityReset() {
        serviceScope.launch {
            val activeSession = gameSessionRepository.read().sessionActive
            if (activeSession) {
                DpiGameSessionService.restoreNow(
                    context = this@VolumeShortcutAccessibilityService,
                    source = DpiGameSessionService.RESTORE_SOURCE_VOLUME
                )
                vibrateBriefly()
                Toast.makeText(
                    this@VolumeShortcutAccessibilityService,
                    "Restaurando el DPI anterior…",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val result = densityController.resetDensity()
            val message = result.fold(
                onSuccess = {
                    densityController.getSystemState().getOrNull()?.let { state ->
                        densityPreferences.saveReset(state)
                    }
                    "DPI restablecido correctamente."
                },
                onFailure = { error ->
                    error.message ?: "No fue posible acceder a WindowManager."
                }
            )

            Toast.makeText(
                this@VolumeShortcutAccessibilityService,
                message,
                Toast.LENGTH_LONG
            ).show()

            if (
                result.isSuccess &&
                AppPreferences.shouldVibrateAfterSuccess(
                    this@VolumeShortcutAccessibilityService
                )
            ) {
                vibrateBriefly()
            }
        }
    }

    private fun vibrateBriefly() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (vibrator.hasVibrator()) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    SUCCESS_VIBRATION_MILLIS,
                    VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        }
    }

    private fun resetGestureState() {
        cancelTrigger()
        volumeUpPressed = false
        volumeDownPressed = false
        triggeredForCurrentCycle = false
        gestureCycleActive = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
    }

    override fun onInterrupt() {
        resetGestureState()
    }

    override fun onDestroy() {
        resetGestureState()
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val GESTURE_DURATION_MILLIS = 2_000L
        const val SUCCESS_VIBRATION_MILLIS = 60L
    }
}
