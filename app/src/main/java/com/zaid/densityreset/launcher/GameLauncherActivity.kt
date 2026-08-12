package com.zaid.densityreset.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zaid.densityreset.MainActivity
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import com.zaid.densityreset.startup.StartupActivity
import com.zaid.densityreset.startup.StartupCoordinator
import kotlinx.coroutines.launch

class GameLauncherActivity : ComponentActivity() {

    private val viewModel: GameLauncherViewModel by viewModels()
    private var pendingGame: SupportedGame? = null

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val game = pendingGame
        pendingGame = null
        if (game != null && Settings.canDrawOverlays(this)) {
            requestNotificationThenPlay(game)
        } else if (game != null) {
            Toast.makeText(
                this,
                "Activa ‘Mostrar sobre otras apps’ para ver FPS, RAM, batería y temperatura dentro del juego.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val game = pendingGame
        pendingGame = null
        if (granted && game != null) {
            viewModel.play(game)
        } else if (!granted) {
            Toast.makeText(
                this,
                "El permiso de notificaciones es necesario durante la sesión de juego.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!StartupCoordinator.isReady()) {
            redirectToStartup()
            return
        }

        setContent {
            val state = viewModel.uiState.collectAsStateWithLifecycle().value
            GameLauncherScreen(
                state = state,
                isPresetEnabled = viewModel::isPresetEnabled,
                isBoosterModeEnabled = viewModel::isBoosterModeEnabled,
                onSelectProfile = viewModel::selectProfile,
                onSelectBoosterMode = viewModel::selectBoosterMode,
                onToggleDefault = viewModel::toggleDefaultProfile,
                onPlay = ::requestPlay,
                onRestore = viewModel::restoreNow,
                onRedetectDevice = viewModel::redetectDevice,
                onOpenLegacyControls = {
                    startActivity(Intent(this, MainActivity::class.java))
                }
            )
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
    }

    private fun requestPlay(game: SupportedGame) {
        if (viewModel.requiresPerformanceOverlay() && !Settings.canDrawOverlays(this)) {
            pendingGame = game
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        requestNotificationThenPlay(game)
    }

    private fun requestNotificationThenPlay(game: SupportedGame) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingGame = game
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        viewModel.play(game)
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
}
