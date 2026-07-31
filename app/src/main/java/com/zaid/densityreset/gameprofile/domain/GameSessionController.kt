package com.zaid.densityreset.gameprofile.domain

import android.content.Context
import com.zaid.densityreset.density.DensityPreset
import com.zaid.densityreset.gameprofile.data.GameSessionRepository
import com.zaid.densityreset.gameprofile.data.GameSessionRepositoryImpl
import com.zaid.densityreset.gameprofile.service.DpiGameSessionService
import com.zaid.densityreset.gameprofile.shizuku.ShizukuGameController
import com.zaid.densityreset.shizuku.ShizukuManager

interface GameSessionController {
    suspend fun startSession(
        game: SupportedGame,
        preset: DensityPreset
    ): GameSessionResult

    suspend fun restoreNow(): GameSessionResult

    suspend fun recoverPendingSession(): GameSessionResult
}

class GameSessionControllerImpl(
    context: Context,
    private val repository: GameSessionRepository =
        GameSessionRepositoryImpl(context),
    private val gameController: ShizukuGameController =
        ShizukuGameController(context)
) : GameSessionController {

    private val appContext = context.applicationContext

    override suspend fun startSession(
        game: SupportedGame,
        preset: DensityPreset
    ): GameSessionResult {
        val current = repository.read()
        if (current.sessionActive) {
            return GameSessionResult.Failure(
                "Ya existe una sesión de DPI activa."
            )
        }

        val shizuku = ShizukuManager.currentState()
        val validationError = when {
            !gameController.isInstalled(game) -> "Este juego no está instalado."
            !shizuku.installed -> "Shizuku no está instalado."
            !shizuku.running -> "Shizuku no está ejecutándose."
            !shizuku.permissionGranted -> "Permiso de Shizuku denegado."
            else -> null
        }
        if (validationError != null) {
            return GameSessionResult.Failure(validationError)
        }

        return runCatching {
            DpiGameSessionService.startSession(
                context = appContext,
                game = game,
                preset = preset
            )
            GameSessionResult.Success("Preparando sesión…")
        }.getOrElse { error ->
            GameSessionResult.Failure(
                error.message ?: "No se pudo iniciar la sesión."
            )
        }
    }

    override suspend fun restoreNow(): GameSessionResult {
        val current = repository.read()
        if (!current.sessionActive) {
            return GameSessionResult.Failure("No existe una sesión activa.")
        }
        DpiGameSessionService.restoreNow(appContext)
        return GameSessionResult.Success("Restaurando DPI…")
    }

    override suspend fun recoverPendingSession(): GameSessionResult {
        val current = repository.read()
        if (!current.sessionActive) {
            return GameSessionResult.Success("No hay sesiones pendientes.")
        }
        DpiGameSessionService.recover(appContext)
        return GameSessionResult.Success("Recuperando sesión pendiente…")
    }
}
