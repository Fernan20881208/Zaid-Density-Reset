package com.zaid.densityreset.gameprofile.shizuku

import com.zaid.densityreset.density.DensityControlException
import com.zaid.densityreset.density.DensityFailureReason
import com.zaid.densityreset.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

data class ShizukuCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean
        get() = exitCode == 0
}

class ShizukuCommandExecutor {

    private val remoteProcessMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val stringArrayClass = arrayOf<String>().javaClass
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            stringArrayClass,
            stringArrayClass,
            String::class.java
        ).apply { isAccessible = true }
    }

    suspend fun execute(
        command: Array<String>,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
    ): Result<ShizukuCommandResult> = withContext(Dispatchers.IO) {
        runCatching {
            ensureShizukuReady()
            runRemoteProcess(command, timeoutSeconds)
        }
    }

    private fun runRemoteProcess(
        command: Array<String>,
        timeoutSeconds: Long
    ): ShizukuCommandResult {
        var process: ShizukuRemoteProcess? = null
        return try {
            val started = startRemoteProcess(command)
            process = started
            runCatching { started.outputStream.close() }

            if (!started.waitForTimeout(timeoutSeconds, TimeUnit.SECONDS)) {
                terminateProcess(started)
                throw DensityControlException(
                    DensityFailureReason.REMOTE_PROCESS_FAILED,
                    "La operación de Shizuku excedió el tiempo permitido."
                )
            }

            ShizukuCommandResult(
                exitCode = started.waitFor(),
                stdout = readStream(started.inputStream),
                stderr = readStream(started.errorStream)
            )
        } catch (throwable: Throwable) {
            process?.let(::terminateProcess)
            val cause = unwrap(throwable)
            if (cause is DensityControlException) throw cause
            throw DensityControlException(
                DensityFailureReason.REMOTE_PROCESS_FAILED,
                when {
                    cause is SecurityException ->
                        "El fabricante bloqueó esta operación."
                    cause.message.orEmpty().contains("binder", ignoreCase = true) ->
                        "Shizuku se desconectó durante la sesión."
                    else -> "No se pudo ejecutar la operación mediante Shizuku."
                },
                cause
            )
        } finally {
            closeStreams(process)
        }
    }

    private fun startRemoteProcess(command: Array<String>): ShizukuRemoteProcess {
        val value = try {
            remoteProcessMethod.invoke(null, command, null, null)
        } catch (exception: InvocationTargetException) {
            throw exception.targetException ?: exception
        }
        return value as? ShizukuRemoteProcess
            ?: throw DensityControlException(
                DensityFailureReason.REMOTE_PROCESS_FAILED,
                "Shizuku no devolvió un proceso remoto compatible."
            )
    }

    private fun ensureShizukuReady() {
        val state = ShizukuManager.currentState()
        when {
            !state.installed -> throw DensityControlException(
                DensityFailureReason.SHIZUKU_NOT_INSTALLED,
                "Shizuku no está instalado."
            )
            !state.running -> throw DensityControlException(
                DensityFailureReason.SHIZUKU_NOT_RUNNING,
                "Shizuku no está ejecutándose."
            )
            !state.permissionGranted -> throw DensityControlException(
                DensityFailureReason.SHIZUKU_PERMISSION_DENIED,
                "Permiso de Shizuku denegado."
            )
        }
    }

    private fun terminateProcess(process: ShizukuRemoteProcess) {
        runCatching {
            process.destroy()
            if (!process.waitForTimeout(PROCESS_DESTROY_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitForTimeout(PROCESS_DESTROY_GRACE_SECONDS, TimeUnit.SECONDS)
            }
        }
    }

    private fun closeStreams(process: ShizukuRemoteProcess?) {
        if (process == null) return
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun readStream(stream: InputStream): String = runCatching {
        stream.bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
    }.getOrDefault("")

    private fun unwrap(throwable: Throwable): Throwable =
        if (throwable is InvocationTargetException && throwable.targetException != null) {
            throwable.targetException
        } else {
            throwable
        }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 15L
        const val PROCESS_DESTROY_GRACE_SECONDS = 1L
    }
}
