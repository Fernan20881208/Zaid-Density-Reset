package com.zaid.densityreset.density

import android.content.Context
import android.os.Process
import android.os.UserHandle
import com.zaid.densityreset.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit

class ShizukuDensityController(context: Context) : DensityController {

    private val appContext = context.applicationContext

    private val remoteProcessMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val stringArrayClass = arrayOf<String>().javaClass
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            stringArrayClass,
            stringArrayClass,
            String::class.java
        ).apply { isAccessible = true }
    }

    override suspend fun getInitialDensity(): Int =
        getSystemState().getOrThrow().initialDensity

    override suspend fun getCurrentDensity(): Int =
        getSystemState().getOrThrow().currentDensity

    override suspend fun applyDensity(density: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureShizukuReady()

            val binderResult = runBridge(
                action = ACTION_APPLY,
                density = density
            )

            if (!binderResult.success) {
                if (
                    density >= WM_MINIMUM_DENSITY &&
                    binderResult.code != null &&
                    binderResult.code in BINDER_FALLBACK_CODES
                ) {
                    applyWithWmFallback(density)
                } else {
                    throw bridgeError(binderResult)
                }
            }

            val verified = readSystemStatePreferBinder()
            if (verified.currentDensity != density) {
                throw DensityControlException(
                    DensityFailureReason.VERIFICATION_FAILED,
                    "No se pudo verificar el DPI aplicado."
                )
            }
        }
    }

    override suspend fun resetDensity(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureShizukuReady()

            val binderResult = runBridge(action = ACTION_RESET)
            if (!binderResult.success) {
                resetWithWmFallback()
            }

            val verified = readSystemStatePreferBinder()
            if (verified.hasOverride || verified.currentDensity != verified.initialDensity) {
                throw DensityControlException(
                    DensityFailureReason.VERIFICATION_FAILED,
                    "No se pudo verificar el DPI aplicado."
                )
            }
        }
    }

    suspend fun getSystemState(): Result<DensitySystemState> = withContext(Dispatchers.IO) {
        runCatching {
            ensureShizukuReady()
            readSystemStatePreferBinder()
        }
    }

    private fun readSystemStatePreferBinder(): DensitySystemState {
        val binderResult = runBridge(action = ACTION_STATUS)
        if (binderResult.success) {
            val initial = binderResult.initial
                ?: throw invalidBridgeResponse()
            val current = binderResult.current
                ?: throw invalidBridgeResponse()
            return DensitySystemState(
                initialDensity = initial,
                currentDensity = current,
                hasOverride = binderResult.hasOverride ?: (initial != current),
                source = DensityReadSource.WINDOW_MANAGER_BINDER
            )
        }

        return readDensityFromWmCommand().getOrElse { fallbackError ->
            throw DensityControlException(
                DensityFailureReason.WINDOW_MANAGER_UNAVAILABLE,
                "No fue posible acceder a WindowManager.",
                fallbackError
            )
        }
    }

    private fun applyWithWmFallback(density: Int) {
        val result = runRemoteProcess(
            arrayOf("/system/bin/wm", "density", density.toString())
        )
        if (result.exitCode != 0) {
            val text = (result.stderr + "\n" + result.stdout).lowercase()
            val message = if (
                "density must be" in text ||
                "invalid" in text ||
                "rejected" in text
            ) {
                "El dispositivo rechazó esta densidad."
            } else {
                "El fabricante bloqueó la modificación de DPI."
            }
            throw DensityControlException(
                DensityFailureReason.DENSITY_REJECTED,
                message
            )
        }
    }

    private fun resetWithWmFallback() {
        val result = runRemoteProcess(
            arrayOf("/system/bin/wm", "density", "reset")
        )
        if (result.exitCode != 0) {
            throw DensityControlException(
                DensityFailureReason.MANUFACTURER_BLOCKED,
                "El fabricante bloqueó la modificación de DPI."
            )
        }
    }

    private fun readDensityFromWmCommand(): Result<DensitySystemState> = runCatching {
        val result = runRemoteProcess(arrayOf("/system/bin/wm", "density"))
        if (result.exitCode != 0) {
            throw IllegalStateException("wm density failed")
        }

        val physical = PHYSICAL_DENSITY_REGEX
            .find(result.stdout)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: throw IllegalStateException("Physical density missing")

        val override = OVERRIDE_DENSITY_REGEX
            .find(result.stdout)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        DensitySystemState(
            initialDensity = physical,
            currentDensity = override ?: physical,
            hasOverride = override != null,
            source = DensityReadSource.WM_COMMAND
        )
    }

    private fun runBridge(action: String, density: Int? = null): BridgeResult {
        val userId = UserHandle.getUserHandleForUid(Process.myUid()).identifier
        val command = buildList {
            add("/system/bin/app_process")
            add("-Djava.class.path=${appContext.applicationInfo.sourceDir}")
            add("/system/bin")
            add("--nice-name=zaid-density-bridge")
            add(BRIDGE_CLASS_NAME)
            add(action)
            add(userId.toString())
            density?.let { add(it.toString()) }
        }.toTypedArray()

        val result = runRemoteProcess(command)
        val marker = result.stdout
            .lineSequence()
            .lastOrNull { it.startsWith(RESULT_PREFIX) }
            ?: result.stderr
                .lineSequence()
                .lastOrNull { it.startsWith(RESULT_PREFIX) }

        if (marker == null) {
            return BridgeResult(
                success = false,
                code = "REMOTE_PROCESS_FAILED",
                message = result.stderr.ifBlank {
                    result.stdout.ifBlank { "No bridge result" }
                }
            )
        }

        val fields = marker
            .split('|')
            .drop(1)
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null
                else part.substring(0, separator) to part.substring(separator + 1)
            }
            .toMap()

        return BridgeResult(
            success = fields["ok"] == "1",
            code = fields["code"],
            message = fields["message"],
            initial = fields["initial"]?.toIntOrNull(),
            current = fields["current"]?.toIntOrNull(),
            hasOverride = fields["override"]?.let { it == "1" }
        )
    }

    private fun runRemoteProcess(command: Array<String>): ProcessResult {
        var process: ShizukuRemoteProcess? = null
        return try {
            val startedProcess = startRemoteProcess(command)
            process = startedProcess
            runCatching { startedProcess.outputStream.close() }

            val finished = startedProcess.waitForTimeout(
                COMMAND_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
            if (!finished) {
                terminateProcess(startedProcess)
                throw DensityControlException(
                    DensityFailureReason.REMOTE_PROCESS_FAILED,
                    "No fue posible acceder a WindowManager."
                )
            }

            val exitCode = startedProcess.waitFor()
            ProcessResult(
                exitCode = exitCode,
                stdout = readStreamSafely(startedProcess.inputStream),
                stderr = readStreamSafely(startedProcess.errorStream)
            )
        } catch (throwable: Throwable) {
            process?.let(::terminateProcess)
            val cause = unwrap(throwable)
            if (cause is DensityControlException) throw cause

            val message = cause.message.orEmpty().lowercase()
            val reason = when {
                "binder" in message && "dead" in message ->
                    DensityFailureReason.BINDER_DISCONNECTED
                cause is SecurityException ->
                    DensityFailureReason.MANUFACTURER_BLOCKED
                else -> DensityFailureReason.REMOTE_PROCESS_FAILED
            }
            val userMessage = when (reason) {
                DensityFailureReason.BINDER_DISCONNECTED ->
                    "Shizuku no está ejecutándose."
                DensityFailureReason.MANUFACTURER_BLOCKED ->
                    "El fabricante bloqueó la modificación de DPI."
                else -> "No fue posible acceder a WindowManager."
            }
            throw DensityControlException(reason, userMessage, cause)
        } finally {
            closeProcessStreams(process)
        }
    }

    private fun startRemoteProcess(command: Array<String>): ShizukuRemoteProcess {
        val remoteProcess = try {
            remoteProcessMethod.invoke(null, command, null, null)
        } catch (exception: InvocationTargetException) {
            throw exception.targetException ?: exception
        }

        return remoteProcess as? ShizukuRemoteProcess
            ?: throw DensityControlException(
                DensityFailureReason.REMOTE_PROCESS_FAILED,
                "No fue posible acceder a WindowManager."
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

    private fun bridgeError(result: BridgeResult): DensityControlException {
        val code = result.code.orEmpty()
        return when (code) {
            "SECURITY_EXCEPTION" -> DensityControlException(
                DensityFailureReason.MANUFACTURER_BLOCKED,
                "El fabricante bloqueó la modificación de DPI."
            )
            "DENSITY_REJECTED" -> DensityControlException(
                DensityFailureReason.DENSITY_REJECTED,
                "El dispositivo rechazó esta densidad."
            )
            "VERIFY_FAILED" -> DensityControlException(
                DensityFailureReason.VERIFICATION_FAILED,
                "No se pudo verificar el DPI aplicado."
            )
            "REMOTE_EXCEPTION" -> DensityControlException(
                DensityFailureReason.WINDOW_MANAGER_UNAVAILABLE,
                "No fue posible acceder a WindowManager."
            )
            "HIDDEN_API_UNAVAILABLE", "WINDOW_MANAGER_UNAVAILABLE" ->
                DensityControlException(
                    DensityFailureReason.WINDOW_MANAGER_UNAVAILABLE,
                    "No fue posible acceder a WindowManager."
                )
            else -> DensityControlException(
                DensityFailureReason.REMOTE_PROCESS_FAILED,
                "No fue posible acceder a WindowManager."
            )
        }
    }

    private fun invalidBridgeResponse() = DensityControlException(
        DensityFailureReason.WINDOW_MANAGER_UNAVAILABLE,
        "No fue posible acceder a WindowManager."
    )

    private fun terminateProcess(process: ShizukuRemoteProcess) {
        runCatching {
            process.destroy()
            if (!process.waitForTimeout(PROCESS_DESTROY_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitForTimeout(PROCESS_DESTROY_GRACE_SECONDS, TimeUnit.SECONDS)
            }
        }
    }

    private fun closeProcessStreams(process: ShizukuRemoteProcess?) {
        if (process == null) return
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    private fun readStreamSafely(stream: InputStream): String =
        runCatching {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText().trim()
            }
        }.getOrDefault("")

    private fun unwrap(throwable: Throwable): Throwable =
        if (
            throwable is InvocationTargetException &&
            throwable.targetException != null
        ) {
            throwable.targetException
        } else {
            throwable
        }

    private data class ProcessResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    )

    private data class BridgeResult(
        val success: Boolean,
        val code: String? = null,
        val message: String? = null,
        val initial: Int? = null,
        val current: Int? = null,
        val hasOverride: Boolean? = null
    )

    private companion object {
        const val ACTION_STATUS = "status"
        const val ACTION_APPLY = "apply"
        const val ACTION_RESET = "reset"
        const val BRIDGE_CLASS_NAME =
            "com.zaid.densityreset.density.DensityBridge"
        const val RESULT_PREFIX = "ZAID_DENSITY_RESULT"
        const val COMMAND_TIMEOUT_SECONDS = 15L
        const val PROCESS_DESTROY_GRACE_SECONDS = 1L
        const val WM_MINIMUM_DENSITY = 72

        val BINDER_FALLBACK_CODES = setOf(
            "HIDDEN_API_UNAVAILABLE",
            "WINDOW_MANAGER_UNAVAILABLE",
            "REMOTE_PROCESS_FAILED"
        )

        val PHYSICAL_DENSITY_REGEX = Regex(
            pattern = "Physical\\s+density\\s*:\\s*(\\d+)",
            option = RegexOption.IGNORE_CASE
        )
        val OVERRIDE_DENSITY_REGEX = Regex(
            pattern = "Override\\s+density\\s*:\\s*(\\d+)",
            option = RegexOption.IGNORE_CASE
        )
    }
}
