package com.zaid.densityreset.quicktile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.zaid.densityreset.density.ShizukuDensityController
import com.zaid.densityreset.gameprofile.data.GameSessionRepositoryImpl
import com.zaid.densityreset.gameprofile.service.DpiGameSessionService
import com.zaid.densityreset.remoteconfig.RemoteConfigManager
import com.zaid.densityreset.startup.StartupActivity
import com.zaid.densityreset.startup.StartupCoordinator
import com.zaid.densityreset.startup.StartupGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DensityQuickTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionRepository by lazy { GameSessionRepositoryImpl(applicationContext) }
    private val densityController by lazy { ShizukuDensityController(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { renderTile() }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val config = RemoteConfigManager.currentConfig()
            if (!config.quickTileEnabled) {
                renderUnavailable("Deshabilitado")
                return@launch
            }

            when (StartupCoordinator.currentGate()) {
                is StartupGate.UpdateRequired -> {
                    openStartup(StartupActivity.ACTION_OPEN_UPDATE)
                    return@launch
                }
                is StartupGate.Maintenance,
                StartupGate.LicenseRequired,
                is StartupGate.Error,
                StartupGate.Checking -> {
                    openStartup(StartupActivity.ACTION_OPEN_GAME_LAUNCHER)
                    return@launch
                }
                StartupGate.Ready -> Unit
            }

            val session = sessionRepository.read()
            if (session.sessionActive) {
                DpiGameSessionService.restoreNow(applicationContext)
                renderTile()
                return@launch
            }

            val density = densityController.getSystemState().getOrNull()
            if (density?.hasOverride == true) {
                val reset = densityController.resetDensity()
                if (reset.isSuccess) {
                    DensityTileNotifier.requestRefresh(applicationContext)
                }
                renderTile()
            } else {
                openStartup(StartupActivity.ACTION_OPEN_GAME_LAUNCHER)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun renderTile() {
        val tile = qsTile ?: return
        val config = RemoteConfigManager.currentConfig()
        tile.label = "Density Reset"

        if (!config.quickTileEnabled) {
            renderUnavailable("Deshabilitado")
            return
        }

        when (StartupCoordinator.currentGate()) {
            is StartupGate.UpdateRequired -> {
                tile.state = Tile.STATE_INACTIVE
                setSubtitle(tile, "Actualización requerida")
                tile.updateTile()
                return
            }
            is StartupGate.Maintenance -> {
                tile.state = Tile.STATE_INACTIVE
                setSubtitle(tile, "Mantenimiento")
                tile.updateTile()
                return
            }
            StartupGate.LicenseRequired -> {
                tile.state = Tile.STATE_INACTIVE
                setSubtitle(tile, "Licencia requerida")
                tile.updateTile()
                return
            }
            is StartupGate.Error -> {
                tile.state = Tile.STATE_INACTIVE
                setSubtitle(tile, "Requiere atención")
                tile.updateTile()
                return
            }
            else -> Unit
        }

        val session = sessionRepository.read()
        if (session.sessionActive) {
            tile.state = Tile.STATE_ACTIVE
            val shortName = when (session.selectedGame?.name) {
                "FREE_FIRE" -> "FF"
                "FREE_FIRE_MAX" -> "FF MAX"
                else -> "Sesión"
            }
            val density = session.targetDensity?.let { "$it DPI" }.orEmpty()
            setSubtitle(tile, listOf(shortName, density).filter { it.isNotBlank() }.joinToString(" · "))
            tile.updateTile()
            return
        }

        val density = densityController.getSystemState().getOrNull()
        if (density == null) {
            tile.state = Tile.STATE_INACTIVE
            setSubtitle(tile, "Abrir Game Launcher")
        } else if (density.hasOverride) {
            tile.state = Tile.STATE_ACTIVE
            setSubtitle(tile, "${density.currentDensity} DPI")
        } else {
            tile.state = Tile.STATE_INACTIVE
            setSubtitle(tile, "DPI original")
        }
        tile.updateTile()
    }

    private fun renderUnavailable(subtitle: String) {
        val tile = qsTile ?: return
        tile.label = "Density Reset"
        tile.state = Tile.STATE_UNAVAILABLE
        setSubtitle(tile, subtitle)
        tile.updateTile()
    }

    private fun setSubtitle(tile: Tile, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = text
        } else {
            tile.label = if (text.isBlank()) "Density Reset" else "Density Reset · $text"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tile.stateDescription = text
        }
    }

    @Suppress("DEPRECATION")
    private fun openStartup(action: String) {
        val intent = Intent(this, StartupActivity::class.java).apply {
            this.action = action
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                this,
                REQUEST_OPEN_APP,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private companion object {
        const val REQUEST_OPEN_APP = 6701
    }
}
