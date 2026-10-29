package com.gg.core.process

import com.gg.core.root.RootAccess
import com.gg.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Process listing, attach, and basic info.
 *
 * Uses root (via RootManager) for accurate results on modern Android.
 */
interface ProcessManager {
    /** Returns a list of currently running processes. */
    suspend fun listProcesses(includeSystem: Boolean = false): List<ProcessInfo>

    /** Attach to a process. Returns true if the PID is valid and accessible. */
    suspend fun attach(pid: Int): Boolean

    /** Returns the currently attached PID, or null. */
    fun getCurrentPid(): Int?

    /** Get detailed info for a single PID. */
    suspend fun getProcessInfo(pid: Int): ProcessInfo?

    /** Detach from the current process. */
    fun detach()
}

data class ProcessInfo(
    val pid: Int,
    val name: String,                 // usually the process name / cmdline
    val packageName: String?,         // Android package if available
    val is64Bit: Boolean,
    val uid: Int,
    val user: String = "",            // e.g. u0_a123 or root
    val ppid: Int = 0
) {
    val displayName: String
        get() = packageName?.takeIf { it.isNotBlank() } ?: name
}

/**
 * Default implementation.
 *
 * Strategy:
 * 1. Prefer `ps -A -o PID,PPID,UID,USER,NAME,ARGS` via root (most reliable)
 * 2. Fall back to walking /proc if needed
 * 3. Detect 64-bit by checking /proc/<pid>/exe or maps
 * 4. Resolve package name from /proc/<pid>/cmdline or known patterns
 */
class DefaultProcessManager(
    private val root: RootManager = RootAccess.get()
) : ProcessManager {

    private val currentPid = AtomicInteger(-1)
    private val lastList = AtomicReference<List<ProcessInfo>>(emptyList())

    override suspend fun listProcesses(includeSystem: Boolean): List<ProcessInfo> =
        withContext(Dispatchers.IO) {
            val processes = if (root.isRootAvailable()) {
                listViaPs()
            } else {
                listViaProc() // limited, non-root fallback
            }

            val filtered = if (includeSystem) {
                processes
            } else {
                processes.filter { info ->
                    // Keep user apps and interesting processes, drop pure system ones
                    val pkg = info.packageName
                    when {
                        pkg != null && !pkg.startsWith("android.") &&
                                !pkg.startsWith("com.android.") &&
                                pkg.contains('.') -> true
                        info.uid >= 10000 -> true          // app UIDs
                        info.name.contains(".") -> true
                        else -> false
                    }
                }
            }

            // Sort: user apps first, then by name
            val sorted = filtered.sortedWith(
                compareByDescending<ProcessInfo> { it.uid >= 10000 }
                    .thenBy { it.displayName.lowercase() }
            )

            lastList.set(sorted)
            sorted
        }

    override suspend fun attach(pid: Int): Boolean = withContext(Dispatchers.IO) {
        if (pid <= 0) return@withContext false

        // Verify the process still exists and we can access it
        val exists = if (root.isRootAvailable()) {
            val result = root.exec("test -d /proc/$pid && echo OK")
            result.isSuccess && result.output.contains("OK")
        } else {
            File("/proc/$pid").isDirectory
        }

        if (exists) {
            currentPid.set(pid)
            true
        } else {
            false
        }
    }

    override fun getCurrentPid(): Int? {
        val pid = currentPid.get()
        return if (pid > 0) pid else null
    }

    override suspend fun getProcessInfo(pid: Int): ProcessInfo? = withContext(Dispatchers.IO) {
        // First try the last cached list
        lastList.get().firstOrNull { it.pid == pid }?.let { return@withContext it }

        // Otherwise query directly
        if (root.isRootAvailable()) {
            val result = root.exec("ps -A -p $pid -o PID,PPID,UID,USER,NAME,ARGS")
            if (result.isSuccess) {
                parsePsLine(result.output.lines().drop(1).firstOrNull() ?: "")
                    ?.let { return@withContext it }
            }
        }

        // Fallback: read from /proc
        readProcInfo(pid)
    }

    override fun detach() {
        currentPid.set(-1)
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private suspend fun listViaPs(): List<ProcessInfo> {
        // Busybox/toolbox compatible columns. We request more than we need and parse carefully.
        val result = root.exec("ps -A -o PID,PPID,UID,USER,NAME,ARGS")

        if (!result.isSuccess && result.output.isBlank()) {
            // Some devices use different ps. Try a simpler form.
            val fallback = root.exec("ps -A")
            return parseSimplePs(fallback.output)
        }

        return result.output
            .lines()
            .drop(1) // header
            .mapNotNull { parsePsLine(it) }
            .distinctBy { it.pid }
    }

    private fun parsePsLine(line: String): ProcessInfo? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null

        // Expected roughly: PID PPID UID USER NAME ARGS...
        // We split on whitespace but keep the rest as args/name.
        val parts = trimmed.split(Regex("\\s+"), limit = 6)
        if (parts.size < 5) return null

        val pid = parts[0].toIntOrNull() ?: return null
        val ppid = parts[1].toIntOrNull() ?: 0
        val uid = parts[2].toIntOrNull() ?: -1
        val user = parts[3]
        val name = parts[4]
        val args = parts.getOrElse(5) { "" }

        val packageName = extractPackageName(name, args)
        val is64 = isProcess64Bit(pid)

        return ProcessInfo(
            pid = pid,
            name = name.ifBlank { args.take(64) },
            packageName = packageName,
            is64Bit = is64,
            uid = uid,
            user = user,
            ppid = ppid
        )
    }

    private fun parseSimplePs(output: String): List<ProcessInfo> {
        // Very basic "ps -A" format: USER PID PPID VSZ RSS WCHAN ADDR S NAME
        return output.lines()
            .drop(1)
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 9) return@mapNotNull null
                val user = parts[0]
                val pid = parts[1].toIntOrNull() ?: return@mapNotNull null
                val name = parts.last()
                val uid = if (user == "root") 0 else -1
                ProcessInfo(
                    pid = pid,
                    name = name,
                    packageName = extractPackageName(name, ""),
                    is64Bit = isProcess64Bit(pid),
                    uid = uid,
                    user = user
                )
            }
    }

    private fun listViaProc(): List<ProcessInfo> {
        val proc = File("/proc")
        if (!proc.isDirectory) return emptyList()

        return proc.listFiles()
            ?.mapNotNull { dir ->
                val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
                readProcInfo(pid)
            }
            ?.sortedBy { it.pid }
            ?: emptyList()
    }

    private fun readProcInfo(pid: Int): ProcessInfo? {
        val dir = File("/proc/$pid")
        if (!dir.isDirectory) return null

        val cmdline = try {
            File(dir, "cmdline").readBytes()
                .toString(Charsets.UTF_8)
                .replace('\u0000', ' ')
                .trim()
        } catch (_: Exception) {
            ""
        }

        val status = try {
            File(dir, "status").readText()
        } catch (_: Exception) {
            ""
        }

        val name = cmdline.ifBlank {
            status.lineSequence()
                .firstOrNull { it.startsWith("Name:") }
                ?.substringAfter(":")
                ?.trim()
                ?: "unknown"
        }.take(128)

        val uid = status.lineSequence()
            .firstOrNull { it.startsWith("Uid:") }
            ?.substringAfter(":")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.firstOrNull()
            ?.toIntOrNull()
            ?: -1

        val packageName = extractPackageName(name, cmdline)

        return ProcessInfo(
            pid = pid,
            name = name,
            packageName = packageName,
            is64Bit = isProcess64Bit(pid),
            uid = uid
        )
    }

    /**
     * Heuristic package name extraction.
     * Android app processes usually look like com.example.app or com.example.app:remote
     */
    private fun extractPackageName(name: String, args: String): String? {
        val candidates = listOf(name, args)
        for (c in candidates) {
            val token = c.trim().split(Regex("\\s+|:")).firstOrNull() ?: continue
            if (token.contains('.') &&
                token.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+"))) {
                // Strip any :service suffix
                return token.substringBefore(':')
            }
        }
        return null
    }

    /**
     * Detect whether the process is 64-bit.
     * Best effort: check /proc/<pid>/exe target or the first map entry.
     */
    private fun isProcess64Bit(pid: Int): Boolean {
        // Try reading the ELF header of the executable
        try {
            val exe = File("/proc/$pid/exe")
            if (exe.exists()) {
                // On a rooted device we can often resolve the symlink target
                // Even without full read we can look at maps
            }
        } catch (_: Exception) { /* ignore */ }

        // Fallback: look at /proc/<pid>/maps for high addresses or known linker
        try {
            val maps = File("/proc/$pid/maps")
            if (maps.canRead()) {
                val firstLines = maps.bufferedReader().use { br ->
                    (1..8).mapNotNull { br.readLine() }
                }
                // 64-bit processes almost always have maps above 0x7f00000000 or use linker64
                val joined = firstLines.joinToString("\n")
                if (joined.contains("linker64") ||
                    joined.contains("/lib64/") ||
                    joined.contains("app_process64")) {
                    return true
                }
                // High canonical addresses
                for (line in firstLines) {
                    val addr = line.substringBefore('-').trim()
                    if (addr.length >= 11) { // e.g. 7fxxxxxxxx
                        return true
                    }
                }
            }
        } catch (_: Exception) { /* ignore */ }

        // Last resort: assume 64-bit on modern devices if we can't tell
        // (most phones since ~2017 are 64-bit capable and apps prefer 64)
        return true
    }
}

/**
 * Convenience accessor.
 */
object ProcessAccess {
    @Volatile
    private var instance: ProcessManager = DefaultProcessManager()

    fun get(): ProcessManager = instance

    fun set(manager: ProcessManager) {
        instance = manager
    }
}
