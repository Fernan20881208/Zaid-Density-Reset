package com.zaid.densityreset.launcher

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
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
                "El permiso de notificaciones es necesario durante la sesión de DPI.",
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
            val state = androidx.lifecycle.compose.collectAsStateWithLifecycle(viewModel.uiState).value
            GameLauncherScreen(
                state = state,
                isPresetEnabled = viewModel::isPresetEnabled,
                onSelectProfile = viewModel::selectProfile,
                onToggleDefault = viewModel::toggleDefaultProfile,
                onPlay = ::requestPlay,
                onRestore = viewModel::restoreNow,
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
