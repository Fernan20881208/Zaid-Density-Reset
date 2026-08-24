package com.zaid.densityreset.quicklaunch

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.zaid.densityreset.R
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import com.zaid.densityreset.startup.StartupActivity

object GameShortcutPublisher {
    fun publish(context: Context) {
        val appContext = context.applicationContext
        val shortcuts = SupportedGame.entries.mapIndexed { rank, game ->
            ShortcutInfoCompat.Builder(appContext, shortcutId(game))
                .setShortLabel(shortLabel(game))
                .setLongLabel("Jugar ${game.displayName} con el perfil predeterminado")
                .setIcon(IconCompat.createWithResource(appContext, R.drawable.ic_notification_density))
                .setRank(rank)
                .setIntent(
                    Intent(appContext, StartupActivity::class.java).apply {
                        action = QuickLaunchContract.ACTION_LAUNCH_GAME
                        putExtra(QuickLaunchContract.EXTRA_GAME_PACKAGE, game.packageName)
                    }
                )
                .build()
        }
        runCatching { ShortcutManagerCompat.setDynamicShortcuts(appContext, shortcuts) }
    }

    private fun shortcutId(game: SupportedGame): String =
        "play_${game.name.lowercase()}"

    private fun shortLabel(game: SupportedGame): String = when (game) {
        SupportedGame.FREE_FIRE -> "Jugar FF"
        SupportedGame.FREE_FIRE_MAX -> "Jugar FFM"
    }
}
