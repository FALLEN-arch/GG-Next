package com.gg.core.search

import com.gg.core.memory.MemoryAccess
import com.gg.core.memory.MemoryEngine
import com.gg.core.memory.SearchRequest
import com.gg.core.memory.SearchResult
import com.gg.core.memory.ValueType
import com.gg.core.process.ProcessAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages a search session: first scan → refine → get results → clear.
 *
 * Refine only re-checks the addresses found in the previous step,
 * which is dramatically faster than scanning memory again.
 */
interface SearchSession {
    /** First full scan. Replaces any previous results. */
    suspend fun firstScan(request: SearchRequest): SearchResult

    /** Refine current results against a new value. */
    suspend fun refine(request: SearchRequest): SearchResult

    /** Current list of matching addresses. */
    fun getCurrentResults(): List<Long>

    /** Number of current results. */
    fun getResultCount(): Int

    /** Clear the session. */
    fun clear()

    /** The value type used in the current session (needed for refine/edit). */
    fun getValueType(): ValueType?
}

class DefaultSearchSession(
    private val memory: MemoryEngine = MemoryAccess.get()
) : SearchSession {

    private val results = AtomicReference<List<Long>>(emptyList())
    private val valueType = AtomicReference<ValueType?>(null)

    override suspend fun firstScan(request: SearchRequest): SearchResult =
        withContext(Dispatchers.IO) {
            val pid = ProcessAccess.get().getCurrentPid()
                ?: return@withContext SearchResult(emptyList(), 0)

            val result = memory.search(pid, request)
            results.set(result.matches)
            valueType.set(request.type)
            result
        }

    override suspend fun refine(request: SearchRequest): SearchResult =
        withContext(Dispatchers.IO) {
            val pid = ProcessAccess.get().getCurrentPid()
                ?: return@withContext SearchResult(emptyList(), 0)

            val current = results.get()
            if (current.isEmpty()) {
                // Nothing to refine – fall back to first scan behaviour
                return@withContext firstScan(request)
            }

            val pattern = valueToBytes(request.value, request.type)
                ?: return@withContext SearchResult(emptyList(), 0)

            val matched = ArrayList<Long>()
            // Read each previous address and compare
            for (addr in current) {
                val data = memory.read(pid, addr, pattern.size) ?: continue
                if (data.contentEquals(pattern)) {
                    matched.add(addr)
                }
                // Soft safety cap
                if (matched.size >= 50_000) break
            }

            results.set(matched)
            valueType.set(request.type)
            SearchResult(matched, matched.size)
        }

    override fun getCurrentResults(): List<Long> = results.get()

    override fun getResultCount(): Int = results.get().size

    override fun clear() {
        results.set(emptyList())
        valueType.set(null)
    }

    override fun getValueType(): ValueType? = valueType.get()

    // -------------------------------------------------------------------------

    private fun valueToBytes(value: String, type: ValueType): ByteArray? {
        val v = value.trim()
        if (v.isEmpty()) return null
        return try {
            when (type) {
                ValueType.BYTE -> byteArrayOf(v.toInt(radixFor(v)).toByte())
                ValueType.WORD -> {
                    val s = v.toInt(radixFor(v)).toShort()
                    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(s).array()
                }
                ValueType.DWORD -> {
                    val i = v.toLong(radixFor(v)).toInt()
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(i).array()
                }
                ValueType.QWORD -> {
                    val l = v.toLong(radixFor(v))
                    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(l).array()
                }
                ValueType.FLOAT -> {
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v.toFloat()).array()
                }
                ValueType.DOUBLE -> {
                    ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putDouble(v.toDouble()).array()
                }
                ValueType.UTF8 -> v.toByteArray(Charsets.UTF_8)
                ValueType.UTF16 -> v.toByteArray(Charsets.UTF_16LE)
                ValueType.AOB -> parseAob(v)
                ValueType.AUTO -> {
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
        if (v.startsWith("0x", true) || v.any { it in 'a'..'f' || it in 'A'..'F' }) 16 else 10

    private fun parseAob(aob: String): ByteArray? {
        val cleaned = aob.replace(",", " ").replace("0x", " ", ignoreCase = true).trim()
        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) {
            val hex = aob.filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
            if (hex.length % 2 != 0) return null
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        return tokens.map { it.toInt(16).toByte() }.toByteArray()
    }
}

object SearchAccess {
    @Volatile
    private var instance: SearchSession = DefaultSearchSession()

    fun get(): SearchSession = instance

    fun set(session: SearchSession) {
        instance = session
    }
}
