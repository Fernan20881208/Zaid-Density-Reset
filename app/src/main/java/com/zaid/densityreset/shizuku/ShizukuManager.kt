package com.zaid.densityreset.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import com.zaid.densityreset.BuildConfig
import com.zaid.densityreset.IPrivilegedDensityService
import com.zaid.densityreset.R
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ShizukuManager {

    data class State(
        val installed: Boolean,
        val running: Boolean,
        val permissionGranted: Boolean,
        val userServiceConnected: Boolean,
        val bindingInProgress: Boolean,
        val shizukuAppVersion: String?,
        val shizukuApiVersion: Int?,
        val shizukuUid: Int?,
        val peekedServiceVersion: Int?,
        val connectionDetail: String
    )

    data class ResetResult(
        val success: Boolean,
        val message: String,
        val exitCode: Int? = null,
        val stdout: String = "",
        val stderr: String = ""
    )

    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val PERMISSION_REQUEST_CODE = 7104
    private const val USER_SERVICE_TAG = "density-reset-user-service-v3"
    private const val USER_SERVICE_VERSION = 3
    private const val SERVICE_CONNECT_TIMEOUT_MILLIS = 20_000L
    private const val SERVICE_CONNECT_POLL_MILLIS = 100L
    private const val BIND_CALLBACK_TIMEOUT_MILLIS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateListeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val operationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "density-reset-client")
    }
    private val operationInProgress = AtomicBoolean(false)
    private val bindingInProgress = AtomicBoolean(false)
    private val initializationLock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var privilegedService: IPrivilegedDensityService? = null

    @Volatile
    private var connectedBinder: IBinder? = null

    @Volatile
    private var lastConnectionDetail: String = "Esperando al Binder de Shizuku."

    @Volatile
    private var lastPeekedServiceVersion: Int? = null

    private lateinit var userServiceArgs: Shizuku.UserServiceArgs

    private val serviceDeathRecipient: IBinder.DeathRecipient = IBinder.DeathRecipient {
        mainHandler.post {
            markDisconnected("El proceso privilegiado terminó inesperadamente.")
        }
    }

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            bindingInProgress.set(false)

            if (!binder.pingBinder()) {
                markDisconnected("Shizuku devolvió un Binder de UserService no válido.")
                return
            }

            clearConnectedService()
            connectedBinder = binder
            privilegedService = IPrivilegedDensityService.Stub.asInterface(binder)
            lastPeekedServiceVersion = USER_SERVICE_VERSION
            lastConnectionDetail =
                "onServiceConnected recibido: ${name.className.substringAfterLast('.')}"
            runCatching { binder.linkToDeath(serviceDeathRecipient, 0) }
            publishState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            bindingInProgress.set(false)
            markDisconnected(
                "onServiceDisconnected recibido para ${name.className.substringAfterLast('.')}"
            )
        }

        override fun onBindingDied(name: ComponentName) {
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            bindingInProgress.set(false)
            markDisconnected(
                "Android invalidó el enlace de ${name.className.substringAfterLast('.')}"
            )
        }
    }

    private val binderReceivedListener: Shizuku.OnBinderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            lastConnectionDetail = "Binder principal de Shizuku recibido."
            publishState()
            mainHandler.postDelayed({ connectUserServiceIfPossible() }, 250L)
        }

    private val binderDeadListener: Shizuku.OnBinderDeadListener =
        Shizuku.OnBinderDeadListener {
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            bindingInProgress.set(false)
            markDisconnected("El Binder principal de Shizuku murió o fue reiniciado.")
        }

    private val permissionResultListener: Shizuku.OnRequestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    lastConnectionDetail = "Permiso concedido; preparando UserService."
                    publishState()
                    mainHandler.postDelayed({ connectUserServiceIfPossible() }, 250L)
                } else {
                    lastConnectionDetail = "Shizuku rechazó el permiso solicitado."
                    publishState()
                }
            }
        }

    private val bindTimeoutRunnable: Runnable = Runnable {
        if (!bindingInProgress.compareAndSet(true, false)) return@Runnable
        if (isConnected()) return@Runnable

        val peekResult = peekUserServiceSafely()
        lastPeekedServiceVersion = peekResult
        lastConnectionDetail = if (peekResult != null && peekResult >= 0) {
            "El UserService existe (versión $peekResult), pero Shizuku no entregó onServiceConnected en ${BIND_CALLBACK_TIMEOUT_MILLIS / 1_000} s."
        } else {
            "Shizuku no creó ni conectó el UserService en ${BIND_CALLBACK_TIMEOUT_MILLIS / 1_000} s."
        }
        publishState()
    }

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(initializationLock) {
            if (initialized) return

            applicationContext = context.applicationContext
            userServiceArgs = Shizuku.UserServiceArgs(
                ComponentName(
                    BuildConfig.APPLICATION_ID,
                    PrivilegedDensityService::class.java.name
                )
            )
                .daemon(false)
                .processNameSuffix("density_reset_service")
                .debuggable(BuildConfig.DEBUG)
                .version(USER_SERVICE_VERSION)
                .tag(USER_SERVICE_TAG)

            initialized = true
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }

        publishState()
        mainHandler.postDelayed({ connectUserServiceIfPossible() }, 250L)
    }

    fun addStateListener(listener: (State) -> Unit) {
        stateListeners.add(listener)
        mainHandler.post { listener(currentState()) }
    }

    fun removeStateListener(listener: (State) -> Unit) {
        stateListeners.remove(listener)
    }

    fun refresh() {
        publishState()
        connectUserServiceIfPossible()
    }

    fun currentState(): State {
        val context = applicationContext
        val installed = context?.let(::isShizukuInstalled) == true
        val running = installed && isShizukuBinderAvailable()
        val permissionGranted = running && hasShizukuPermission()

        return State(
            installed = installed,
            running = running,
            permissionGranted = permissionGranted,
            userServiceConnected = permissionGranted && isConnected(),
            bindingInProgress = bindingInProgress.get(),
            shizukuAppVersion = context?.let(::getShizukuAppVersion),
            shizukuApiVersion = if (running) getShizukuApiVersionSafely() else null,
            shizukuUid = if (running) getShizukuUidSafely() else null,
            peekedServiceVersion = lastPeekedServiceVersion,
            connectionDetail = lastConnectionDetail
        )
    }

    fun requestPermission(): String {
        val context = applicationContext ?: return "Density Reset no está inicializado."
        val state = currentState()

        if (!state.installed) return context.getString(R.string.shizuku_not_installed)
        if (!state.running) return context.getString(R.string.shizuku_not_running)
        if (state.permissionGranted) {
            connectUserServiceIfPossible()
            return context.getString(R.string.permission_already_granted)
        }

        return try {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                context.getString(R.string.permission_denied_rationale)
            } else {
                Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
                context.getString(R.string.permission_request_sent)
            }
        } catch (throwable: Throwable) {
            context.getString(
                R.string.unexpected_error,
                throwable.message ?: throwable.javaClass.simpleName
            )
        }
    }

    fun reconnectUserService(): String {
        val context = applicationContext ?: return "Density Reset no está inicializado."
        val state = currentState()
        if (!state.running) return context.getString(R.string.shizuku_not_running)
        if (!state.permissionGranted) return context.getString(R.string.shizuku_permission_missing)

        mainHandler.post {
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            bindingInProgress.set(false)
            lastPeekedServiceVersion = null
            lastConnectionDetail = "Eliminando el enlace anterior antes de reconectar."
            publishState()

            runCatching {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
            }.onFailure { throwable ->
                lastConnectionDetail = formatThrowable("Error al limpiar el enlace", throwable)
                publishState()
            }

            clearConnectedService()
            mainHandler.postDelayed({ connectUserServiceIfPossible(skipPeek = true) }, 750L)
        }

        return context.getString(R.string.user_service_reconnect_started)
    }

    fun openShizuku(): Boolean {
        val context = applicationContext ?: return false
        val launchIntent = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    fun resetDensity(callback: (ResetResult) -> Unit) {
        val context = applicationContext
        if (context == null) {
            callbackOnMain(
                callback,
                ResetResult(false, "Density Reset no está inicializado.")
            )
            return
        }

        if (!operationInProgress.compareAndSet(false, true)) {
            callbackOnMain(
                callback,
                ResetResult(false, context.getString(R.string.operation_in_progress))
            )
            return
        }

        operationExecutor.execute {
            val result = try {
                executeResetWithConnectionWait(context)
            } catch (throwable: Throwable) {
                ResetResult(
                    success = false,
                    message = context.getString(
                        R.string.unexpected_error,
                        throwable.message ?: throwable.javaClass.simpleName
                    )
                )
            } finally {
                operationInProgress.set(false)
            }
            callbackOnMain(callback, result)
            publishState()
        }
    }

    private fun executeResetWithConnectionWait(context: Context): ResetResult {
        var state = currentState()
        if (!state.installed) {
            return ResetResult(false, context.getString(R.string.shizuku_not_installed))
        }
        if (!state.running) {
            return ResetResult(false, context.getString(R.string.shizuku_not_running))
        }
        if (!state.permissionGranted) {
            return ResetResult(false, context.getString(R.string.shizuku_permission_missing))
        }

        if (runCatching { Shizuku.isPreV11() }.getOrDefault(true)) {
            return ResetResult(
                false,
                context.getString(R.string.user_service_not_connected),
                stderr = "La versión instalada de Shizuku no admite UserService."
            )
        }

        if (!state.userServiceConnected) {
            mainHandler.post { connectUserServiceIfPossible() }
            val deadline = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(SERVICE_CONNECT_TIMEOUT_MILLIS)

            while (System.nanoTime() < deadline) {
                if (isConnected()) break
                Thread.sleep(SERVICE_CONNECT_POLL_MILLIS)
                state = currentState()
                if (!state.running || !state.permissionGranted) break
            }
        }

        val service = privilegedService
        val binder = connectedBinder
        if (service == null || binder?.pingBinder() != true) {
            val stateAfterWait = currentState()
            return ResetResult(
                false,
                context.getString(R.string.user_service_not_connected),
                stderr = buildDiagnosticText(stateAfterWait)
            )
        }

        return try {
            parseRemoteResult(context, service.resetDensity())
        } catch (exception: RemoteException) {
            mainHandler.post {
                markDisconnected(exception.message ?: "La llamada Binder fue interrumpida.")
            }
            ResetResult(
                success = false,
                message = context.getString(R.string.user_service_not_connected),
                stderr = exception.message.orEmpty()
            )
        } catch (throwable: Throwable) {
            ResetResult(
                success = false,
                message = context.getString(
                    R.string.unexpected_error,
                    throwable.message ?: throwable.javaClass.simpleName
                )
            )
        }
    }

    fun buildDiagnosticText(state: State = currentState()): String = buildString {
        append("Detalle: ")
        append(state.connectionDetail)
        append('\n')
        append("Shizuku app: ")
        append(state.shizukuAppVersion ?: "desconocida")
        append(" | API: ")
        append(state.shizukuApiVersion ?: "?")
        append(" | UID: ")
        append(state.shizukuUid ?: "?")
        append('\n')
        append("Enlazando: ")
        append(if (state.bindingInProgress) "sí" else "no")
        append(" | peek: ")
        append(state.peekedServiceVersion?.toString() ?: "sin resultado")
    }

    private fun parseRemoteResult(context: Context, rawResult: String?): ResetResult {
        if (rawResult.isNullOrBlank()) {
            return ResetResult(false, context.getString(R.string.user_service_not_connected))
        }

        val json = JSONObject(rawResult)
        val success = json.optBoolean("success", false)
        val exitCode = json.optInt("exitCode", -1)
        val stdout = json.optString("stdout", "")
        val stderr = json.optString("stderr", "")
        val timedOut = json.optBoolean("timedOut", false)

        val message = when {
            success -> context.getString(R.string.density_reset_success)
            timedOut -> context.getString(R.string.command_timeout)
            else -> context.getString(R.string.command_error_with_code, exitCode)
        }

        return ResetResult(
            success = success,
            message = message,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr
        )
    }

    private fun connectUserServiceIfPossible(skipPeek: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { connectUserServiceIfPossible(skipPeek) }
            return
        }
        if (!initialized || isConnected()) return

        val state = currentState()
        if (!state.running || !state.permissionGranted) return
        if (!bindingInProgress.compareAndSet(false, true)) return

        lastConnectionDetail = "Preparando solicitud de UserService."
        publishState()

        try {
            if (Shizuku.isPreV11()) {
                throw IllegalStateException("Shizuku anterior a API 11 no admite UserService.")
            }

            if (!skipPeek && getShizukuApiVersionSafely()?.let { it >= 12 } == true) {
                val peekVersion = Shizuku.peekUserService(userServiceArgs, serviceConnection)
                lastPeekedServiceVersion = peekVersion
                if (peekVersion >= 0) {
                    lastConnectionDetail =
                        "UserService existente detectado (versión $peekVersion); esperando Binder."
                    scheduleBindTimeout()
                    publishState()
                    return
                }
            }

            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            lastConnectionDetail = "Solicitud bindUserService enviada; esperando callback."
            scheduleBindTimeout()
            publishState()
        } catch (throwable: Throwable) {
            bindingInProgress.set(false)
            lastConnectionDetail = formatThrowable("bindUserService falló", throwable)
            clearConnectedService()
            publishState()
        }
    }

    private fun scheduleBindTimeout() {
        mainHandler.removeCallbacks(bindTimeoutRunnable)
        mainHandler.postDelayed(bindTimeoutRunnable, BIND_CALLBACK_TIMEOUT_MILLIS)
    }

    private fun peekUserServiceSafely(): Int? {
        if (getShizukuApiVersionSafely()?.let { it >= 12 } != true) return null
        return runCatching {
            Shizuku.peekUserService(userServiceArgs, serviceConnection)
        }.getOrNull()
    }

    private fun markDisconnected(reason: String) {
        bindingInProgress.set(false)
        lastConnectionDetail = reason
        clearConnectedService()
        publishState()
    }

    private fun isConnected(): Boolean =
        privilegedService != null && connectedBinder?.pingBinder() == true

    private fun clearConnectedService() {
        val binder = connectedBinder
        connectedBinder = null
        privilegedService = null
        if (binder != null) {
            runCatching { binder.unlinkToDeath(serviceDeathRecipient, 0) }
        }
    }

    private fun publishState() {
        if (!initialized) return
        val state = currentState()
        mainHandler.post {
            stateListeners.forEach { listener ->
                runCatching { listener(state) }
            }
        }
    }

    private fun callbackOnMain(
        callback: (ResetResult) -> Unit,
        result: ResetResult
    ) {
        mainHandler.post { callback(result) }
    }

    private fun formatThrowable(prefix: String, throwable: Throwable): String = buildString {
        append(prefix)
        append(": ")
        append(throwable.javaClass.simpleName)
        throwable.message?.takeIf { it.isNotBlank() }?.let {
            append(" — ")
            append(it)
        }
    }

    private fun isShizukuInstalled(context: Context): Boolean =
        try {
            context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    private fun getShizukuAppVersion(context: Context): String? =
        runCatching {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    SHIZUKU_PACKAGE,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            }
            packageInfo.versionName
        }.getOrNull()

    private fun isShizukuBinderAvailable(): Boolean =
        try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

    private fun hasShizukuPermission(): Boolean =
        try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

    private fun getShizukuApiVersionSafely(): Int? =
        runCatching { Shizuku.getVersion() }.getOrNull()

    private fun getShizukuUidSafely(): Int? =
        runCatching { Shizuku.getUid() }.getOrNull()
}
