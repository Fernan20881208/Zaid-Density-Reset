package com.zaid.densityreset.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.zaid.densityreset.R
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.Future
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
    private const val COMMAND_TIMEOUT_SECONDS = 15L
    private const val STREAM_READ_TIMEOUT_SECONDS = 2L
    private const val PROCESS_DESTROY_GRACE_SECONDS = 1L
    private const val EXIT_CODE_TIMEOUT = -2
    private const val EXIT_CODE_INTERNAL_ERROR = -1

    private val fixedCommand = arrayOf(
        "/system/bin/wm",
        "density",
        "reset"
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateListeners = CopyOnWriteArraySet<(State) -> Unit>()
    private val operationExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "density-reset-client")
    }
    private val streamExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "density-reset-stream")
    }
    private val operationInProgress = AtomicBoolean(false)
    private val initializationLock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var lastConnectionDetail: String = "Esperando al Binder principal de Shizuku."

    private val remoteProcessMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val stringArrayClass = arrayOf<String>().javaClass
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            stringArrayClass,
            stringArrayClass,
            String::class.java
        ).apply {
            isAccessible = true
        }
    }

    private val binderReceivedListener: Shizuku.OnBinderReceivedListener =
        Shizuku.OnBinderReceivedListener {
            lastConnectionDetail = if (hasShizukuPermission()) {
                "Canal directo disponible; no se usa UserService."
            } else {
                "Binder de Shizuku recibido; falta conceder permiso."
            }
            publishState()
        }

    private val binderDeadListener: Shizuku.OnBinderDeadListener =
        Shizuku.OnBinderDeadListener {
            lastConnectionDetail = "El Binder principal de Shizuku murió o fue reiniciado."
            publishState()
        }

    private val permissionResultListener: Shizuku.OnRequestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener

            lastConnectionDetail = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                "Permiso concedido; canal directo listo para ejecutar comandos."
            } else {
                "Shizuku rechazó el permiso solicitado."
            }
            publishState()
        }

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(initializationLock) {
            if (initialized) return

            applicationContext = context.applicationContext
            initialized = true
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }

        refresh()
    }

    fun addStateListener(listener: (State) -> Unit) {
        stateListeners.add(listener)
        mainHandler.post { listener(currentState()) }
    }

    fun removeStateListener(listener: (State) -> Unit) {
        stateListeners.remove(listener)
    }

    fun refresh() {
        val state = currentState()
        lastConnectionDetail = when {
            !state.installed -> "Shizuku no está instalado."
            !state.running -> "Shizuku está instalado, pero su servidor no está iniciado."
            !state.permissionGranted -> "Servidor disponible; falta conceder permiso a Density Reset."
            else -> "Canal directo disponible; no se usa UserService."
        }
        publishState()
    }

    fun currentState(): State {
        val context = applicationContext
        val installed = context?.let(::isShizukuInstalled) == true
        val running = installed && isShizukuBinderAvailable()
        val permissionGranted = running && hasShizukuPermission()
        val directChannelAvailable = permissionGranted

        return State(
            installed = installed,
            running = running,
            permissionGranted = permissionGranted,
            userServiceConnected = directChannelAvailable,
            bindingInProgress = false,
            shizukuAppVersion = context?.let(::getShizukuAppVersion),
            shizukuApiVersion = if (running) getShizukuApiVersionSafely() else null,
            shizukuUid = if (running) getShizukuUidSafely() else null,
            peekedServiceVersion = null,
            connectionDetail = lastConnectionDetail
        )
    }

    fun requestPermission(): String {
        val context = applicationContext ?: return "Density Reset no está inicializado."
        val state = currentState()

        if (!state.installed) return context.getString(R.string.shizuku_not_installed)
        if (!state.running) return context.getString(R.string.shizuku_not_running)
        if (state.permissionGranted) {
            refresh()
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

        lastConnectionDetail = "Canal directo de Shizuku actualizado y listo."
        publishState()
        return context.getString(R.string.direct_channel_refreshed)
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

        lastConnectionDetail = "Ejecutando wm density reset mediante el proceso remoto de Shizuku."
        publishState()

        operationExecutor.execute {
            val result = try {
                executeDirectReset(context)
            } catch (throwable: Throwable) {
                val detail = formatThrowable("Ejecución directa falló", unwrapReflectionError(throwable))
                lastConnectionDetail = detail
                ResetResult(
                    success = false,
                    message = context.getString(
                        R.string.unexpected_error,
                        unwrapReflectionError(throwable).message
                            ?: unwrapReflectionError(throwable).javaClass.simpleName
                    ),
                    exitCode = EXIT_CODE_INTERNAL_ERROR,
                    stderr = detail
                )
            } finally {
                operationInProgress.set(false)
            }

            callbackOnMain(callback, result)
            publishState()
        }
    }

    private fun executeDirectReset(context: Context): ResetResult {
        val state = currentState()
        if (!state.installed) {
            return ResetResult(false, context.getString(R.string.shizuku_not_installed))
        }
        if (!state.running) {
            return ResetResult(false, context.getString(R.string.shizuku_not_running))
        }
        if (!state.permissionGranted) {
            return ResetResult(false, context.getString(R.string.shizuku_permission_missing))
        }

        var process: Process? = null
        return try {
            val startedProcess = startRemoteProcess()
            process = startedProcess

            val stdoutFuture = streamExecutor.submit(Callable {
                startedProcess.inputStream.readFully()
            })
            val stderrFuture = streamExecutor.submit(Callable {
                startedProcess.errorStream.readFully()
            })

            val finished = startedProcess.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                terminateProcess(startedProcess)
                val stdout = readFutureSafely(stdoutFuture)
                val stderr = readFutureSafely(stderrFuture)
                lastConnectionDetail = "El proceso remoto excedió ${COMMAND_TIMEOUT_SECONDS} s y fue detenido."
                return ResetResult(
                    success = false,
                    message = context.getString(R.string.command_timeout),
                    exitCode = EXIT_CODE_TIMEOUT,
                    stdout = stdout,
                    stderr = stderr.ifBlank { lastConnectionDetail }
                )
            }

            val exitCode = startedProcess.exitValue()
            val stdout = readFutureSafely(stdoutFuture)
            val stderr = readFutureSafely(stderrFuture)
            val success = exitCode == 0

            lastConnectionDetail = if (success) {
                "Modo directo comprobado: wm density reset terminó con código 0."
            } else {
                "wm density reset terminó con código $exitCode."
            }

            ResetResult(
                success = success,
                message = if (success) {
                    context.getString(R.string.density_reset_success)
                } else {
                    context.getString(R.string.command_error_with_code, exitCode)
                },
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr
            )
        } catch (throwable: Throwable) {
            process?.let(::terminateProcess)
            val cause = unwrapReflectionError(throwable)
            val detail = formatThrowable("Proceso remoto de Shizuku", cause)
            lastConnectionDetail = detail
            ResetResult(
                success = false,
                message = context.getString(
                    R.string.unexpected_error,
                    cause.message ?: cause.javaClass.simpleName
                ),
                exitCode = EXIT_CODE_INTERNAL_ERROR,
                stderr = detail
            )
        } finally {
            closeProcessStreams(process)
        }
    }

    private fun startRemoteProcess(): Process {
        val remoteProcess = try {
            remoteProcessMethod.invoke(null, fixedCommand, null, null)
        } catch (exception: InvocationTargetException) {
            throw exception.targetException ?: exception
        }

        return remoteProcess as? Process
            ?: throw IllegalStateException("Shizuku no devolvió un proceso remoto compatible.")
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
        append("Modo: proceso remoto directo | Disponible: ")
        append(if (state.userServiceConnected) "sí" else "no")
    }

    private fun InputStream.readFully(): String =
        bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }.trim()

    private fun readFutureSafely(future: Future<String>): String =
        try {
            future.get(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).trim()
        } catch (_: Throwable) {
            ""
        }

    private fun terminateProcess(process: Process) {
        try {
            process.destroy()
            if (!process.waitFor(PROCESS_DESTROY_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(PROCESS_DESTROY_GRACE_SECONDS, TimeUnit.SECONDS)
            }
        } catch (_: Throwable) {
            runCatching { process.destroyForcibly() }
        } finally {
            closeProcessStreams(process)
        }
    }

    private fun closeProcessStreams(process: Process?) {
        if (process == null) return
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
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

    private fun unwrapReflectionError(throwable: Throwable): Throwable =
        if (throwable is InvocationTargetException && throwable.targetException != null) {
            throwable.targetException
        } else {
            throwable
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
