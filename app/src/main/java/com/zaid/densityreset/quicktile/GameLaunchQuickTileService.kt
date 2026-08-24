package com.zaid.densityreset.quicktile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.zaid.densityreset.gameprofile.data.GameSessionRepositoryImpl
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import com.zaid.densityreset.launcher.GameLauncherRepositoryImpl
import com.zaid.densityreset.quicklaunch.QuickLaunchContract
import com.zaid.densityreset.remoteconfig.RemoteConfigManager
import com.zaid.densityreset.startup.StartupActivity
import com.zaid.densityreset.startup.StartupCoordinator
import com.zaid.densityreset.startup.StartupGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

abstract class GameLaunchQuickTileService : TileService() {
    protected abstract val game: SupportedGame
    protected abstract val tileName: String

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sessionRepository by lazy { GameSessionRepositoryImpl(applicationContext) }
    private val launcherRepository by lazy { GameLauncherRepositoryImpl(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch { renderTile() }
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val config = RemoteConfigManager.currentConfig()
            if (!config.quickTileEnabled || !isGameEnabled(config)) {
                renderUnavailable("Deshabilitado")
                return@launch
            }
            val open = Runnable { openStartup() }
            if (isLocked) unlockAndRun(open) else open.run()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun renderTile() {
        val tile = qsTile ?: return
        tile.label = tileName
        val config = RemoteConfigManager.currentConfig()
        if (!config.quickTileEnabled || !isGameEnabled(config)) {
            renderUnavailable("Deshabilitado")
            return
        }

        when (StartupCoordinator.currentGate()) {
            is StartupGate.UpdateRequired -> return renderInactive(tile, "Actualiza la app")
            is StartupGate.Maintenance -> return renderInactive(tile, "Mantenimiento")
            StartupGate.LicenseRequired -> return renderInactive(tile, "Licencia requerida")
            is StartupGate.Error -> return renderInactive(tile, "Requiere atención")
            else -> Unit
        }

        val installed = launcherRepository.installedGame(game).installed
        if (!installed) {
            renderUnavailable("No instalado")
            return
        }

        val session = sessionRepository.read()
        if (session.sessionActive && session.selectedGame == game) {
            tile.state = Tile.STATE_ACTIVE
            setSubtitle(tile, session.targetDensity?.let { "Activo · $it DPI" } ?: "Sesión activa")
        } else if (session.sessionActive) {
            tile.state = Tile.STATE_INACTIVE
            setSubtitle(tile, "Otra sesión activa")
        } else {
            val preference = launcherRepository.observePreferences().first()[game]
            val preset = preference?.defaultProfile ?: preference?.lastProfile
            tile.state = Tile.STATE_INACTIVE
            setSubtitle(tile, preset?.let { "Jugar · ${it.density} DPI" } ?: "Elegir perfil")
        }
        tile.updateTile()
    }

    private fun isGameEnabled(config: com.zaid.densityreset.remoteconfig.RemoteAppConfig): Boolean =
        when (game) {
            SupportedGame.FREE_FIRE -> config.freeFireEnabled
            SupportedGame.FREE_FIRE_MAX -> config.freeFireMaxEnabled
        }

    private fun renderInactive(tile: Tile, subtitle: String) {
        tile.state = Tile.STATE_INACTIVE
        setSubtitle(tile, subtitle)
        tile.updateTile()
    }

    private fun renderUnavailable(subtitle: String) {
        val tile = qsTile ?: return
        tile.label = tileName
        tile.state = Tile.STATE_UNAVAILABLE
        setSubtitle(tile, subtitle)
        tile.updateTile()
    }

    private fun setSubtitle(tile: Tile, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = text
        } else {
            tile.label = "$tileName · $text"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) tile.stateDescription = text
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openStartup() {
        val intent = Intent(this, StartupActivity::class.java).apply {
            action = QuickLaunchContract.ACTION_LAUNCH_GAME
            putExtra(QuickLaunchContract.EXTRA_GAME_PACKAGE, game.packageName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    requestCode(game),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun requestCode(game: SupportedGame): Int = when (game) {
        SupportedGame.FREE_FIRE -> 6811
        SupportedGame.FREE_FIRE_MAX -> 6812
    }
}

class FreeFireQuickTileService : GameLaunchQuickTileService() {
    override val game: SupportedGame = SupportedGame.FREE_FIRE
    override val tileName: String = "FF"
}

class FreeFireMaxQuickTileService : GameLaunchQuickTileService() {
    override val game: SupportedGame = SupportedGame.FREE_FIRE_MAX
    override val tileName: String = "FFM"
}
