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
import com.zaid.densityreset.MainActivity
import com.zaid.densityreset.R
import com.zaid.densityreset.density.DensityPreset
import com.zaid.densityreset.density.ShizukuDensityController
import com.zaid.densityreset.gameprofile.data.GameSessionRepository
import com.zaid.densityreset.gameprofile.data.GameSessionRepositoryImpl
import com.zaid.densityreset.gameprofile.domain.DensitySnapshot
import com.zaid.densityreset.gameprofile.domain.GameSessionState
import com.zaid.densityreset.gameprofile.domain.SessionStep
import com.zaid.densityreset.gameprofile.domain.SupportedGame
import com.zaid.densityreset.gameprofile.shizuku.ShizukuGameController
import com.zaid.densityreset.shizuku.ShizukuManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.ceil

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
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private var operationJob: Job? = null
    private var countdownJob: Job? = null
    private var foregroundStarted = false

    private val shizukuStateListener: (ShizukuManager.State) -> Unit = { state ->
        serviceScope.launch {
            val session = repository.read()
            if (!session.sessionActive) return@launch

            if (!state.running || !state.permissionGranted) {
                repository.updateStep(
                    step = session.currentStep,
                    errorMessage = "Shizuku se desconectó durante la sesión."
                )
                updateNotification(session, secondsRemaining(session))
            } else if (session.currentStep == SessionStep.ERROR) {
                requestRestore(RESTORE_SOURCE_RECOVERY)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ShizukuManager.addStateListener(shizukuStateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground(
            buildPreparingNotification(
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
                    ?.let { name ->
                        runCatching { DensityPreset.valueOf(name) }.getOrNull()
                    }
                if (game == null || preset == null) {
                    operationJob = serviceScope.launch {
                        repository.failAndClear(
                            getString(R.string.game_session_invalid_configuration)
                        )
                        stopServiceCleanly()
                    }
                } else {
                    startExclusiveOperation {
                        startSessionFlow(game, preset)
                    }
                }
            }

            ACTION_RESTORE_NOW -> requestRestore(
                intent.getStringExtra(EXTRA_RESTORE_SOURCE)
                    ?: RESTORE_SOURCE_MANUAL
            )

            ACTION_RECOVER_SESSION, null -> startExclusiveOperation {
                recoverPendingSession()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ShizukuManager.removeStateListener(shizukuStateListener)
        countdownJob?.cancel()
        operationJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startExclusiveOperation(block: suspend () -> Unit) {
        if (operationJob?.isActive == true) return
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

    private fun requestRestore(source: String) {
        val previousJob = operationJob
        previousJob?.cancel()
        countdownJob?.cancel()
        operationJob = serviceScope.launch {
            previousJob?.cancelAndJoin()
            restorePersistedSnapshot(source)
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
        updateNotification(
            game = game,
            preset = preset,
            step = SessionStep.VALIDATING,
            seconds = null
        )

        val shizuku = ShizukuManager.currentState()
        when {
            !gameController.isInstalled(game) -> {
                failWithoutRestoration("Este juego no está instalado.")
                return
            }
            !shizuku.installed -> {
                failWithoutRestoration("Shizuku no está instalado.")
                return
            }
            !shizuku.running -> {
                failWithoutRestoration("Shizuku no está ejecutándose.")
                return
            }
            !shizuku.permissionGranted -> {
                failWithoutRestoration("Permiso de Shizuku denegado.")
                return
            }
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
        updateNotification(game, preset, SessionStep.CLOSING_GAME, null)
        gameController.forceStop(game).getOrElse {
            failWithoutRestoration("No se pudo reiniciar el juego.")
            return
        }

        repository.updateStep(SessionStep.APPLYING_DENSITY)
        updateNotification(game, preset, SessionStep.APPLYING_DENSITY, null)
        densityController.applyDensity(preset.density).getOrElse { error ->
            abortAndRestore(
                error.message ?: "El dispositivo no confirmó el DPI seleccionado."
            )
            return
        }

        repository.updateStep(SessionStep.VERIFYING_DENSITY)
        updateNotification(game, preset, SessionStep.VERIFYING_DENSITY, null)
        delay(DENSITY_SETTLE_MILLIS)
        val verified = densityController.getSystemState().getOrElse {
            abortAndRestore("El dispositivo no confirmó el DPI seleccionado.")
            return
        }
        if (verified.currentDensity != preset.density) {
            abortAndRestore("El dispositivo no confirmó el DPI seleccionado.")
            return
        }

        repository.updateStep(SessionStep.OPENING_GAME)
        updateNotification(game, preset, SessionStep.OPENING_GAME, null)
        gameController.launch(game).getOrElse {
            abortAndRestore(
                "No se pudo abrir el juego. El DPI anterior será restaurado."
            )
            return
        }

        delay(GAME_LAUNCH_CONFIRMATION_MILLIS)
        val restoreAt = System.currentTimeMillis() + SESSION_DURATION_MILLIS
        repository.markSessionActive(restoreAt)
        startCountdown()
    }

    private suspend fun recoverPendingSession() {
        val session = repository.read()
        if (!session.sessionActive) {
            stopServiceCleanly()
            return
        }

        val restoreAt = session.restoreAt
        if (
            session.currentStep == SessionStep.SESSION_ACTIVE &&
            restoreAt != null &&
            restoreAt > System.currentTimeMillis()
        ) {
            startCountdown()
            return
        }

        restorePersistedSnapshot(RESTORE_SOURCE_RECOVERY)
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = serviceScope.launch {
            while (isActive) {
                val session = repository.read()
                if (!session.sessionActive) return@launch

                val seconds = secondsRemaining(session)
                updateNotification(session, seconds)
                if (seconds <= 0) {
                    requestRestore(RESTORE_SOURCE_TIMER)
                    return@launch
                }

                delay(COUNTDOWN_UPDATE_MILLIS)
            }
        }
    }

    private suspend fun abortAndRestore(errorMessage: String) {
        val restored = restoreSnapshotInternal()
        if (restored.isSuccess) {
            repository.failAndClear(errorMessage)
            showToast(errorMessage)
            stopServiceCleanly()
        } else {
            val message = getString(R.string.game_session_restore_failed)
            repository.markRestorationFailure(message)
            updateNotification(repository.read(), null)
            showToast(message)
        }
    }

    private suspend fun restorePersistedSnapshot(source: String) {
        val session = repository.read()
        if (!session.sessionActive) {
            stopServiceCleanly()
            return
        }

        repository.updateStep(SessionStep.RESTORING_DENSITY)
        updateNotification(
            session.copy(currentStep = SessionStep.RESTORING_DENSITY),
            null
        )

        val result = restoreSnapshotInternal()
        result.onSuccess {
            repository.finishSession(
                getString(R.string.dpi_restored_successfully)
            )
            if (source == RESTORE_SOURCE_VOLUME) {
                showToast(getString(R.string.dpi_restored_successfully))
            }
            stopServiceCleanly()
        }.onFailure {
            val message = getString(R.string.game_session_restore_failed)
            repository.markRestorationFailure(message)
            updateNotification(repository.read(), null)
            showToast(message)
        }
    }

    private suspend fun restoreSnapshotInternal(): Result<Unit> {
        val session = repository.read()
        val snapshot = session.snapshot
            ?: return Result.failure(
                IllegalStateException("No se encontró el snapshot de densidad.")
            )

        val restoreResult = if (
            snapshot.hadOverride &&
            snapshot.previousOverrideDensity != null
        ) {
            densityController.applyDensity(snapshot.previousOverrideDensity)
        } else {
            densityController.resetDensity()
        }
        restoreResult.getOrElse { return Result.failure(it) }

        delay(DENSITY_SETTLE_MILLIS)
        val verified = densityController.getSystemState().getOrElse {
            return Result.failure(it)
        }

        val restored = if (snapshot.hadOverride) {
            verified.hasOverride &&
                verified.currentDensity == snapshot.previousOverrideDensity
        } else {
            !verified.hasOverride &&
                verified.currentDensity == snapshot.physicalDensity
        }

        return if (restored) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException("No fue posible verificar la restauración del DPI.")
            )
        }
    }

    private suspend fun failWithoutRestoration(message: String) {
        repository.failAndClear(message)
        showToast(message)
        stopServiceCleanly()
    }

    private suspend fun handleUnexpectedFailure(error: Throwable) {
        val session = repository.read()
        val message = error.message ?: getString(R.string.game_session_unexpected_error)
        if (session.sessionActive && session.snapshot != null) {
            abortAndRestore(message)
        } else {
            repository.failAndClear(message)
            stopServiceCleanly()
        }
    }

    private fun secondsRemaining(session: GameSessionState): Int {
        val restoreAt = session.restoreAt ?: return 0
        return ceil(
            (restoreAt - System.currentTimeMillis()).coerceAtLeast(0L) / 1_000.0
        ).toInt()
    }

    private fun updateNotification(
        session: GameSessionState,
        seconds: Int?
    ) {
        val game = session.selectedGame
        val preset = session.selectedPreset
        if (game == null || preset == null) {
            notificationManager.notify(
                NOTIFICATION_ID,
                buildPreparingNotification(
                    getString(R.string.game_session_preparing),
                    session.errorMessage ?: getString(R.string.game_session_checking_state)
                )
            )
            return
        }
        updateNotification(game, preset, session.currentStep, seconds, session.errorMessage)
    }

    private fun updateNotification(
        game: SupportedGame,
        preset: DensityPreset,
        step: SessionStep,
        seconds: Int?,
        errorMessage: String? = null
    ) {
        val title = when (step) {
            SessionStep.SESSION_ACTIVE -> getString(R.string.game_session_active)
            SessionStep.RESTORING_DENSITY -> getString(R.string.game_session_restoring)
            SessionStep.ERROR -> getString(R.string.game_session_attention)
            else -> getString(R.string.game_session_preparing)
        }
        val base = "${game.displayName} · ${preset.displayName} · ${preset.density} DPI"
        val text = when {
            !errorMessage.isNullOrBlank() -> errorMessage
            step == SessionStep.SESSION_ACTIVE && seconds != null ->
                "$base · Restauración automática en $seconds s"
            else -> "$base · ${stepLabel(step)}"
        }

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
            .addAction(
                0,
                getString(R.string.restore_now),
                restorePendingIntent(RESTORE_SOURCE_NOTIFICATION)
            )

        if (step == SessionStep.SESSION_ACTIVE && seconds != null) {
            builder.setProgress(SESSION_DURATION_SECONDS, seconds, false)
        }
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun stepLabel(step: SessionStep): String = when (step) {
        SessionStep.VALIDATING -> "Validando requisitos"
        SessionStep.SAVING_DENSITY -> "Guardando DPI actual"
        SessionStep.CLOSING_GAME -> "Cerrando el juego"
        SessionStep.APPLYING_DENSITY -> "Aplicando DPI"
        SessionStep.VERIFYING_DENSITY -> "Verificando DPI"
        SessionStep.OPENING_GAME -> "Abriendo el juego"
        SessionStep.RESTORING_DENSITY -> "Restaurando DPI"
        SessionStep.ERROR -> "Requiere atención"
        else -> "Preparando sesión"
    }

    private fun buildPreparingNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_density)
            .setContentTitle(title)
            .setContentText(text)
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
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun restorePendingIntent(source: String): PendingIntent =
        PendingIntent.getService(
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
        countdownJob?.cancel()
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
        private const val RESTORE_SOURCE_MANUAL = "manual"
        private const val RESTORE_SOURCE_NOTIFICATION = "notification"
        private const val RESTORE_SOURCE_TIMER = "timer"
        private const val RESTORE_SOURCE_RECOVERY = "recovery"

        private const val NOTIFICATION_CHANNEL_ID = "dpi_game_session"
        private const val NOTIFICATION_ID = 4102
        private const val REQUEST_OPEN_APP = 4103
        private const val REQUEST_RESTORE = 4104

        private const val SESSION_DURATION_MILLIS = 30_000L
        private const val SESSION_DURATION_SECONDS = 30
        private const val COUNTDOWN_UPDATE_MILLIS = 1_000L
        private const val DENSITY_SETTLE_MILLIS = 500L
        private const val GAME_LAUNCH_CONFIRMATION_MILLIS = 1_500L

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
