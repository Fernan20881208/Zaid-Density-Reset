package com.zaid.densityreset.gameprofile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zaid.densityreset.R
import com.zaid.densityreset.accessibility.DpiGameLockBridge
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
import com.zaid.densityreset.gameprofile.domain.GameSessionState
import com.zaid.densityreset.gameprofile.domain.SessionStep
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import com.zaid.densityreset.gameprofile.shizuku.ShizukuCommandExecutor
import com.zaid.densityreset.gameprofile.shizuku.ShizukuGameController
import com.zaid.densityreset.icons.DensityIconInvalidationCoordinator
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
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private var operationJob: Job? = null
    private var timerJob: Job? = null
    private var gameWatchJob: Job? = null
    private var boosterStateJob: Job? = null
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
        ensureForeground(
            buildNotification(
                title = getString(R.string.game_session_preparing),
                text = getString(R.string.game_session_checking_state),
                includeRestore = false
            )
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
                        startSessionFlow(game, preset, boosterMode)
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
        boosterMode: BoosterMode?
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

        repository.markSessionActive(restoreAt)
        DpiGameLockBridge.notifySessionChanged()
        startGameWatcher(game)
        updateSessionNotification(repository.read(), secondsRemaining(repository.read()))
    }

    private suspend fun recoverPendingSession() {
        val session = repository.read()
        if (!session.sessionActive) {
            if (boosterManager.hasSnapshot()) {
                boosterManager.restore()
            }
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

        val reset = executeWmDensityReset()
        if (reset.isFailure) {
            val boosterRestore = boosterManager.restore()
            val message = reset.exceptionOrNull()?.message
                ?: getString(R.string.game_session_restore_failed)
            repository.markRestorationFailure(
                if (boosterRestore is BoosterResult.Failure) {
                    "$message ${boosterRestore.message}"
                } else {
                    message
                }
            )
            DpiGameLockBridge.notifySessionChanged()
            updateSessionNotification(repository.read(), null)
            showToast(message)
            return
        }

        DpiGameLockBridge.notifySessionChanged()
        val hasBooster = boosterManager.hasSnapshot()
        if (hasBooster && !gameExitConfirmed) {
            repository.markBoosterActive()
            DpiGameLockBridge.notifySessionChanged()
            updateSessionNotification(repository.read(), null)
        } else {
            val boosterRestore = boosterManager.restore()
            if (boosterRestore is BoosterResult.Failure) {
                repository.markRestorationFailure(boosterRestore.message)
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

    private suspend fun handleConfirmedGameExit() {
        val session = repository.read()
        if (!session.sessionActive) return

        val boosterRestore = boosterManager.restore()
        if (boosterRestore is BoosterResult.Failure) {
            repository.markRestorationFailure(boosterRestore.message)
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
        val booster = boosterManager.restore()
        val density = executeWmDensityReset()
        if (booster !is BoosterResult.Failure && density.isSuccess) {
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

        val booster = boosterManager.restore()
        val density = executeWmDensityReset()
        if (booster !is BoosterResult.Failure && density.isSuccess) {
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
            }.ifBlank { getString(R.string.game_session_restore_failed) }
            repository.markRestorationFailure(message)
            DpiGameLockBridge.notifySessionChanged()
            updateSessionNotification(repository.read(), null)
            showToast(message)
        }
    }

    /**
     * The primary density restoration remains the literal command required by
     * the product: `/system/bin/wm density reset` through Shizuku, followed by
     * a real WindowManager state verification.
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

        val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_density)
            .setContentTitle(title)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(line))
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

    private fun ensureForeground(notification: Notification) {
        if (foregroundStarted) return
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
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
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    companion object {
        private const val ACTION_START_SESSION =
            "com.zaidnavarro.ds.action.START_GAME_DPI_SESSION"
        private const val ACTION_RESTORE_NOW =
            "com.zaidnavarro.ds.action.RESTORE_GAME_DPI_SESSION"
        private const val ACTION_RECOVER_SESSION =
            "com.zaidnavarro.ds.action.RECOVER_GAME_DPI_SESSION"
        private const val EXTRA_GAME_PACKAGE = "extra_game_package"
        private const val EXTRA_PRESET = "extra_density_preset"
        private const val EXTRA_BOOSTER_MODE = "extra_booster_mode"
        private const val EXTRA_RESTORE_SOURCE = "extra_restore_source"

        const val RESTORE_SOURCE_VOLUME = "volume_gesture"
        const val RESTORE_SOURCE_GAME_EXIT = "game_exit"
        private const val RESTORE_SOURCE_MANUAL = "manual"
        private const val RESTORE_SOURCE_NOTIFICATION = "notification"
        private const val RESTORE_SOURCE_RECOVERY = "recovery"

        private const val NOTIFICATION_CHANNEL_ID = "dpi_game_session"
        private const val NOTIFICATION_ID = 4102
        private const val REQUEST_OPEN_APP = 4103
        private const val REQUEST_RESTORE = 4104

        const val SESSION_DURATION_SECONDS = 20
        private const val SESSION_DURATION_MILLIS = 20_000L
        private const val COUNTDOWN_UPDATE_MILLIS = 1_000L
        private const val DENSITY_SETTLE_MILLIS = 350L
        private const val GAME_WATCH_START_DELAY_MILLIS = 4_000L
        private const val GAME_WATCH_INTERVAL_MILLIS = 1_500L
        private const val GAME_EXIT_CONFIRMATION_SAMPLES = 3
        private const val GAME_NOT_SEEN_CONFIRMATION_SAMPLES = 8
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
            boosterMode: BoosterMode? = null
        ) {
            val intent = Intent(context, DpiGameSessionService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra(EXTRA_GAME_PACKAGE, game.packageName)
                putExtra(EXTRA_PRESET, preset.name)
                boosterMode?.let { putExtra(EXTRA_BOOSTER_MODE, it.name) }
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
