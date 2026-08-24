package com.zaid.densityreset.launcher

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zaid.densityreset.MainActivity
import com.zaid.densityreset.appearance.AppAppearanceMode
import com.zaid.densityreset.appearance.AppAppearancePreferences
import com.zaid.densityreset.appearance.AppAppearanceViewController
import com.zaid.densityreset.appearance.DensityResetAppearance
import com.zaid.densityreset.automation.GameAutomationPreference
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import com.zaid.densityreset.quicklaunch.QuickLaunchContract
import com.zaid.densityreset.recording.ScreenCaptureGrant
import com.zaid.densityreset.startup.StartupActivity
import com.zaid.densityreset.startup.StartupCoordinator
import kotlinx.coroutines.launch

class GameLauncherActivity : ComponentActivity() {

    private val viewModel: GameLauncherViewModel by viewModels()
    private var appearanceMode by mutableStateOf(AppAppearanceMode.LIQUID_GLASS)
    private var pendingGame: SupportedGame? = null
    private var pendingAutomation = GameAutomationPreference()
    private var overlayPrompted = false
    private var notificationPrompted = false
    private var dndPrompted = false
    private var writeSettingsPrompted = false
    private var audioPrompted = false
    private var storagePrompted = false
    private var screenCapturePrompted = false
    private var quickLaunchPackage: String? = null

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "El HUD flotante quedará oculto; la isla del panel y el Game Booster no necesitan este permiso.",
                Toast.LENGTH_LONG
            ).show()
        }
        continuePlayPreflight()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "El permiso de notificaciones es necesario durante la sesión de juego.",
                Toast.LENGTH_LONG
            ).show()
            clearPendingPlay()
        } else {
            continuePlayPreflight()
        }
    }

    private val dndPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        continuePlayPreflight()
    }

    private val writeSettingsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        continuePlayPreflight()
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "La grabación continuará como video sin audio interno.",
                Toast.LENGTH_LONG
            ).show()
        }
        continuePlayPreflight()
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "No se grabará la pantalla porque Android no permitió guardar el archivo.",
                Toast.LENGTH_LONG
            ).show()
            launchPendingGame(null)
        } else {
            continuePlayPreflight()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val grant = result.data?.takeIf { result.resultCode == Activity.RESULT_OK }?.let { data ->
            ScreenCaptureGrant(
                resultCode = result.resultCode,
                data = data,
                requestInternalAudio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
            )
        }
        if (grant == null) {
            Toast.makeText(
                this,
                "Android canceló la captura; el juego se abrirá sin grabar.",
                Toast.LENGTH_LONG
            ).show()
        }
        launchPendingGame(grant)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        appearanceMode = AppAppearancePreferences.get(this)
        AppAppearanceViewController.applyActivityTheme(this, appearanceMode)
        super.onCreate(savedInstanceState)
        restorePendingPlay(savedInstanceState)
        AppAppearanceViewController.applyWindow(this, appearanceMode)
        if (!StartupCoordinator.isReady()) {
            redirectToStartup()
            return
        }
        quickLaunchPackage = intent.getStringExtra(QuickLaunchContract.EXTRA_GAME_PACKAGE)

        setContent {
            DensityResetAppearance(appearanceMode) {
                val state = viewModel.uiState.collectAsStateWithLifecycle().value
                GameLauncherScreen(
                    state = state,
                    isPresetEnabled = viewModel::isPresetEnabled,
                    isBoosterModeEnabled = viewModel::isBoosterModeEnabled,
                    onSelectProfile = viewModel::selectProfile,
                    onSelectBoosterMode = viewModel::selectBoosterMode,
                    onSetOverlayEnabled = viewModel::setOverlayEnabled,
                    onSetOverlayOpacity = viewModel::setOverlayOpacity,
                    onSetPriorityDndEnabled = viewModel::setPriorityDndEnabled,
                    onSetBrightnessEnabled = viewModel::setBrightnessEnabled,
                    onSetBrightnessPercent = viewModel::setBrightnessPercent,
                    onSetRotationLockEnabled = viewModel::setRotationLockEnabled,
                    onSetMediaVolumeEnabled = viewModel::setMediaVolumeEnabled,
                    onSetMediaVolumePercent = viewModel::setMediaVolumePercent,
                    onSetScreenRecordingEnabled = viewModel::setScreenRecordingEnabled,
                    onToggleDefault = viewModel::toggleDefaultProfile,
                    onPlay = ::requestPlay,
                    onRestore = viewModel::restoreNow,
                    onRedetectDevice = viewModel::redetectDevice,
                    onAppearanceModeChange = ::changeAppearanceMode,
                    onOpenLegacyControls = {
                        startActivity(Intent(this, MainActivity::class.java))
                    }
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { message ->
                    Toast.makeText(this@GameLauncherActivity, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!StartupCoordinator.isReady()) {
            redirectToStartup()
            return
        }
        viewModel.refreshGames()
        if (pendingGame != null) {
            continuePlayPreflight()
        }
        quickLaunchPackage?.let { packageName ->
            quickLaunchPackage = null
            intent.removeExtra(QuickLaunchContract.EXTRA_GAME_PACKAGE)
            SupportedGame.fromPackageName(packageName)?.let(::requestPlay)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        quickLaunchPackage = intent.getStringExtra(QuickLaunchContract.EXTRA_GAME_PACKAGE)
    }

    private fun requestPlay(game: SupportedGame) {
        if (pendingGame != null) return
        pendingGame = game
        overlayPrompted = false
        notificationPrompted = false
        dndPrompted = false
        writeSettingsPrompted = false
        audioPrompted = false
        storagePrompted = false
        screenCapturePrompted = false
        lifecycleScope.launch {
            viewModel.prepareLaunch(game)
            viewModel.launchBlockReason(game)?.let { reason ->
                Toast.makeText(this@GameLauncherActivity, reason, Toast.LENGTH_LONG).show()
                clearPendingPlay()
                return@launch
            }
            pendingAutomation = viewModel.loadAutomationPreference(game)
            continuePlayPreflight()
        }
    }

    private fun continuePlayPreflight() {
        val game = pendingGame ?: return
        if (
            viewModel.requiresPerformanceOverlay(game) &&
            !Settings.canDrawOverlays(this) &&
            !overlayPrompted
        ) {
            overlayPrompted = true
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED &&
            !notificationPrompted
        ) {
            notificationPrompted = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        if (pendingAutomation.priorityDndEnabled && !dndPrompted) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            if (notificationManager?.isNotificationPolicyAccessGranted != true) {
                dndPrompted = true
                dndPermissionLauncher.launch(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                )
                return
            }
        }

        if (
            (pendingAutomation.brightnessEnabled || pendingAutomation.rotationLockEnabled) &&
            !Settings.System.canWrite(this) &&
            !writeSettingsPrompted
        ) {
            writeSettingsPrompted = true
            writeSettingsPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }

        if (!pendingAutomation.screenRecordingEnabled) {
            launchPendingGame(null)
            return
        }

        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED &&
            !storagePrompted
        ) {
            storagePrompted = true
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED &&
            !audioPrompted
        ) {
            audioPrompted = true
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        if (!screenCapturePrompted) {
            screenCapturePrompted = true
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            if (projectionManager == null) {
                Toast.makeText(
                    this,
                    "Este dispositivo no ofrece grabación de pantalla mediante Android.",
                    Toast.LENGTH_LONG
                ).show()
                launchPendingGame(null)
            } else {
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }
    }

    private fun changeAppearanceMode(mode: AppAppearanceMode) {
        if (mode == appearanceMode) return
        AppAppearancePreferences.set(this, mode)
        appearanceMode = mode
        AppAppearanceViewController.applyWindow(this, mode)
    }

    private fun launchPendingGame(screenCaptureGrant: ScreenCaptureGrant?) {
        val game = pendingGame ?: return
        clearPendingPlay()
        viewModel.play(game, screenCaptureGrant)
    }

    private fun clearPendingPlay() {
        pendingGame = null
        pendingAutomation = GameAutomationPreference()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingGame?.let { outState.putString(STATE_PENDING_GAME, it.packageName) }
        outState.putBoolean(STATE_PRIORITY_DND, pendingAutomation.priorityDndEnabled)
        outState.putBoolean(STATE_BRIGHTNESS_ENABLED, pendingAutomation.brightnessEnabled)
        outState.putInt(STATE_BRIGHTNESS_PERCENT, pendingAutomation.brightnessPercent)
        outState.putBoolean(STATE_ROTATION_LOCK, pendingAutomation.rotationLockEnabled)
        outState.putBoolean(STATE_VOLUME_ENABLED, pendingAutomation.mediaVolumeEnabled)
        outState.putInt(STATE_VOLUME_PERCENT, pendingAutomation.mediaVolumePercent)
        outState.putBoolean(STATE_RECORDING_ENABLED, pendingAutomation.screenRecordingEnabled)
        outState.putBoolean(STATE_OVERLAY_PROMPTED, overlayPrompted)
        outState.putBoolean(STATE_NOTIFICATION_PROMPTED, notificationPrompted)
        outState.putBoolean(STATE_DND_PROMPTED, dndPrompted)
        outState.putBoolean(STATE_WRITE_SETTINGS_PROMPTED, writeSettingsPrompted)
        outState.putBoolean(STATE_AUDIO_PROMPTED, audioPrompted)
        outState.putBoolean(STATE_STORAGE_PROMPTED, storagePrompted)
        outState.putBoolean(STATE_CAPTURE_PROMPTED, screenCapturePrompted)
    }

    private fun restorePendingPlay(state: Bundle?) {
        state ?: return
        pendingGame = SupportedGame.fromPackageName(state.getString(STATE_PENDING_GAME))
        pendingAutomation = GameAutomationPreference(
            priorityDndEnabled = state.getBoolean(STATE_PRIORITY_DND),
            brightnessEnabled = state.getBoolean(STATE_BRIGHTNESS_ENABLED),
            brightnessPercent = state.getInt(
                STATE_BRIGHTNESS_PERCENT,
                GameAutomationPreference.DEFAULT_PERCENT
            ),
            rotationLockEnabled = state.getBoolean(STATE_ROTATION_LOCK),
            mediaVolumeEnabled = state.getBoolean(STATE_VOLUME_ENABLED),
            mediaVolumePercent = state.getInt(
                STATE_VOLUME_PERCENT,
                GameAutomationPreference.DEFAULT_PERCENT
            ),
            screenRecordingEnabled = state.getBoolean(STATE_RECORDING_ENABLED)
        )
        overlayPrompted = state.getBoolean(STATE_OVERLAY_PROMPTED)
        notificationPrompted = state.getBoolean(STATE_NOTIFICATION_PROMPTED)
        dndPrompted = state.getBoolean(STATE_DND_PROMPTED)
        writeSettingsPrompted = state.getBoolean(STATE_WRITE_SETTINGS_PROMPTED)
        audioPrompted = state.getBoolean(STATE_AUDIO_PROMPTED)
        storagePrompted = state.getBoolean(STATE_STORAGE_PROMPTED)
        screenCapturePrompted = state.getBoolean(STATE_CAPTURE_PROMPTED)
    }

    private fun redirectToStartup() {
        startActivity(
            Intent(this, StartupActivity::class.java).apply {
                action = StartupActivity.ACTION_OPEN_GAME_LAUNCHER
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }

    private companion object {
        const val STATE_PENDING_GAME = "pending_game"
        const val STATE_PRIORITY_DND = "pending_priority_dnd"
        const val STATE_BRIGHTNESS_ENABLED = "pending_brightness_enabled"
        const val STATE_BRIGHTNESS_PERCENT = "pending_brightness_percent"
        const val STATE_ROTATION_LOCK = "pending_rotation_lock"
        const val STATE_VOLUME_ENABLED = "pending_volume_enabled"
        const val STATE_VOLUME_PERCENT = "pending_volume_percent"
        const val STATE_RECORDING_ENABLED = "pending_recording_enabled"
        const val STATE_OVERLAY_PROMPTED = "overlay_prompted"
        const val STATE_NOTIFICATION_PROMPTED = "notification_prompted"
        const val STATE_DND_PROMPTED = "dnd_prompted"
        const val STATE_WRITE_SETTINGS_PROMPTED = "write_settings_prompted"
        const val STATE_AUDIO_PROMPTED = "audio_prompted"
        const val STATE_STORAGE_PROMPTED = "storage_prompted"
        const val STATE_CAPTURE_PROMPTED = "capture_prompted"
    }
}
