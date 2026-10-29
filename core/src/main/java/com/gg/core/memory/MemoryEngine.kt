package com.gg.core.memory

import com.gg.core.root.RootAccess
import com.gg.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Core memory read / write / search engine.
 *
 * Uses root + /proc/<pid>/mem and /proc/<pid>/maps.
 * Designed to be reliable on Magisk / KernelSU / APatch.
 */
interface MemoryEngine {
    /** Read [size] bytes starting at [address] from process [pid]. */
    suspend fun read(pid: Int, address: Long, size: Int): ByteArray?

    /** Write [data] to [address] in process [pid]. */
    suspend fun write(pid: Int, address: Long, data: ByteArray): Boolean

    /** Return the memory maps of the process. */
    suspend fun getMaps(pid: Int): List<MemoryRange>

    /** Basic exact-value search. Full fuzzy/refine comes later. */
    suspend fun search(pid: Int, request: SearchRequest): SearchResult
}

data class SearchRequest(
    val value: String,
    val type: ValueType,
    val memoryRanges: List<MemoryRange> = emptyList(), // empty = search common regions
    val fuzzy: Boolean = false
)

enum class ValueType {
    BYTE, WORD, DWORD, QWORD, FLOAT, DOUBLE, UTF8, UTF16, AOB, AUTO
}

data class MemoryRange(
    val start: Long,
    val end: Long,
    val permissions: String = "---",
    val name: String? = null
) {
    val size: Long get() = end - start
    val isReadable: Boolean get() = permissions.contains('r')
    val isWritable: Boolean get() = permissions.contains('w')
    val isExecutable: Boolean get() = permissions.contains('x')
}

data class SearchResult(
    val matches: List<Long>,
    val count: Int
)

/**
 * Default implementation.
 *
 * Read strategy:
 *   dd if=/proc/PID/mem bs=1 skip=ADDR count=SIZE 2>/dev/null
 *   then base64 so we can safely transport binary through the shell.
 *
 * Write strategy:
 *   base64 payload → decode on device → dd of=/proc/PID/mem ...
 *
 * This avoids needing a custom native helper while remaining practical
 * for the sizes typical in game memory editing.
 */
class DefaultMemoryEngine(
    private val root: RootManager = RootAccess.get()
) : MemoryEngine {

    companion object {
        // Safety limits – prevent accidental huge allocations / very long operations
        private const val MAX_READ_SIZE = 8 * 1024 * 1024          // 8 MB single read
        private const val MAX_WRITE_SIZE = 1 * 1024 * 1024          // 1 MB single write
        private const val MAX_SEARCH_REGION = 64 * 1024 * 1024L     // 64 MB per region in basic search
        private const val MAX_TOTAL_SEARCH = 256 * 1024 * 1024L     // 256 MB total soft limit
        private const val SEARCH_CHUNK = 512 * 1024                 // 512 KB chunks
    }

    private val lastMaps = AtomicReference<Pair<Int, List<MemoryRange>>?>(null)

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    override suspend fun read(pid: Int, address: Long, size: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            if (pid <= 0 || address < 0 || size <= 0) return@withContext null
            if (size > MAX_READ_SIZE) return@withContext null
            if (!root.isRootAvailable()) return@withContext null

            // dd + base64 is reliable and avoids binary corruption through the shell
            val cmd = buildString {
                append("dd if=/proc/$pid/mem bs=1 skip=$address count=$size 2>/dev/null ")
                append("| base64 -w 0 2>/dev/null || base64 2>/dev/null")
            }

            val result = root.exec(cmd, timeoutMs = 30_000)
            if (!result.isSuccess && result.output.isBlank()) return@withContext null

            try {
                android.util.Base64.decode(result.output.trim(), android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                // Fallback: try pure hex if base64 failed for some reason
                null
            }
        }

    override suspend fun write(pid: Int, address: Long, data: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            if (pid <= 0 || address < 0 || data.isEmpty()) return@withContext false
            if (data.size > MAX_WRITE_SIZE) return@withContext false
            if (!root.isRootAvailable()) return@withContext false

            val b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)

            // Write via a temporary file to keep the command line reasonable
            // and to avoid issues with very large printf payloads.
            val tmp = "/data/local/tmp/.gg_mem_write_$$"
            val commands = listOf(
                "echo '$b64' | base64 -d > $tmp 2>/dev/null",
                "dd if=$tmp of=/proc/$pid/mem bs=1 seek=$address count=${data.size} conv=notrunc 2>/dev/null",
                "rm -f $tmp",
                "echo WRITE_OK"
            )

            val result = root.exec(commands, timeoutMs = 30_000)
            result.isSuccess && result.output.contains("WRITE_OK")
        }

    override suspend fun getMaps(pid: Int): List<MemoryRange> =
        withContext(Dispatchers.IO) {
            if (pid <= 0) return@withContext emptyList()

            // Cache last maps for the same pid briefly
            lastMaps.get()?.let { (cachedPid, maps) ->
                if (cachedPid == pid) return@withContext maps
            }

            val maps = if (root.isRootAvailable()) {
                val result = root.exec("cat /proc/$pid/maps")
                if (result.isSuccess || result.output.isNotBlank()) {
                    parseMaps(result.output)
                } else {
                    emptyList()
                }
            } else {
                // Non-root attempt (usually fails for other processes)
                try {
                    val text = java.io.File("/proc/$pid/maps").readText()
                    parseMaps(text)
                } catch (_: Exception) {
                    emptyList()
                }
            }

            lastMaps.set(pid to maps)
            maps
        }

    override suspend fun search(pid: Int, request: SearchRequest): SearchResult =
        withContext(Dispatchers.IO) {
            if (pid <= 0) return@withContext SearchResult(emptyList(), 0)
            if (!root.isRootAvailable()) return@withContext SearchResult(emptyList(), 0)

            val pattern = valueToBytes(request.value, request.type) ?: return@withContext SearchResult(emptyList(), 0)
            if (pattern.isEmpty()) return@withContext SearchResult(emptyList(), 0)

            val regions = if (request.memoryRanges.isNotEmpty()) {
                request.memoryRanges.filter { it.isReadable }
            } else {
                // Default: readable anonymous + heap + common app regions
                getMaps(pid).filter { range ->
                    range.isReadable &&
                            range.size > 0 &&
                            range.size <= MAX_SEARCH_REGION &&
                            (range.name == null ||
                                    range.name.contains("[heap]") ||
                                    range.name.contains("[anon:") ||
                                    range.name.contains("dalvik") ||
                                    range.name.contains("anonymous") ||
                                    range.permissions.contains("rw"))
                }
            }

            val matches = ArrayList<Long>()
            var scanned = 0L

            for (region in regions) {
                if (scanned >= MAX_TOTAL_SEARCH) break
                if (region.size > MAX_SEARCH_REGION) continue

                val regionMatches = searchRegion(pid, region, pattern)
                matches.addAll(regionMatches)
                scanned += region.size

                // Soft limit on number of results for the basic implementation
                if (matches.size >= 10_000) break
            }

            SearchResult(matches = matches, count = matches.size)
        }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun parseMaps(text: String): List<MemoryRange> {
        val list = ArrayList<MemoryRange>()
        for (line in text.lines()) {
            // Typical format:
            // 7a1b2c3000-7a1b2c4000 rw-p 00000000 00:00 0 [anon:libc_malloc]
            val parts = line.trim().split(Regex("\\s+"), limit = 6)
            if (parts.isEmpty()) continue

            val addrPart = parts[0]
            val dash = addrPart.indexOf('-')
            if (dash <= 0) continue

            val start = addrPart.substring(0, dash).toLongOrNull(16) ?: continue
            val end = addrPart.substring(dash + 1).toLongOrNull(16) ?: continue
            if (end <= start) continue

            val perms = parts.getOrElse(1) { "---" }
            val name = parts.getOrNull(5)?.trim()?.ifBlank { null }

            list.add(MemoryRange(start, end, perms, name))
        }
        return list
    }

    private suspend fun searchRegion(pid: Int, region: MemoryRange, pattern: ByteArray): List<Long> {
        val found = ArrayList<Long>()
        var offset = 0L
        val regionSize = region.size

        while (offset < regionSize) {
            val chunkSize = minOf(SEARCH_CHUNK.toLong(), regionSize - offset).toInt()
            val addr = region.start + offset
            val data = read(pid, addr, chunkSize) ?: break

            var pos = 0
            while (true) {
                val idx = indexOf(data, pattern, pos)
                if (idx < 0) break
                found.add(addr + idx)
                pos = idx + 1
                if (found.size >= 10_000) return found
            }

            // Overlap so we don't miss matches that cross chunk boundaries
            offset += (chunkSize - pattern.size + 1).coerceAtLeast(1)
        }
        return found
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        if (pattern.isEmpty() || from >= data.size) return -1
        outer@ for (i in from..data.size - pattern.size) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun valueToBytes(value: String, type: ValueType): ByteArray? {
        val v = value.trim()
        if (v.isEmpty()) return null

        return try {
            when (type) {
                ValueType.BYTE -> byteArrayOf(v.toInt(radixFor(v)).toByte())
                ValueType.WORD -> {
                    val short = v.toInt(radixFor(v)).toShort()
                    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(short).array()
                }
                ValueType.DWORD -> {
                    val int = v.toLong(radixFor(v)).toInt()
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(int).array()
                }
                ValueType.QWORD -> {
                    val long = v.toLong(radixFor(v))
                    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(long).array()
                }
                ValueType.FLOAT -> {
                    val f = v.toFloat()
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(f).array()
                }
                ValueType.DOUBLE -> {
                    val d = v.toDouble()
                    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(d).array()
                }
                ValueType.UTF8 -> v.toByteArray(Charsets.UTF_8)
                ValueType.UTF16 -> v.toByteArray(Charsets.UTF_16LE)
                ValueType.AOB -> parseAob(v)
                ValueType.AUTO -> {
                    // Simple auto: try number first, otherwise treat as UTF8
                    v.toLongOrNull()?.let {
                        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(it.toInt()).array()
                    } ?: v.toByteArray(Charsets.UTF_8)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun radixFor(v: String): Int =
        if (v.startsWith("0x", ignoreCase = true) || v.any { it in 'a'..'f' || it in 'A'..'F' }) 16 else 10

    private fun parseAob(aob: String): ByteArray? {
        // Supports "DEAD BEEF", "DE AD BE EF", "0xDE,0xAD", "DEADBEEF"
        val cleaned = aob
            .replace(",", " ")
            .replace("0x", " ", ignoreCase = true)
            .trim()
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }

        if (tokens.isEmpty()) {
            // Continuous hex string
            val hex = aob.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            if (hex.length % 2 != 0) return null
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        return tokens.map { it.toInt(16).toByte() }.toByteArray()
    }
}

/**
 * Convenience accessor.
 */
object MemoryAccess {
    @Volatile
    private var instance: MemoryEngine = DefaultMemoryEngine()

    fun get(): MemoryEngine = instance

    fun set(engine: MemoryEngine) {
        instance = engine
    }
}
