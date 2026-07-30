package com.zaid.densityreset.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
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
        val userServiceConnected: Boolean
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
    private const val USER_SERVICE_TAG = "density-reset-user-service-v2"
    private const val USER_SERVICE_VERSION = 2
    private const val SERVICE_CONNECT_TIMEOUT_MILLIS = 20_000L
    private const val SERVICE_CONNECT_POLL_MILLIS = 100L
    private const val BIND_ATTEMPT_TIMEOUT_MILLIS = 10_000L
    private const val RECONNECT_DELAY_MILLIS = 1_500L

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
    private var lastConnectionError: String = ""

    private lateinit var userServiceArgs: Shizuku.UserServiceArgs

    private val serviceDeathRecipient = IBinder.DeathRecipient {
        mainHandler.post {
            markDisconnected("El proceso privilegiado terminó inesperadamente.")
            scheduleReconnect()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            bindingInProgress.set(false)

            if (!binder.pingBinder()) {
                markDisconnected("Shizuku devolvió un Binder no válido.")
                scheduleReconnect()
                return
            }

            clearConnectedService()
            connectedBinder = binder
            privilegedService = IPrivilegedDensityService.Stub.asInterface(binder)
            lastConnectionError = ""
            runCatching { binder.linkToDeath(serviceDeathRecipient, 0) }
            publishState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            bindingInProgress.set(false)
            markDisconnected("El UserService se desconectó.")
            scheduleReconnect()
        }

        override fun onBindingDied(name: ComponentName) {
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            bindingInProgress.set(false)
            markDisconnected("Android invalidó la conexión del UserService.")
            scheduleReconnect()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        bindingInProgress.set(false)
        lastConnectionError = ""
        publishState()
        mainHandler.postDelayed({ bindUserServiceIfPossible() }, 250L)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        mainHandler.removeCallbacks(bindTimeoutRunnable)
        bindingInProgress.set(false)
        markDisconnected("Shizuku se detuvo o reinició.")
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                publishState()
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    mainHandler.postDelayed({ bindUserServiceIfPossible(force = true) }, 250L)
                }
            }
        }

    private val bindTimeoutRunnable = Runnable {
        if (!bindingInProgress.compareAndSet(true, false)) return@Runnable
        if (isConnected()) return@Runnable

        lastConnectionError =
            "Shizuku no respondió al enlace del UserService en ${BIND_ATTEMPT_TIMEOUT_MILLIS / 1_000} segundos."
        runCatching {
            Shizuku.unbindUserService(userServiceArgs, serviceConnection, false)
        }
        clearConnectedService()
        publishState()
        scheduleReconnect()
    }

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(initializationLock) {
            if (initialized) return

            applicationContext = context.applicationContext
            userServiceArgs = Shizuku.UserServiceArgs(
                ComponentName(
                    context.packageName,
                    PrivilegedDensityService::class.java.name
                )
            )
                .daemon(false)
                .processNameSuffix("density_reset")
                .debuggable(false)
                .version(USER_SERVICE_VERSION)
                .tag(USER_SERVICE_TAG)

            initialized = true
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }

        publishState()
        mainHandler.postDelayed({ bindUserServiceIfPossible() }, 250L)
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
        bindUserServiceIfPossible()
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
            userServiceConnected = permissionGranted && isConnected()
        )
    }

    fun requestPermission(): String {
        val context = applicationContext ?: return "Density Reset no está inicializado."
        val state = currentState()

        if (!state.installed) return context.getString(R.string.shizuku_not_installed)
        if (!state.running) return context.getString(R.string.shizuku_not_running)
        if (state.permissionGranted) {
            bindUserServiceIfPossible(force = true)
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
                stderr = "La versión instalada de Shizuku no admite UserService. Actualiza Shizuku."
            )
        }

        if (!state.userServiceConnected) {
            mainHandler.post { bindUserServiceIfPossible(force = true) }
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
            val detail = lastConnectionError.ifBlank {
                "Shizuku no entregó la conexión del UserService. Abre Shizuku, confirma que siga iniciado y vuelve a intentar."
            }
            return ResetResult(
                false,
                context.getString(R.string.user_service_not_connected),
                stderr = detail
            )
        }

        return try {
            parseRemoteResult(context, service.resetDensity())
        } catch (exception: RemoteException) {
            mainHandler.post {
                markDisconnected(exception.message ?: "La llamada Binder fue interrumpida.")
                scheduleReconnect()
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

    private fun bindUserServiceIfPossible(force: Boolean = false) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { bindUserServiceIfPossible(force) }
            return
        }
        if (!initialized) return

        val state = currentState()
        if (!state.running || !state.permissionGranted || state.userServiceConnected) return

        if (force) {
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            bindingInProgress.set(false)
            runCatching {
                Shizuku.unbindUserService(userServiceArgs, serviceConnection, false)
            }
        }

        if (!bindingInProgress.compareAndSet(false, true)) return

        lastConnectionError = ""
        try {
            if (Shizuku.isPreV11()) {
                throw IllegalStateException("Shizuku anterior a la API 11 no admite UserService.")
            }
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
            mainHandler.removeCallbacks(bindTimeoutRunnable)
            mainHandler.postDelayed(bindTimeoutRunnable, BIND_ATTEMPT_TIMEOUT_MILLIS)
        } catch (throwable: Throwable) {
            bindingInProgress.set(false)
            lastConnectionError = buildString {
                append(throwable.javaClass.simpleName)
                throwable.message?.takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it)
                }
            }
            clearConnectedService()
            publishState()
        }
    }

    private fun scheduleReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable)
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MILLIS)
    }

    private val reconnectRunnable = Runnable {
        val state = currentState()
        if (state.running && state.permissionGranted && !state.userServiceConnected) {
            bindUserServiceIfPossible(force = true)
        }
    }

    private fun markDisconnected(reason: String) {
        bindingInProgress.set(false)
        lastConnectionError = reason
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

    private fun isShizukuInstalled(context: Context): Boolean =
        try {
            context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

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
}
