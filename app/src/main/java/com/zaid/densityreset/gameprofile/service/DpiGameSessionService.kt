package com.zaid.densityreset.gameprofile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import android.widget.RemoteViews
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zaid.densityreset.R
import com.zaid.densityreset.accessibility.DpiGameLockBridge
import com.zaid.densityreset.analytics.GamePlaytimeRepositoryImpl
import com.zaid.densityreset.automation.GameEnvironmentManager
import com.zaid.densityreset.booster.BoosterMode
import com.zaid.densityreset.booster.BoosterResult
import com.zaid.densityreset.booster.GameBoosterManager
import com.zaid.densityreset.booster.GameBoosterState
import com.zaid.densityreset.booster.GamePerformanceState
import com.zaid.densityreset.booster.ThermalLevel
import com.zaid.densityreset.density.DensityPreset
import com.zaid.densityreset.density.ShizukuDensityController
import com.zaid.densityreset.gameprofile.data.GameSessionRepository
import com.zaid.densityreset.gameprofile.data.GameSessionRepositoryImpl
import com.zaid.densityreset.gameprofile.domain.DensitySnapshot
import com.zaid.densityreset.gameprofile.domain.DensityRestorationTarget
import com.zaid.densityreset.gameprofile.domain.GameSessionState
import com.zaid.densityreset.gameprofile.domain.SessionStep
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import com.zaid.densityreset.gameprofile.domain.restorationTarget
import com.zaid.densityreset.gameprofile.shizuku.ShizukuCommandExecutor
import com.zaid.densityreset.gameprofile.shizuku.ShizukuGameController
import com.zaid.densityreset.icons.DensityIconInvalidationCoordinator
import com.zaid.densityreset.recording.GameScreenRecorder
import com.zaid.densityreset.recording.ScreenCaptureGrant
import com.zaid.densityreset.shizuku.ShizukuManager
import com.zaid.densityreset.startup.StartupActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Single foreground service for a game session. It keeps the existing verified
 * 20-second DPI window, runs the capability-based Game Booster and lightweight
 * monitors, then restores every temporary change when the game ends.
 */
class DpiGameSessionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val repository: GameSessionRepository by lazy {
        GameSessionRepositoryImpl(applicationContext)
    }
    private val densityController by lazy {
        ShizukuDensityController(applicationContext)
    }
    private val gameController by lazy {
        ShizukuGameController(applicationContext)
    }
    private val commandExecutor by lazy {
        ShizukuCommandExecutor()
    }
    private val boosterManager by lazy {
        GameBoosterManager(applicationContext, commandExecutor)
    }
    private val environmentManager by lazy {
        GameEnvironmentManager(applicationContext)
    }
    private val playtimeRepository by lazy {
        GamePlaytimeRepositoryImpl(applicationContext)
    }
    private val screenRecorder by lazy {
        GameScreenRecorder(applicationContext)
    }
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private var operationJob: Job? = null
    private var timerJob: Job? = null
    private var gameWatchJob: Job? = null
    private var boosterStateJob: Job? = null
    private var notificationHeartbeatJob: Job? = null
    private var foregroundStarted = false
    private var gameExitConfirmed = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        boosterStateJob = serviceScope.launch {
            boosterManager.observeState().collect {
                val session = repository.read()
                if (session.sessionActive) {
                    updateSessionNotification(session, session.restoreAt?.let { secondsRemaining(session) })
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val projectionData = intent?.projectionDataExtra()
        val screenCaptureGrant = projectionData?.let {
            ScreenCaptureGrant(
                resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT_CODE, 0),
                data = it,
                requestInternalAudio = intent.getBooleanExtra(
                    EXTRA_REQUEST_INTERNAL_AUDIO,
                    false
                )
            )
        }
        ensureForeground(
            buildNotification(
                title = getString(R.string.game_session_preparing),
                text = getString(R.string.game_session_checking_state),
                includeRestore = false
            ),
            mediaProjection = screenCaptureGrant != null
        )

        when (intent?.action) {
            ACTION_START_SESSION -> {
                val game = SupportedGame.fromPackageName(
                    intent.getStringExtra(EXTRA_GAME_PACKAGE)
                )
                val preset = intent.getStringExtra(EXTRA_PRESET)
                    ?.let { runCatching { DensityPreset.valueOf(it) }.getOrNull() }
                val boosterMode = intent.getStringExtra(EXTRA_BOOSTER_MODE)
                    ?.let { runCatching { BoosterMode.valueOf(it) }.getOrNull() }

                if (game == null || preset == null) {
                    launchOperation {
                        repository.failAndClear(
                            getString(R.string.game_session_invalid_configuration)
                        )
                        DpiGameLockBridge.notifySessionChanged()
                        stopServiceCleanly()
                    }
                } else {
                    launchOperation {
                        startSessionFlow(game, preset, boosterMode, screenCaptureGrant)
                    }
                }
            }

            ACTION_STOP_RECORDING -> {
                serviceScope.launch {
                    screenRecorder.stop()
                    val session = repository.read()
                    if (session.sessionActive) {
                        updateSessionNotification(
                            session,
                            session.restoreAt?.let { secondsRemaining(session) }
                        )
                    } else {
                        stopServiceCleanly()
                    }
                }
            }

            ACTION_RESTORE_NOW -> {
                val source = intent.getStringExtra(EXTRA_RESTORE_SOURCE)
                    ?: RESTORE_SOURCE_MANUAL
                if (source == RESTORE_SOURCE_GAME_EXIT) {
                    // Accessibility can produce transient foreground changes.
                    // The service owns the final game-exit decision with a
                    // multi-sample guard, so this signal only ensures recovery.
                    if (operationJob?.isActive != true) {
                        launchOperation(cancelTimer = false) {
                            recoverPendingSession()
                        }
                    }
                } else {
                    launchOperation(cancelTimer = true, cancelWatcher = true) {
                        restoreEverything(source)
                    }
                }
            }

            ACTION_RECOVER_SESSION, null -> {
                launchOperation(cancelTimer = false) {
                    recoverPendingSession()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timerJob?.cancel()
        gameWatchJob?.cancel()
        boosterStateJob?.cancel()
        notificationHeartbeatJob?.cancel()
        operationJob?.cancel()
        boosterManager.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun launchOperation(
        cancelTimer: Boolean = false,
        cancelWatcher: Boolean = false,
        block: suspend () -> Unit
    ) {
        operationJob?.cancel()
        if (cancelTimer) timerJob?.cancel()
        if (cancelWatcher) gameWatchJob?.cancel()
        operationJob = serviceScope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                handleUnexpectedFailure(error)
            }
        }
    }

    private suspend fun startSessionFlow(
        game: SupportedGame,
        preset: DensityPreset,
        boosterMode: BoosterMode?,
        screenCaptureGrant: ScreenCaptureGrant?
    ) {
        val existing = repository.read()
        if (existing.sessionActive) {
            recoverPendingSession()
            return
        }

        gameExitConfirmed = false
        repository.updateStep(SessionStep.VALIDATING)
        updatePreparingNotification(game, preset, "Validando requisitos")

        val shizuku = ShizukuManager.currentState()
        val validationError = when {
            !gameController.isInstalled(game) -> "Este juego no está instalado."
            !shizuku.installed -> "Shizuku no está instalado."
            !shizuku.running -> "Shizuku no está ejecutándose."
            !shizuku.permissionGranted -> "Permiso de Shizuku denegado."
            else -> null
        }
        if (validationError != null) {
            failWithoutRestoration(validationError)
            return
        }

        val originalState = densityController.getSystemState().getOrElse { error ->
            failWithoutRestoration(
                error.message ?: "No fue posible acceder a WindowManager."
            )
            return
        }

        val densitySnapshot = DensitySnapshot(
            physicalDensity = originalState.initialDensity,
            effectiveDensity = originalState.currentDensity,
            hadOverride = originalState.hasOverride,
            previousOverrideDensity = originalState.currentDensity
                .takeIf { originalState.hasOverride }
        )

        repository.beginSession(
            game = game,
            preset = preset,
            snapshot = densitySnapshot,
            startedAt = System.currentTimeMillis()
        )

        updatePreparingNotification(game, preset, "Aplicando automatizaciones")
        environmentManager.apply(game)

        repository.updateStep(SessionStep.CLOSING_GAME)
        updatePreparingNotification(game, preset, "Cerrando el juego")
        gameController.forceStop(game).getOrElse {
            failWithoutRestoration("No se pudo reiniciar el juego.")
            return
        }

        if (boosterMode != null) {
            updatePreparingNotification(game, preset, "Preparando ${boosterMode.displayName}")
            // Booster/monitor failures are intentionally non-blocking. The
            // manager only writes Game Mode after it has captured a restorable
            // previous value, and every monitor can independently be absent.
            boosterManager.prepare(game.packageName, boosterMode)
        }

        repository.updateStep(SessionStep.APPLYING_DENSITY)
        updatePreparingNotification(game, preset, "Aplicando ${preset.density} DPI")

        densityController.applyDensity(preset.density).getOrElse { error ->
            abortAndRestoreAll(
                error.message ?: "El dispositivo no confirmó el DPI seleccionado."
            )
            return
        }

        repository.updateStep(SessionStep.VERIFYING_DENSITY)
        updatePreparingNotification(game, preset, "Verificando DPI real")
        delay(DENSITY_SETTLE_MILLIS)

        val verified = densityController.getSystemState().getOrElse {
            abortAndRestoreAll("El dispositivo no confirmó el DPI seleccionado.")
            return
        }
        if (!verified.hasOverride || verified.currentDensity != preset.density) {
            abortAndRestoreAll(
                "El comando no quedó aplicado: se esperaba ${preset.density} DPI y WindowManager reportó ${verified.currentDensity} DPI."
            )
            return
        }

        // The deadline starts only after WindowManager confirms the target DPI.
        // This preserves the exact behavior introduced in 1.5.3.
        val restoreAt = System.currentTimeMillis() + SESSION_DURATION_MILLIS
        repository.markSessionActive(restoreAt)
        DpiGameLockBridge.notifySessionChanged()
        scheduleResetAt(restoreAt)

        repository.updateStep(SessionStep.OPENING_GAME)
        updatePreparingNotification(game, preset, "Abriendo ${game.displayName}")
        gameController.launch(game).getOrElse {
            abortAndRestoreAll("No se pudo abrir el juego. Se restaurará la sesión.")
            return
        }
        repository.markGameLaunched(System.currentTimeMillis())

        if (screenCaptureGrant != null) {
            delay(RECORDING_START_DELAY_MILLIS)
            screenRecorder.start(
                game = game,
                resultCode = screenCaptureGrant.resultCode,
                projectionData = screenCaptureGrant.data,
                requestInternalAudio = screenCaptureGrant.requestInternalAudio
            ).onFailure { error ->
                showToast(error.message ?: "No se pudo iniciar la grabación de pantalla.")
            }
        }

        repository.markSessionActive(restoreAt)
        DpiGameLockBridge.notifySessionChanged()
        startGameWatcher(game)
        startNotificationHeartbeat()
        updateSessionNotification(repository.read(), secondsRemaining(repository.read()))
    }

    private suspend fun recoverPendingSession() {
        val session = repository.read()
        if (!session.sessionActive) {
            if (boosterManager.hasSnapshot()) {
                boosterManager.restore()
            }
            environmentManager.restore()
            screenRecorder.stop()
            stopServiceCleanly()
            return
        }

        val game = session.selectedGame
        if (boosterManager.hasSnapshot()) {
            boosterManager.recoverIfNeeded()
        }
        if (game != null) startGameWatcher(game)

        if (session.currentStep == SessionStep.BOOSTER_ACTIVE) {
            updateSessionNotification(session, null)
            return
        }

        val restoreAt = session.restoreAt
        if (restoreAt == null || restoreAt <= System.currentTimeMillis()) {
            handleDpiDeadline()
            return
        }

        val target = session.targetDensity
        if (target != null) {
            val state = densityController.getSystemState().getOrNull()
            if (state == null || !state.hasOverride || state.currentDensity != target) {
                densityController.applyDensity(target).getOrElse {
                    restoreEverything(RESTORE_SOURCE_RECOVERY)
                    return
                }
                val reverified = densityController.getSystemState().getOrNull()
                if (reverified == null || reverified.currentDensity != target) {
                    restoreEverything(RESTORE_SOURCE_RECOVERY)
                    return
                }
            }
        }

        DpiGameLockBridge.notifySessionChanged()
        updateSessionNotification(session, secondsRemaining(session))
        scheduleResetAt(restoreAt)
    }

    private fun scheduleResetAt(restoreAt: Long) {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                val remainingMillis = restoreAt - System.currentTimeMillis()
                if (remainingMillis <= 0L) break

                val session = repository.read()
                if (!session.sessionActive) return@launch
                updateSessionNotification(session, secondsRemaining(session))
                delay(min(COUNTDOWN_UPDATE_MILLIS, remainingMillis))
            }

            if (repository.read().sessionActive) {
                handleDpiDeadline()
            }
        }
    }

    private suspend fun handleDpiDeadline() {
        val session = repository.read()
        if (!session.sessionActive) return

        val densityRestore = executeDensityRestoration(session.snapshot)
        if (densityRestore.isFailure) {
            screenRecorder.stop()
            val environmentRestore = environmentManager.restore()
            val boosterRestore = boosterManager.restore()
            val message = densityRestore.exceptionOrNull()?.message
                ?: getString(R.string.game_session_restore_failed)
            repository.markRestorationFailure(
                listOfNotNull(
                    message,
                    (boosterRestore as? BoosterResult.Failure)?.message,
                    environmentRestore.exceptionOrNull()?.message
                ).joinToString(" ")
            )
            DpiGameLockBridge.notifySessionChanged()
            updateSessionNotification(repository.read(), null)
            showToast(message)
            return
        }

        DpiGameLockBridge.notifySessionChanged()
        if (!gameExitConfirmed) {
            repository.markBoosterActive()
            DpiGameLockBridge.notifySessionChanged()
            updateSessionNotification(repository.read(), null)
        } else {
            screenRecorder.stop()
            val environmentRestore = environmentManager.restore()
            val boosterRestore = boosterManager.restore()
            val failure = listOfNotNull(
                (boosterRestore as? BoosterResult.Failure)?.message,
                environmentRestore.exceptionOrNull()?.message
            ).joinToString(" ")
            if (failure.isNotBlank()) {
                repository.markRestorationFailure(failure)
                updateSessionNotification(repository.read(), null)
                return
            }
            repository.finishSession(getString(R.string.dpi_restored_successfully))
            DpiGameLockBridge.notifySessionChanged()
            stopServiceCleanly()
        }
    }

    private fun startGameWatcher(game: SupportedGame) {
        gameWatchJob?.cancel()
        gameWatchJob = serviceScope.launch {
            delay(GAME_WATCH_START_DELAY_MILLIS)
            var seenGame = false
            var outsideSamples = 0
            var notSeenSamples = 0

            while (isActive) {
                val session = repository.read()
                if (!session.sessionActive) return@launch

                val foreground = gameController.foregroundPackage().getOrNull()
                when {
                    foreground == game.packageName -> {
                        seenGame = true
                        outsideSamples = 0
                        notSeenSamples = 0
                    }
                    foreground.isNullOrBlank() || foreground in TRANSIENT_PACKAGES -> Unit
                    seenGame -> outsideSamples++
                    else -> notSeenSamples++
                }

                if (
                    outsideSamples >= GAME_EXIT_CONFIRMATION_SAMPLES ||
                    (!seenGame && notSeenSamples >= GAME_NOT_SEEN_CONFIRMATION_SAMPLES)
                ) {
                    gameExitConfirmed = true
                    handleConfirmedGameExit()
                    return@launch
                }
                delay(GAME_WATCH_INTERVAL_MILLIS)
            }
        }
    }

    private fun startNotificationHeartbeat() {
        notificationHeartbeatJob?.cancel()
        notificationHeartbeatJob = serviceScope.launch {
            while (isActive) {
                val session = repository.read()
                if (!session.sessionActive) return@launch
                updateSessionNotification(
                    session,
                    session.restoreAt?.let { secondsRemaining(session) }
                )
                delay(NOTIFICATION_HEARTBEAT_MILLIS)
            }
        }
    }

    private suspend fun handleConfirmedGameExit() {
        val session = repository.read()
        if (!session.sessionActive) return

        recordPlaytime(session)
        screenRecorder.stop()
        val environmentRestore = environmentManager.restore()
        val boosterRestore = boosterManager.restore()
        val failure = listOfNotNull(
            (boosterRestore as? BoosterResult.Failure)?.message,
            environmentRestore.exceptionOrNull()?.message
        ).joinToString(" ")
        if (failure.isNotBlank()) {
            repository.markRestorationFailure(failure)
            DpiGameLockBridge.notifySessionChanged()
            updateSessionNotification(repository.read(), session.restoreAt?.let { secondsRemaining(session) })
            return
        }

        val restoreAt = session.restoreAt
        when {
            session.currentStep == SessionStep.BOOSTER_ACTIVE || restoreAt == null -> {
                repository.finishSession("Sesión finalizada y Game Booster restaurado.")
                DpiGameLockBridge.notifySessionChanged()
                stopServiceCleanly()
            }
            restoreAt <= System.currentTimeMillis() -> handleDpiDeadline()
            else -> {
                // The game ended before the fixed DPI deadline. Game Mode and
                // monitors are already restored, but the DPI intentionally
                // remains until its original 20-second timestamp.
                updateSessionNotification(session, secondsRemaining(session))
            }
        }
    }

    private suspend fun abortAndRestoreAll(message: String) {
        timerJob?.cancel()
        gameWatchJob?.cancel()
        notificationHeartbeatJob?.cancel()
        val session = repository.read()
        recordPlaytime(session)
        val recording = screenRecorder.stop()
        val environment = environmentManager.restore()
        val booster = boosterManager.restore()
        val density = executeDensityRestoration(session.snapshot)
        if (
            booster !is BoosterResult.Failure &&
            density.isSuccess &&
            environment.isSuccess &&
            recording.isSuccess
        ) {
            repository.failAndClear(message)
            DpiGameLockBridge.notifySessionChanged()
            showToast(message)
            stopServiceCleanly()
        } else {
            val failure = buildString {
                density.exceptionOrNull()?.message?.let { append(it) }
                if (booster is BoosterResult.Failure) {
                    if (isNotEmpty()) append(" ")
                    append(booster.message)
                }
                environment.exceptionOrNull()?.message?.let {
                    if (isNotEmpty()) append(" ")
                    append(it)
                }
                recording.exceptionOrNull()?.message?.let {
                    if (isNotEmpty()) append(" ")
                    append(it)
                }
            }.ifBlank { getString(R.string.game_session_restore_failed) }
            repository.markRestorationFailure(failure)
            DpiGameLockBridge.notifySessionChanged()
            updateSessionNotification(repository.read(), null)
            showToast(failure)
        }
    }

    private suspend fun restoreEverything(source: String) {
        val session = repository.read()
        if (!session.sessionActive) {
            screenRecorder.stop()
            environmentManager.restore()
            boosterManager.restore()
            DpiGameLockBridge.notifySessionChanged()
            stopServiceCleanly()
            return
        }

        repository.updateStep(SessionStep.RESTORING_DENSITY)
        DpiGameLockBridge.notifySessionChanged()
        updateSessionNotification(
            session.copy(currentStep = SessionStep.RESTORING_DENSITY),
            null
        )

        recordPlaytime(session)
        val recording = screenRecorder.stop()
        val environment = environmentManager.restore()
        val booster = boosterManager.restore()
        val density = executeDensityRestoration(session.snapshot)
        if (
            booster !is BoosterResult.Failure &&
            density.isSuccess &&
            environment.isSuccess &&
            recording.isSuccess
        ) {
            repository.finishSession("DPI y Game Booster restaurados correctamente.")
            DpiGameLockBridge.notifySessionChanged()
            if (source != RESTORE_SOURCE_RECOVERY) {
                showToast("DPI y Game Booster restaurados correctamente.")
            }
            stopServiceCleanly()
        } else {
            val message = buildString {
                density.exceptionOrNull()?.message?.let { append(it) }
                if (booster is BoosterResult.Failure) {
                    if (isNotEmpty()) append(" ")
                    append(booster.message)
                }
                environment.exceptionOrNull()?.message?.let {
                    if (isNotEmpty()) append(" ")
                    append(it)
                }
                recording.exceptionOrNull()?.message?.let {
                    if (isNotEmpty()) append(" ")
                    append(it)
                }
            }.ifBlank { getString(R.string.game_session_restore_failed) }
            repository.markRestorationFailure(message)
            DpiGameLockBridge.notifySessionChanged()
            updateSessionNotification(repository.read(), null)
            showToast(message)
        }
    }

    private suspend fun executeDensityRestoration(
        snapshot: DensitySnapshot?
    ): Result<Unit> {
        return when (val target = snapshot.restorationTarget()) {
            DensityRestorationTarget.PhysicalDensity -> executeWmDensityReset()
            is DensityRestorationTarget.OverrideDensity -> {
                restoreDensityOverride(target.density)
            }
        }
    }

    private suspend fun recordPlaytime(session: GameSessionState) {
        val game = session.selectedGame ?: return
        val preset = session.selectedPreset ?: return
        val sessionId = session.sessionStartedAt ?: return
        val launchedAt = session.gameLaunchedAt ?: return
        playtimeRepository.recordSession(
            game = game,
            preset = preset,
            sessionId = sessionId,
            startedAt = launchedAt,
            endedAt = System.currentTimeMillis()
        )
    }

    private suspend fun restoreDensityOverride(density: Int): Result<Unit> {
        densityController.applyDensity(density).getOrElse {
            return Result.failure(it)
        }

        val verified = densityController.getSystemState().getOrElse {
            return Result.failure(it)
        }
        if (!verified.hasOverride || verified.currentDensity != density) {
            return Result.failure(
                IllegalStateException(
                    "Se intentó restaurar el override de $density DPI, pero WindowManager " +
                        "reportó ${verified.currentDensity} DPI."
                )
            )
        }
        return Result.success(Unit)
    }

    /**
     * Restores the physical density when the saved snapshot confirms there was
     * no pre-existing override. The command runs through Shizuku and is
     * followed by a real WindowManager state verification.
     */
    private suspend fun executeWmDensityReset(): Result<Unit> {
        val before = densityController.getSystemState().getOrNull()

        val commandResult = commandExecutor.execute(
            arrayOf("/system/bin/wm", "density", "reset")
        ).getOrElse { return Result.failure(it) }

        if (!commandResult.isSuccess) {
            val detail = commandResult.stderr.ifBlank { commandResult.stdout }
            return Result.failure(
                IllegalStateException(
                    detail.ifBlank { "wm density reset devolvió código ${commandResult.exitCode}." }
                )
            )
        }

        delay(DENSITY_SETTLE_MILLIS)
        val verified = densityController.getSystemState().getOrElse {
            return Result.failure(it)
        }

        if (verified.hasOverride || verified.currentDensity != verified.initialDensity) {
            return Result.failure(
                IllegalStateException(
                    "wm density reset se ejecutó, pero WindowManager aún reporta ${verified.currentDensity} DPI."
                )
            )
        }

        if (before != null && before.currentDensity != verified.currentDensity) {
            DensityIconInvalidationCoordinator.onDensityChanged(
                context = applicationContext,
                previousDensity = before.currentDensity,
                expectedDensity = verified.currentDensity,
                hasOverride = false
            )
        }

        return Result.success(Unit)
    }

    private suspend fun failWithoutRestoration(message: String) {
        screenRecorder.stop()
        environmentManager.restore()
        boosterManager.restore()
        repository.failAndClear(message)
        DpiGameLockBridge.notifySessionChanged()
        showToast(message)
        stopServiceCleanly()
    }

    private suspend fun handleUnexpectedFailure(error: Throwable) {
        val session = repository.read()
        val message = error.message ?: getString(R.string.game_session_unexpected_error)
        if (session.sessionActive) {
            abortAndRestoreAll(message)
        } else {
            screenRecorder.stop()
            environmentManager.restore()
            boosterManager.restore()
            repository.failAndClear(message)
            DpiGameLockBridge.notifySessionChanged()
            stopServiceCleanly()
        }
    }

    private fun secondsRemaining(session: GameSessionState): Int {
        val restoreAt = session.restoreAt ?: return 0
        return ceil(
            (restoreAt - System.currentTimeMillis()).coerceAtLeast(0L) / 1_000.0
        ).toInt()
    }

    private fun updatePreparingNotification(
        game: SupportedGame,
        preset: DensityPreset,
        detail: String
    ) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(
                getString(R.string.game_session_preparing),
                "${game.displayName} · ${preset.displayName} · $detail",
                includeRestore = true
            )
        )
    }

    private fun updateSessionNotification(
        session: GameSessionState,
        seconds: Int?
    ) {
        val game = session.selectedGame
        val preset = session.selectedPreset
        if (game == null || preset == null) {
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    getString(R.string.game_session_preparing),
                    session.errorMessage ?: getString(R.string.game_session_checking_state),
                    includeRestore = true
                )
            )
            return
        }

        when (session.currentStep) {
            SessionStep.RESTORING_DENSITY -> notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    getString(R.string.game_session_restoring),
                    "${game.displayName} · Restaurando cambios temporales",
                    includeRestore = false
                )
            )
            SessionStep.ERROR -> notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    getString(R.string.game_session_attention),
                    session.errorMessage ?: getString(R.string.game_session_restore_failed),
                    includeRestore = true
                )
            )
            else -> updateGameNotification(game, preset, session, seconds)
        }
    }

    private fun updateGameNotification(
        game: SupportedGame,
        preset: DensityPreset,
        session: GameSessionState,
        seconds: Int?
    ) {
        val booster = currentBoosterState()
        val mode = booster.mode
        val metrics = formatNotificationMetrics(booster.monitor)
        val title = if (mode != null || booster.active) {
            "Density Reset · Game Booster"
        } else {
            getString(R.string.game_session_active)
        }
        val line = buildList {
            add(game.displayName)
            mode?.displayName?.let(::add)
            if (session.currentStep != SessionStep.BOOSTER_ACTIVE && seconds != null) {
                add("DPI reset en $seconds s")
            }
            if (metrics.isNotBlank()) add(metrics)
        }.joinToString(" · ")

        val batterySnapshot = readBatterySnapshot()
        val batteryPercent = booster.monitor.battery?.percent ?: batterySnapshot.percent
        val battery = batteryPercent?.let { "$it%" } ?: "—%"
        val dpi = if (session.currentStep == SessionStep.BOOSTER_ACTIVE) {
            "DPI restaurado"
        } else {
            "${preset.density} DPI"
        }
        val island = RemoteViews(packageName, R.layout.notification_game_island).apply {
            setTextViewText(
                R.id.islandTitle,
                "${gameShortName(game)} · $dpi · $battery"
            )
            setTextViewText(
                R.id.islandSubtitle,
                listOfNotNull(
                    booster.monitor.thermal?.temperatureCelsius?.let { "${it.roundToInt()}°C" },
                    batterySnapshot.temperatureCelsius
                        ?.takeIf { booster.monitor.thermal?.temperatureCelsius == null }
                        ?.let { "${it.roundToInt()}°C" },
                    formatElapsed(session.gameLaunchedAt),
                    mode?.displayName
                ).joinToString(" · ").ifBlank { "Sesión activa" }
            )
            setOnClickPendingIntent(
                R.id.islandRestore,
                restorePendingIntent(RESTORE_SOURCE_NOTIFICATION)
            )
            setViewVisibility(
                R.id.islandStopRecording,
                if (screenRecorder.isRecording) View.VISIBLE else View.GONE
            )
            if (screenRecorder.isRecording) {
                setOnClickPendingIntent(R.id.islandStopRecording, stopRecordingPendingIntent())
            }
        }

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_density)
            .setContentTitle(title)
            .setContentText(line)
            .setCustomContentView(island)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                0,
                getString(R.string.restore_now),
                restorePendingIntent(RESTORE_SOURCE_NOTIFICATION)
            )

        if (screenRecorder.isRecording) {
            builder.addAction(0, "Detener grabación", stopRecordingPendingIntent())
        }

        if (session.currentStep != SessionStep.BOOSTER_ACTIVE && seconds != null) {
            builder.setProgress(
                SESSION_DURATION_SECONDS,
                seconds.coerceIn(0, SESSION_DURATION_SECONDS),
                false
            )
        }
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun currentBoosterState(): GameBoosterState =
        (boosterManager.observeState() as? kotlinx.coroutines.flow.StateFlow<GameBoosterState>)?.value
            ?: com.zaid.densityreset.booster.GameBoosterRuntime.mutableState.value

    private fun formatNotificationMetrics(monitor: GamePerformanceState): String {
        val values = mutableListOf<String>()
        monitor.fps?.fps?.let { values += "FPS ${it.roundToInt()}" }
        if (monitor.fps?.fps == null) {
            monitor.ram?.let { values += "RAM ${formatGigabytes(it.availableBytes)} GB" }
        }
        monitor.thermal?.let { thermal ->
            values += thermal.temperatureCelsius?.let { "${it.roundToInt()}°C" }
                ?: thermal.level.displayName
        }
        monitor.battery?.let { values += "${it.percent}%" }
        return values.joinToString(" · ")
    }

    private fun formatGigabytes(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f", bytes.toDouble() / GIBIBYTE)

    private fun formatElapsed(startedAt: Long?): String? {
        val start = startedAt ?: return null
        val totalSeconds = ((System.currentTimeMillis() - start).coerceAtLeast(0L) / 1_000L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun gameShortName(game: SupportedGame): String = when (game) {
        SupportedGame.FREE_FIRE -> "FF"
        SupportedGame.FREE_FIRE_MAX -> "FFM"
    }

    private fun readBatterySnapshot(): BatterySnapshot {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return BatterySnapshot()
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).roundToInt().coerceIn(0, 100)
        } else {
            null
        }
        val rawTemperature = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return BatterySnapshot(
            percent = percent,
            temperatureCelsius = rawTemperature
                .takeIf { it != Int.MIN_VALUE }
                ?.div(10f)
        )
    }

    private fun buildNotification(
        title: String,
        text: String,
        includeRestore: Boolean
    ): Notification {
        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_density)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (includeRestore) {
            builder.addAction(
                0,
                getString(R.string.restore_now),
                restorePendingIntent(RESTORE_SOURCE_NOTIFICATION)
            )
        }
        return builder.build()
    }

    private fun ensureForeground(
        notification: Notification,
        mediaProjection: Boolean = false
    ) {
        if (foregroundStarted) return
        var foregroundType = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            foregroundType = foregroundType or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        if (mediaProjection && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            foregroundType = foregroundType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            foregroundType
        )
        foregroundStarted = true
    }

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        REQUEST_OPEN_APP,
        Intent(this, StartupActivity::class.java).apply {
            action = StartupActivity.ACTION_OPEN_GAME_LAUNCHER
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun restorePendingIntent(source: String): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_RESTORE,
        Intent(this, DpiGameSessionService::class.java).apply {
            action = ACTION_RESTORE_NOW
            putExtra(EXTRA_RESTORE_SOURCE, source)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopRecordingPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        REQUEST_STOP_RECORDING,
        Intent(this, DpiGameSessionService::class.java).apply {
            action = ACTION_STOP_RECORDING
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.game_session_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene Game Booster, monitores y restauración temporal durante el juego."
                setShowBadge(false)
            }
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun stopServiceCleanly() {
        timerJob?.cancel()
        gameWatchJob?.cancel()
        notificationHeartbeatJob?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun Intent.projectionDataExtra(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_PROJECTION_DATA)
        }

    companion object {
        private const val ACTION_START_SESSION =
            "com.zaidnavarro.ds.action.START_GAME_DPI_SESSION"
        private const val ACTION_RESTORE_NOW =
            "com.zaidnavarro.ds.action.RESTORE_GAME_DPI_SESSION"
        private const val ACTION_RECOVER_SESSION =
            "com.zaidnavarro.ds.action.RECOVER_GAME_DPI_SESSION"
        private const val ACTION_STOP_RECORDING =
            "com.zaidnavarro.ds.action.STOP_GAME_RECORDING"
        private const val EXTRA_GAME_PACKAGE = "extra_game_package"
        private const val EXTRA_PRESET = "extra_density_preset"
        private const val EXTRA_BOOSTER_MODE = "extra_booster_mode"
        private const val EXTRA_RESTORE_SOURCE = "extra_restore_source"
        private const val EXTRA_PROJECTION_RESULT_CODE = "extra_projection_result_code"
        private const val EXTRA_PROJECTION_DATA = "extra_projection_data"
        private const val EXTRA_REQUEST_INTERNAL_AUDIO = "extra_request_internal_audio"

        const val RESTORE_SOURCE_VOLUME = "volume_gesture"
        const val RESTORE_SOURCE_GAME_EXIT = "game_exit"
        private const val RESTORE_SOURCE_MANUAL = "manual"
        private const val RESTORE_SOURCE_NOTIFICATION = "notification"
        private const val RESTORE_SOURCE_RECOVERY = "recovery"

        private const val NOTIFICATION_CHANNEL_ID = "dpi_game_session"
        private const val NOTIFICATION_ID = 4102
        private const val REQUEST_OPEN_APP = 4103
        private const val REQUEST_RESTORE = 4104
        private const val REQUEST_STOP_RECORDING = 4105

        const val SESSION_DURATION_SECONDS = 20
        private const val SESSION_DURATION_MILLIS = 20_000L
        private const val COUNTDOWN_UPDATE_MILLIS = 1_000L
        private const val NOTIFICATION_HEARTBEAT_MILLIS = 5_000L
        private const val DENSITY_SETTLE_MILLIS = 350L
        private const val GAME_WATCH_START_DELAY_MILLIS = 4_000L
        private const val GAME_WATCH_INTERVAL_MILLIS = 1_500L
        private const val GAME_EXIT_CONFIRMATION_SAMPLES = 3
        private const val GAME_NOT_SEEN_CONFIRMATION_SAMPLES = 20
        private const val RECORDING_START_DELAY_MILLIS = 1_200L
        private const val GIBIBYTE = 1_073_741_824.0

        private val TRANSIENT_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.miui.securitycenter"
        )

        fun startSession(
            context: Context,
            game: SupportedGame,
            preset: DensityPreset,
            boosterMode: BoosterMode? = null,
            screenCaptureGrant: ScreenCaptureGrant? = null
        ) {
            val intent = Intent(context, DpiGameSessionService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra(EXTRA_GAME_PACKAGE, game.packageName)
                putExtra(EXTRA_PRESET, preset.name)
                boosterMode?.let { putExtra(EXTRA_BOOSTER_MODE, it.name) }
                screenCaptureGrant?.let { grant ->
                    putExtra(EXTRA_PROJECTION_RESULT_CODE, grant.resultCode)
                    putExtra(EXTRA_PROJECTION_DATA, grant.data)
                    putExtra(EXTRA_REQUEST_INTERNAL_AUDIO, grant.requestInternalAudio)
                }
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun restoreNow(
            context: Context,
            source: String = RESTORE_SOURCE_MANUAL
        ) {
            val intent = Intent(context, DpiGameSessionService::class.java).apply {
                action = ACTION_RESTORE_NOW
                putExtra(EXTRA_RESTORE_SOURCE, source)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun recover(context: Context) {
            val intent = Intent(context, DpiGameSessionService::class.java).apply {
                action = ACTION_RECOVER_SESSION
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

private data class BatterySnapshot(
    val percent: Int? = null,
    val temperatureCelsius: Float? = null
)
