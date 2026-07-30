package com.zaid.densityreset.shizuku

import android.util.Log
import androidx.annotation.Keep
import com.zaid.densityreset.IPrivilegedDensityService
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

@Keep
class PrivilegedDensityService : IPrivilegedDensityService.Stub() {

    init {
        Log.i(TAG, "UserService creado")
    }

    private val commandExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "density-reset-command")
    }
    private val streamExecutor: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "density-reset-stream")
    }
    private val activeProcess = AtomicReference<Process?>(null)

    override fun resetDensity(): String {
        return try {
            commandExecutor.submit(Callable { executeFixedCommand() })
                .get(COMMAND_TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            terminateActiveProcess()
            resultJson(
                success = false,
                exitCode = EXIT_CODE_TIMEOUT,
                stderr = "El comando excedió el tiempo máximo y fue detenido.",
                timedOut = true
            )
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            terminateActiveProcess()
            resultJson(
                success = false,
                exitCode = EXIT_CODE_INTERNAL_ERROR,
                stderr = "La ejecución fue interrumpida."
            )
        } catch (exception: ExecutionException) {
            terminateActiveProcess()
            resultJson(
                success = false,
                exitCode = EXIT_CODE_INTERNAL_ERROR,
                stderr = exception.cause?.message ?: exception.message.orEmpty()
            )
        } catch (throwable: Throwable) {
            terminateActiveProcess()
            resultJson(
                success = false,
                exitCode = EXIT_CODE_INTERNAL_ERROR,
                stderr = throwable.message ?: throwable.javaClass.simpleName
            )
        }
    }

    private fun executeFixedCommand(): String {
        var process: Process? = null
        return try {
            val startedProcess = ProcessBuilder(
                "/system/bin/wm",
                "density",
                "reset"
            )
                .redirectErrorStream(false)
                .start()
            process = startedProcess

            activeProcess.set(startedProcess)

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
                return resultJson(
                    success = false,
                    exitCode = EXIT_CODE_TIMEOUT,
                    stdout = stdout,
                    stderr = stderr.ifBlank { "El comando excedió el tiempo máximo y fue detenido." },
                    timedOut = true
                )
            }

            val exitCode = startedProcess.exitValue()
            val stdout = readFutureSafely(stdoutFuture)
            val stderr = readFutureSafely(stderrFuture)

            resultJson(
                success = exitCode == 0,
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr
            )
        } catch (throwable: Throwable) {
            process?.let(::terminateProcess)
            resultJson(
                success = false,
                exitCode = EXIT_CODE_INTERNAL_ERROR,
                stderr = throwable.message ?: throwable.javaClass.simpleName
            )
        } finally {
            activeProcess.compareAndSet(process, null)
            closeProcessStreams(process)
        }
    }

    private fun InputStream.readFully(): String =
        bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }.trim()

    private fun readFutureSafely(future: java.util.concurrent.Future<String>): String =
        try {
            future.get(STREAM_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).trim()
        } catch (_: Throwable) {
            ""
        }

    private fun terminateActiveProcess() {
        activeProcess.getAndSet(null)?.let(::terminateProcess)
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

    private fun resultJson(
        success: Boolean,
        exitCode: Int,
        stdout: String = "",
        stderr: String = "",
        timedOut: Boolean = false
    ): String = JSONObject()
        .put("success", success)
        .put("exitCode", exitCode)
        .put("stdout", stdout)
        .put("stderr", stderr)
        .put("timedOut", timedOut)
        .toString()

    override fun destroy() {
        Log.i(TAG, "Destruyendo UserService")
        terminateActiveProcess()
        commandExecutor.shutdownNow()
        streamExecutor.shutdownNow()
        System.exit(0)
    }

    private companion object {
        const val TAG = "DensityResetUserSvc"
        const val COMMAND_TIMEOUT_SECONDS = 15L
        const val COMMAND_TOTAL_TIMEOUT_SECONDS = 20L
        const val STREAM_READ_TIMEOUT_SECONDS = 2L
        const val PROCESS_DESTROY_GRACE_SECONDS = 1L
        const val EXIT_CODE_TIMEOUT = -2
        const val EXIT_CODE_INTERNAL_ERROR = -1
    }
}
