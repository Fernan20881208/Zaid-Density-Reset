package com.zaid.densityreset.gameprofile.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ShizukuGameController(context: Context) {

    private val appContext = context.applicationContext
    private val commandExecutor = ShizukuCommandExecutor()

    fun installedGames(): Set<SupportedGame> =
        SupportedGame.entries.filterTo(linkedSetOf()) { isInstalled(it) }

    fun isInstalled(game: SupportedGame): Boolean = runCatching {
        val info = getApplicationInfo(game.packageName)
        info.enabled
    }.getOrDefault(false)

    suspend fun forceStop(game: SupportedGame): Result<Unit> {
        if (!isInstalled(game)) {
            return Result.failure(
                GameLaunchException("Este juego no está instalado.")
            )
        }

        return commandExecutor.execute(
            arrayOf(
                "/system/bin/am",
                "force-stop",
                game.packageName
            )
        ).fold(
            onSuccess = { result ->
                if (result.isSuccess) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        GameLaunchException("No se pudo reiniciar el juego.")
                    )
                }
            },
            onFailure = {
                Result.failure(
                    GameLaunchException("No se pudo reiniciar el juego.", it)
                )
            }
        )
    }

    suspend fun launch(game: SupportedGame): Result<Unit> {
        if (!isInstalled(game)) {
            return Result.failure(
                GameLaunchException("Este juego no está instalado.")
            )
        }

        val launchIntent = appContext.packageManager
            .getLaunchIntentForPackage(game.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }

        if (launchIntent != null) {
            val started = withContext(Dispatchers.Main.immediate) {
                runCatching {
                    appContext.startActivity(launchIntent)
                }
            }
            if (started.isSuccess) return Result.success(Unit)
        }

        return commandExecutor.execute(
            arrayOf(
                "/system/bin/monkey",
                "-p",
                game.packageName,
                "-c",
                "android.intent.category.LAUNCHER",
                "1"
            ),
            timeoutSeconds = MONKEY_TIMEOUT_SECONDS
        ).fold(
            onSuccess = { result ->
                if (result.isSuccess) {
                    Result.success(Unit)
                } else {
                    Result.failure(
                        GameLaunchException(
                            "No se pudo abrir el juego. El DPI anterior será restaurado."
                        )
                    )
                }
            },
            onFailure = {
                Result.failure(
                    GameLaunchException(
                        "No se pudo abrir el juego. El DPI anterior será restaurado.",
                        it
                    )
                )
            }
        )
    }

    suspend fun foregroundPackage(): Result<String?> =
        commandExecutor.execute(
            arrayOf(
                "/system/bin/dumpsys",
                "activity",
                "activities"
            ),
            timeoutSeconds = FOREGROUND_QUERY_TIMEOUT_SECONDS
        ).mapCatching { result ->
            if (!result.isSuccess) {
                throw GameLaunchException("No se pudo consultar la aplicación activa.")
            }
            parseForegroundPackage(result.stdout)
        }

    private fun getApplicationInfo(packageName: String): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(
                    PackageManager.MATCH_DISABLED_COMPONENTS.toLong()
                )
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.packageManager.getApplicationInfo(
                packageName,
                PackageManager.MATCH_DISABLED_COMPONENTS
            )
        }

    class GameLaunchException(
        override val message: String,
        cause: Throwable? = null
    ) : Exception(message, cause)

    private companion object {
        const val MONKEY_TIMEOUT_SECONDS = 10L
        const val FOREGROUND_QUERY_TIMEOUT_SECONDS = 5L
    }
}

internal fun parseForegroundPackage(output: String): String? {
    val activityRegex = Regex("""\\bu\\d+\\s+([A-Za-z0-9._]+)/(?:[A-Za-z0-9._$]+)""")
    val preferredMarkers = listOf(
        "topResumedActivity=",
        "mResumedActivity:"
    )

    for (marker in preferredMarkers) {
        output.lineSequence()
            .firstOrNull { line -> line.contains(marker) }
            ?.let { line ->
                activityRegex.find(line)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.let { return it }
            }
    }

    return output.lineSequence()
        .firstNotNullOfOrNull { line ->
            if (!line.contains("ResumedActivity", ignoreCase = true)) {
                null
            } else {
                activityRegex.find(line)?.groupValues?.getOrNull(1)
            }
        }
}
