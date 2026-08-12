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

/**
 * Applies the selected game DPI, verifies the real WindowManager state and keeps
 * that override for exactly [SESSION_DURATION_MILLIS]. At the deadline the
 * service executes `/system/bin/wm density reset` through Shizuku and verifies
 * that the override disappeared.
 *
 * The timer is absolute (restoreAt is persisted), so a service restart does not
 * extend the 20-second window. Leaving the game never restores early; only the
 * timer or an explicit manual/emergency restore can do that.
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
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private var operationJob: Job? = null
    private var timerJob: Job? = null
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground(
            buildNotification(
                title = getString(R.string.game_session_preparing),
                text = getString(R.string.game_session_checking_state)
            )
        )

        when (intent?.action) {
            ACTION_START_SESSION -> {
                val game = SupportedGame.fromPackageName(
                    intent.getStringExtra(EXTRA_GAME_PACKAGE)
                )
                val preset = intent.getStringExtra(EXTRA_PRESET)
                    ?.let { runCatching { DensityPreset.valueOf(it) }.getOrNull() }

                if (game == null || preset == null) {
                    launchOperation {
                        repository.failAndClear(
                            getString(R.string.game_session_invalid_configuration)
                        )
                        DpiGameLockBridge.notifySessionChanged()
                        stopServiceCleanly()
                    }
                } else {
                    launchOperation { startSessionFlow(game, preset) }
                }
            }

            ACTION_RESTORE_NOW -> {
                val source = intent.getStringExtra(EXTRA_RESTORE_SOURCE)
                    ?: RESTORE_SOURCE_MANUAL

                // Accessibility may report that the game left foreground. That
                // must not shorten the requested fixed 20-second sensitivity.
                if (source == RESTORE_SOURCE_GAME_EXIT) {
                    if (operationJob?.isActive != true) {
                        launchOperation(cancelTimer = false) { recoverPendingSession() }
                    }
                } else {
                    launchOperation(cancelTimer = true) {
                        restoreToPhysicalDensity(source)
                    }
                }
            }

            ACTION_RECOVER_SESSION, null -> {
                launchOperation(cancelTimer = false) { recoverPendingSession() }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        timerJob?.cancel()
        operationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun launchOperation(
        cancelTimer: Boolean = false,
        block: suspend () -> Unit
    ) {
        operationJob?.cancel()
        if (cancelTimer) timerJob?.cancel()
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
        preset: DensityPreset
    ) {
        val existing = repository.read()
        if (existing.sessionActive) {
            recoverPendingSession()
            return
        }

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

        val snapshot = DensitySnapshot(
            physicalDensity = originalState.initialDensity,
            effectiveDensity = originalState.currentDensity,
            hadOverride = originalState.hasOverride,
            previousOverrideDensity = originalState.currentDensity
                .takeIf { originalState.hasOverride }
        )

        repository.beginSession(
            game = game,
            preset = preset,
            snapshot = snapshot,
            startedAt = System.currentTimeMillis()
        )

        repository.updateStep(SessionStep.CLOSING_GAME)
        updatePreparingNotification(game, preset, "Cerrando el juego")
        gameController.forceStop(game).getOrElse {
            failWithoutRestoration("No se pudo reiniciar el juego.")
            return
        }

        repository.updateStep(SessionStep.APPLYING_DENSITY)
        updatePreparingNotification(game, preset, "Aplicando ${preset.density} DPI")

        densityController.applyDensity(preset.density).getOrElse { error ->
            abortAndReset(
                error.message ?: "El dispositivo no confirmó el DPI seleccionado."
            )
            return
        }

        repository.updateStep(SessionStep.VERIFYING_DENSITY)
        updatePreparingNotification(game, preset, "Verificando DPI real")
        delay(DENSITY_SETTLE_MILLIS)

        val verified = densityController.getSystemState().getOrElse {
            abortAndReset("El dispositivo no confirmó el DPI seleccionado.")
            return
        }
        if (!verified.hasOverride || verified.currentDensity != preset.density) {
            abortAndReset(
                "El comando no quedó aplicado: se esperaba ${preset.density} DPI y WindowManager reportó ${verified.currentDensity} DPI."
            )
            return
        }

        // The 20-second deadline starts only after WindowManager has confirmed
        // the requested density. It is persisted as an absolute timestamp.
        val restoreAt = System.currentTimeMillis() + SESSION_DURATION_MILLIS
        repository.markSessionActive(restoreAt = restoreAt)
        DpiGameLockBridge.notifySessionChanged()
        updateActiveNotification(game, preset, seconds = SESSION_DURATION_SECONDS)
        scheduleResetAt(restoreAt)

        repository.updateStep(SessionStep.OPENING_GAME)
        gameController.launch(game).getOrElse {
            abortAndReset("No se pudo abrir el juego. Se restaurará el DPI.")
            return
        }

        // Return to SESSION_ACTIVE after launch so the UI can display the
        // countdown. The absolute restoreAt is not changed.
        repository.markSessionActive(restoreAt = restoreAt)
        DpiGameLockBridge.notifySessionChanged()
        updateActiveNotification(game, preset, secondsRemaining(repository.read()))
    }

    private suspend fun recoverPendingSession() {
        val session = repository.read()
        if (!session.sessionActive) {
            stopServiceCleanly()
            return
        }

        val restoreAt = session.restoreAt
        if (restoreAt == null || restoreAt <= System.currentTimeMillis()) {
            restoreToPhysicalDensity(RESTORE_SOURCE_RECOVERY)
            return
        }

        val target = session.targetDensity
        if (target != null) {
            val state = densityController.getSystemState().getOrNull()
            if (state == null || !state.hasOverride || state.currentDensity != target) {
                // Do not silently extend the timer. Try to re-apply the selected
                // density, but keep the original absolute deadline.
                densityController.applyDensity(target).getOrElse {
                    restoreToPhysicalDensity(RESTORE_SOURCE_RECOVERY)
                    return
                }
                val reverified = densityController.getSystemState().getOrNull()
                if (reverified == null || reverified.currentDensity != target) {
                    restoreToPhysicalDensity(RESTORE_SOURCE_RECOVERY)
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
                restoreToPhysicalDensity(RESTORE_SOURCE_TIMER)
            }
        }
    }

    private suspend fun abortAndReset(message: String) {
        timerJob?.cancel()
        val result = executeWmDensityReset()
        if (result.isSuccess) {
            repository.failAndClear(message)
            DpiGameLockBridge.notifySessionChanged()
            showToast(message)
            stopServiceCleanly()
        } else {
            val failure = result.exceptionOrNull()?.message
                ?: getString(R.string.game_session_restore_failed)
            repository.markRestorationFailure(failure)
            DpiGameLockBridge.notifySessionChanged()
            updateSessionNotification(repository.read(), null)
            showToast(failure)
        }
    }

    private suspend fun restoreToPhysicalDensity(source: String) {
        val session = repository.read()
        if (!session.sessionActive) {
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

        val result = executeWmDensityReset()
        result.onSuccess {
            repository.finishSession(getString(R.string.dpi_restored_successfully))
            DpiGameLockBridge.notifySessionChanged()
            if (source != RESTORE_SOURCE_TIMER && source != RESTORE_SOURCE_RECOVERY) {
                showToast(getString(R.string.dpi_restored_successfully))
            }
            stopServiceCleanly()
        }.onFailure { error ->
            val message = error.message ?: getString(R.string.game_session_restore_failed)
            repository.markRestorationFailure(message)
            DpiGameLockBridge.notifySessionChanged()
            updateSessionNotification(repository.read(), null)
            showToast(message)
        }
    }

    /**
     * The primary reset is deliberately the exact shell command requested by
     * the product flow. Binder is not used as the normal reset path here.
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
        repository.failAndClear(message)
        DpiGameLockBridge.notifySessionChanged()
        showToast(message)
        stopServiceCleanly()
    }

    private suspend fun handleUnexpectedFailure(error: Throwable) {
        val session = repository.read()
        val message = error.message ?: getString(R.string.game_session_unexpected_error)
        if (session.sessionActive) {
            abortAndReset(message)
        } else {
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
                "${game.displayName} · ${preset.displayName} · $detail"
            )
        )
    }

    private fun updateActiveNotification(
        game: SupportedGame,
        preset: DensityPreset,
        seconds: Int
    ) {
        val text = "${game.displayName} · ${preset.displayName} · ${preset.density} DPI · wm density reset en $seconds s"
        notificationManager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification_density)
                .setContentTitle(getString(R.string.game_session_active))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openAppPendingIntent())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(
                    SESSION_DURATION_SECONDS,
                    seconds.coerceIn(0, SESSION_DURATION_SECONDS),
                    false
                )
                .addAction(
                    0,
                    getString(R.string.restore_now),
                    restorePendingIntent(RESTORE_SOURCE_NOTIFICATION)
                )
                .build()
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
                    session.errorMessage ?: getString(R.string.game_session_checking_state)
                )
            )
            return
        }

        when (session.currentStep) {
            SessionStep.RESTORING_DENSITY -> notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    getString(R.string.game_session_restoring),
                    "${game.displayName} · Ejecutando wm density reset"
                )
            )
            SessionStep.ERROR -> notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(
                    getString(R.string.game_session_attention),
                    session.errorMessage ?: getString(R.string.game_session_restore_failed)
                )
            )
            else -> updateActiveNotification(game, preset, seconds ?: secondsRemaining(session))
        }
    }

    private fun buildNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_density)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun ensureForeground(notification: Notification) {
        if (foregroundStarted) return
        val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
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
                description = getString(R.string.game_session_channel_description)
                setShowBadge(false)
            }
        )
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun stopServiceCleanly() {
        timerJob?.cancel()
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
        private const val EXTRA_RESTORE_SOURCE = "extra_restore_source"

        const val RESTORE_SOURCE_VOLUME = "volume_gesture"
        const val RESTORE_SOURCE_GAME_EXIT = "game_exit"
        private const val RESTORE_SOURCE_MANUAL = "manual"
        private const val RESTORE_SOURCE_NOTIFICATION = "notification"
        private const val RESTORE_SOURCE_TIMER = "timer"
        private const val RESTORE_SOURCE_RECOVERY = "recovery"

        private const val NOTIFICATION_CHANNEL_ID = "dpi_game_session"
        private const val NOTIFICATION_ID = 4102
        private const val REQUEST_OPEN_APP = 4103
        private const val REQUEST_RESTORE = 4104

        const val SESSION_DURATION_SECONDS = 20
        private const val SESSION_DURATION_MILLIS = 20_000L
        private const val COUNTDOWN_UPDATE_MILLIS = 1_000L
        private const val DENSITY_SETTLE_MILLIS = 350L

        fun startSession(
            context: Context,
            game: SupportedGame,
            preset: DensityPreset
        ) {
            val intent = Intent(context, DpiGameSessionService::class.java).apply {
                action = ACTION_START_SESSION
                putExtra(EXTRA_GAME_PACKAGE, game.packageName)
                putExtra(EXTRA_PRESET, preset.name)
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
