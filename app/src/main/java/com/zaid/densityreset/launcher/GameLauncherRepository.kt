package com.zaid.densityreset.launcher

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zaid.densityreset.density.DensityPreset
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface GameLauncherRepository {
    fun installedGame(game: SupportedGame): InstalledGameInfo
    fun observePreferences(): Flow<Map<SupportedGame, GameLauncherPreference>>
    suspend fun setLastProfile(game: SupportedGame, preset: DensityPreset)
    suspend fun setDefaultProfile(game: SupportedGame, preset: DensityPreset?)
}

data class InstalledGameInfo(
    val game: SupportedGame,
    val installed: Boolean,
    val applicationName: String,
    val packageName: String,
    val versionCode: Long,
    val lastUpdateTime: Long
)

data class GameLauncherPreference(
    val lastProfile: DensityPreset? = null,
    val defaultProfile: DensityPreset? = null
)

class GameLauncherRepositoryImpl(context: Context) : GameLauncherRepository {

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    override fun installedGame(game: SupportedGame): InstalledGameInfo {
        val applicationInfo = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(game.packageName, 0)
        }.getOrNull()
        val packageInfo = runCatching {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(game.packageName, 0)
        }.getOrNull()
        val installed = applicationInfo != null && packageInfo != null
        val label = applicationInfo?.let {
            runCatching { packageManager.getApplicationLabel(it).toString() }.getOrNull()
        }.orEmpty().ifBlank { game.displayName }
        val versionCode = packageInfo?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                it.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                it.versionCode.toLong()
            }
        } ?: 0L
        return InstalledGameInfo(
            game = game,
            installed = installed,
            applicationName = label,
            packageName = game.packageName,
            versionCode = versionCode,
            lastUpdateTime = packageInfo?.lastUpdateTime ?: 0L
        )
    }

    override fun observePreferences(): Flow<Map<SupportedGame, GameLauncherPreference>> =
        appContext.gameLauncherDataStore.data.map { preferences ->
            SupportedGame.entries.associateWith { game ->
                GameLauncherPreference(
                    lastProfile = preferences[lastKey(game)].toPreset(),
                    defaultProfile = preferences[defaultKey(game)].toPreset()
                )
            }
        }

    override suspend fun setLastProfile(game: SupportedGame, preset: DensityPreset) {
        appContext.gameLauncherDataStore.edit { preferences ->
            preferences[lastKey(game)] = preset.name
        }
    }

    override suspend fun setDefaultProfile(game: SupportedGame, preset: DensityPreset?) {
        appContext.gameLauncherDataStore.edit { preferences ->
            if (preset == null) {
                preferences.remove(defaultKey(game))
            } else {
                preferences[defaultKey(game)] = preset.name
            }
        }
    }

    private fun lastKey(game: SupportedGame): Preferences.Key<String> =
        stringPreferencesKey("${game.name.lowercase()}_last_profile")

    private fun defaultKey(game: SupportedGame): Preferences.Key<String> =
        stringPreferencesKey("${game.name.lowercase()}_default_profile")
}

private val Context.gameLauncherDataStore by preferencesDataStore(name = "game_launcher")

private fun String?.toPreset(): DensityPreset? = this?.let { name ->
    runCatching { DensityPreset.valueOf(name) }.getOrNull()
}
