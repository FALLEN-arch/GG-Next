package com.gg.core.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import com.gg.core.settings.Settings
import java.util.concurrent.atomic.AtomicReference

/**
 * Root access manager.
 *
 * Ports the practical approach from the original GameGuardian (e2 + ConfigListAdapter su paths)
 * into clean, modern Kotlin.
 */
interface RootManager {
    /** Returns true if a working su binary was found and can execute commands. */
    fun isRootAvailable(): Boolean

    /** Returns the path of the su binary currently in use, or null. */
    fun getSuPath(): String?

    /**
     * Execute a single command as root.
     * @param command the shell command to run
     * @param timeoutMs maximum time to wait for completion
     */
    suspend fun exec(command: String, timeoutMs: Long = 15_000): RootResult

    /**
     * Execute multiple commands in one su session (more efficient for batches).
     */
    suspend fun exec(commands: List<String>, timeoutMs: Long = 30_000): RootResult
}

data class RootResult(
    val success: Boolean,
    val output: String,
    val error: String,
    val exitCode: Int
) {
    val isSuccess get() = success && exitCode == 0
}

/**
 * Default implementation.
 *
 * Detection order follows common real-world locations used by Magisk, KernelSU,
 * APatch, and older SuperSU / phh-style setups.
 */
class DefaultRootManager : RootManager {

    companion object {
        // Common su locations (order matters – prefer modern ones first)
        private val SU_CANDIDATES = listOf(
            "su",                          // PATH
            "ku.sud",                      // KernelSU
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su",
            "/magisk/phh/bin/su",
            "/system/xbin/bstk/su",
            "/data/adb/ksu/bin/su",        // KernelSU newer
            "/data/adb/ap/bin/su",         // APatch
            "/data/adb/magisk/su"
        )

        private const val EXIT_MARKER = "GG_ROOT_EXIT:"
    }

    private val detectedSu = AtomicReference<String?>(null)
    private val availability = AtomicReference<Boolean?>(null)

    override fun isRootAvailable(): Boolean {
        availability.get()?.let { return it }

        val path = findWorkingSu()
        val available = path != null
        detectedSu.set(path)
        availability.set(available)
        return available
    }

    override fun getSuPath(): String? {
        if (detectedSu.get() == null) {
            isRootAvailable() // force detection
        }
        return detectedSu.get()
    }

    override suspend fun exec(command: String, timeoutMs: Long): RootResult {
        return exec(listOf(command), timeoutMs)
    }

    override suspend fun exec(commands: List<String>, timeoutMs: Long): RootResult =
        withContext(Dispatchers.IO) {
            val su = getSuPath()
                ?: return@withContext RootResult(
                    success = false,
                    output = "",
                    error = "No working su binary found",
                    exitCode = -1
                )

            try {
                withTimeoutOrNull(timeoutMs) {
                    executeWithSu(su, commands)
                } ?: RootResult(
                    success = false,
                    output = "",
                    error = "Command timed out after ${timeoutMs}ms",
                    exitCode = -2
                )
            } catch (t: Throwable) {
                RootResult(
                    success = false,
                    output = "",
                    error = t.message ?: t.toString(),
                    exitCode = -3
                )
            }
        }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun findWorkingSu(): String? {
        // Prefer user-configured path when set
        try {
            val custom = Settings.suPath
            if (custom.isNotBlank() && testSu(custom)) {
                return custom
            }
        } catch (_: Exception) {
            // Settings may not be initialized in tests
        }

        for (candidate in SU_CANDIDATES) {
            if (testSu(candidate)) {
                return candidate
            }
        }
        return null
    }

    /**
     * Quick test: try to run "id" and check for uid=0.
     */
    private fun testSu(suPath: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", "id"))
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return false
            }
            val output = process.inputStream.bufferedReader().readText()
            process.exitValue() == 0 && output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    private fun executeWithSu(suPath: String, commands: List<String>): RootResult {
        val process = Runtime.getRuntime().exec(suPath)

        DataOutputStream(process.outputStream).use { stdin ->
            // Make the shell more predictable
            stdin.writeBytes("export PATH=/system/bin:/system/xbin:/sbin:/bin:\$PATH\n")
            stdin.writeBytes("export LANG=C\n")

            for (cmd in commands) {
                stdin.writeBytes(cmd)
                stdin.writeBytes("\n")
            }

            // Force an exit code marker so we can reliably know when the shell finished
            stdin.writeBytes("echo $EXIT_MARKER\$?\n")
            stdin.writeBytes("exit\n")
            stdin.flush()
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        // Read stdout
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stdout.appendLine(line)
            }
        }

        // Read stderr
        BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stderr.appendLine(line)
            }
        }

        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return RootResult(
                success = false,
                output = stdout.toString().trim(),
                error = "Process did not exit cleanly",
                exitCode = -4
            )
        }

        // Parse the exit marker we injected
        val fullOutput = stdout.toString()
        val exitCode = parseExitCode(fullOutput)
        val cleanOutput = fullOutput
            .lines()
            .filterNot { it.startsWith(EXIT_MARKER) }
            .joinToString("\n")
            .trim()

        return RootResult(
            success = exitCode == 0,
            output = cleanOutput,
            error = stderr.toString().trim(),
            exitCode = exitCode
        )
    }

    private fun parseExitCode(output: String): Int {
        val markerLine = output.lines().lastOrNull { it.startsWith(EXIT_MARKER) }
            ?: return -5
        return markerLine.removePrefix(EXIT_MARKER).trim().toIntOrNull() ?: -5
    }
}

/**
 * Simple factory / singleton helper.
 * Call RootAccess.init() early (e.g. in Application.onCreate) if you want
 * eager detection, otherwise it detects on first use.
 */
object RootAccess {
    @Volatile
    private var instance: RootManager = DefaultRootManager()

    fun get(): RootManager = instance

    /** Allow tests or alternative implementations to replace the manager. */
    fun set(manager: RootManager) {
        instance = manager
    }

    fun init(): Boolean {
        return instance.isRootAvailable()
    }
}
