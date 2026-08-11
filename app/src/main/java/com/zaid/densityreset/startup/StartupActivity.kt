package com.zaid.densityreset.startup

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zaid.densityreset.MainActivity
import com.zaid.densityreset.launcher.GameLauncherActivity
import com.zaid.densityreset.license.ui.LicenseGateActivity
import com.zaid.densityreset.update.UpdateScreen
import com.zaid.densityreset.update.UpdateViewModel
import kotlinx.coroutines.launch

class StartupActivity : ComponentActivity() {

    private val updateViewModel: UpdateViewModel by viewModels()
    private var desiredDestination = StartupDestination.GAME_LAUNCHER
    private var destinationOpened = false
    private var licenseOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resolveDestination(intent)

        setContent {
            val gate = StartupCoordinator.gate.collectAsStateWithLifecycle().value
            if (gate is StartupGate.UpdateRequired) {
                LaunchedEffect(gate.release.releaseId, gate.release.versionCode) {
                    updateViewModel.setRelease(gate.release)
                }
                val updateState = updateViewModel.uiState.collectAsStateWithLifecycle().value
                UpdateScreen(
                    state = updateState,
                    onDownload = updateViewModel::download,
                    onInstall = { updateViewModel.install(this) },
                    onRetryCheck = ::retryStartup
                )
            } else {
                StartupScreen(gate = gate, onRetry = ::retryStartup)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StartupCoordinator.gate.collect(::handleGate)
            }
        }

        retryStartup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resolveDestination(intent)
        destinationOpened = false
        if (StartupCoordinator.isReady()) {
            openDestination()
        } else {
            retryStartup()
        }
    }

    private fun retryStartup() {
        destinationOpened = false
        lifecycleScope.launch {
            StartupCoordinator.resetForRecheck()
            StartupCoordinator.refresh(forceNetwork = true)
        }
    }

    private fun handleGate(gate: StartupGate) {
        when (gate) {
            StartupGate.Ready -> openDestination()
            StartupGate.LicenseRequired -> openLicenseGate()
            else -> Unit
        }
    }

    private fun openDestination() {
        if (destinationOpened || isFinishing) return
        destinationOpened = true
        val target = when (desiredDestination) {
            StartupDestination.GAME_LAUNCHER -> GameLauncherActivity::class.java
            StartupDestination.LEGACY_CONTROLS -> MainActivity::class.java
        }
        startActivity(
            Intent(this, target).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }

    private fun openLicenseGate() {
        if (licenseOpened || isFinishing) return
        licenseOpened = true
        startActivity(
            Intent(this, LicenseGateActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }

    private fun resolveDestination(intent: Intent?) {
        desiredDestination = when (intent?.action) {
            ACTION_OPEN_LEGACY_CONTROLS -> StartupDestination.LEGACY_CONTROLS
            else -> StartupDestination.GAME_LAUNCHER
        }
    }

    companion object {
        const val ACTION_OPEN_GAME_LAUNCHER =
            "com.zaidnavarro.ds.action.OPEN_GAME_LAUNCHER"
        const val ACTION_OPEN_LEGACY_CONTROLS =
            "com.zaidnavarro.ds.action.OPEN_LEGACY_CONTROLS"
        const val ACTION_OPEN_UPDATE =
            "com.zaidnavarro.ds.action.OPEN_UPDATE"
    }
}
