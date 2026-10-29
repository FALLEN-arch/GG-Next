package com.gg.lua

import com.gg.core.memory.MemoryAccess
import com.gg.core.memory.MemoryRange
import com.gg.core.memory.SearchRequest
import com.gg.core.memory.ValueType
import com.gg.core.process.ProcessAccess
import com.gg.core.search.SearchAccess
import kotlinx.coroutines.runBlocking
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Host for Lua scripts with a practical GG-compatible API surface.
 *
 * Original reference: android.ext.Script (~6k lines) + full LuaJ.
 * This implementation covers the functions most real scripts actually call.
 */
interface LuaHost {
    fun execute(script: String): LuaResult
    fun stop()
    fun isRunning(): Boolean
}

data class LuaResult(
    val success: Boolean,
    val output: String = "",
    val error: String? = null
)

/**
 * Default host backed by LuaJ (Lua 5.2).
 */
class DefaultLuaHost : LuaHost {

    private val running = AtomicBoolean(false)
    private var globals: Globals? = null

    override fun isRunning(): Boolean = running.get()

    override fun stop() {
        running.set(false)
        globals = null
    }

    override fun execute(script: String): LuaResult {
        if (script.isBlank()) {
            return LuaResult(success = false, error = "Empty script")
        }

        running.set(true)
        val outBuffer = ByteArrayOutputStream()
        val errBuffer = ByteArrayOutputStream()

        return try {
            val g = JsePlatform.standardGlobals()
            globals = g

            // Capture print output
            val printStream = PrintStream(outBuffer, true, Charsets.UTF_8.name())
            g.STDOUT = printStream
            g.STDERR = PrintStream(errBuffer, true, Charsets.UTF_8.name())

            // Register gg table
            val gg = LuaTable()
            registerGgApi(gg)
            g.set("gg", gg)

            // Also expose TYPE constants at top level for compatibility
            // (some scripts use gg.TYPE_DWORD, others just numbers)
            val chunk = g.load(script, "script")
            val result = chunk.call()

            val output = outBuffer.toString(Charsets.UTF_8.name()).trim()
            val err = errBuffer.toString(Charsets.UTF_8.name()).trim()

            LuaResult(
                success = true,
                output = if (output.isNotEmpty()) output else result.tojstring(),
                error = err.ifEmpty { null }
            )
        } catch (t: Throwable) {
            LuaResult(
                success = false,
                output = outBuffer.toString(Charsets.UTF_8.name()).trim(),
                error = t.message ?: t.toString()
            )
        } finally {
            running.set(false)
            globals = null
        }
    }

    // -------------------------------------------------------------------------
    // GG API registration
    // -------------------------------------------------------------------------

    private fun registerGgApi(gg: LuaTable) {
        // Type constants (match official GG values)
        gg.set("TYPE_AUTO", LuaValue.valueOf(127))
        gg.set("TYPE_BYTE", LuaValue.valueOf(1))
        gg.set("TYPE_WORD", LuaValue.valueOf(2))
        gg.set("TYPE_DWORD", LuaValue.valueOf(4))
        gg.set("TYPE_QWORD", LuaValue.valueOf(32))
        gg.set("TYPE_FLOAT", LuaValue.valueOf(16))
        gg.set("TYPE_DOUBLE", LuaValue.valueOf(64))
        gg.set("TYPE_XOR", LuaValue.valueOf(8))
        gg.set("TYPE_BYTE_E", LuaValue.valueOf(1))
        gg.set("TYPE_WORD_E", LuaValue.valueOf(2))
        gg.set("TYPE_DWORD_E", LuaValue.valueOf(4))

        // Region constants (simplified)
        gg.set("REGION_JAVA_HEAP", LuaValue.valueOf(2))
        gg.set("REGION_C_HEAP", LuaValue.valueOf(1))
        gg.set("REGION_C_ALLOC", LuaValue.valueOf(4))
        gg.set("REGION_C_DATA", LuaValue.valueOf(8))
        gg.set("REGION_C_BSS", LuaValue.valueOf(16))
        gg.set("REGION_ANONYMOUS", LuaValue.valueOf(32))
        gg.set("REGION_JAVA", LuaValue.valueOf(65536))
        gg.set("REGION_STACK", LuaValue.valueOf(64))
        gg.set("REGION_ASHMEM", LuaValue.valueOf(512))
        gg.set("REGION_OTHER", LuaValue.valueOf(0x10000))
        gg.set("REGION_BAD", LuaValue.valueOf(0x20000))
        gg.set("REGION_CODE_APP", LuaValue.valueOf(0x40000))
        gg.set("REGION_CODE_SYS", LuaValue.valueOf(0x80000))
        gg.set("REGION_PPSSPP", LuaValue.valueOf(0x100000))
        gg.set("REGION_ALL", LuaValue.valueOf(-1))

        // ---- Core search / results ----

        gg.set("searchNumber", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val text = args.optjstring(1, "")
                val type = mapType(args.optint(2, 4))
                return runBlocking {
                    val result = SearchAccess.get().firstScan(SearchRequest(text, type))
                    LuaValue.valueOf(result.count)
                }
            }
        })

        gg.set("refineNumber", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val text = args.optjstring(1, "")
                val type = mapType(args.optint(2, 4))
                return runBlocking {
                    val result = SearchAccess.get().refine(SearchRequest(text, type))
                    LuaValue.valueOf(result.count)
                }
            }
        })

        gg.set("getResultsCount", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                return LuaValue.valueOf(SearchAccess.get().getResultCount())
            }
        })

        gg.set("getResults", object : OneArgFunction() {
            override fun call(maxCount: LuaValue): LuaValue {
                val max = maxCount.optint(100)
                val addresses = SearchAccess.get().getCurrentResults().take(max)
                val type = SearchAccess.get().getValueType() ?: ValueType.DWORD
                val size = typeSize(type)

                val table = LuaTable()
                val pid = ProcessAccess.get().getCurrentPid()

                addresses.forEachIndexed { index, addr ->
                    val item = LuaTable()
                    item.set("address", LuaValue.valueOf(java.lang.Long.toUnsignedString(addr)))
                    // Also provide numeric form when it fits
                    if (addr <= Int.MAX_VALUE) {
                        item.set("address", LuaValue.valueOf(addr.toInt()))
                    }
                    item.set("flags", LuaValue.valueOf(typeToFlags(type)))

                    // Read current value if possible
                    if (pid != null) {
                        val bytes = runBlocking { MemoryAccess.get().read(pid, addr, size) }
                        if (bytes != null) {
                            item.set("value", LuaValue.valueOf(bytesToString(bytes, type)))
                        }
                    }
                    table.set(index + 1, item)
                }
                return table
            }
        })

        gg.set("clearResults", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                SearchAccess.get().clear()
                return LuaValue.NONE
            }
        })

        gg.set("editAll", object : TwoArgFunction() {
            override fun call(value: LuaValue, typeVal: LuaValue): LuaValue {
                val text = value.optjstring("")
                val type = mapType(typeVal.optint(4))
                val pid = ProcessAccess.get().getCurrentPid()
                    ?: return LuaValue.valueOf(0)

                val data = valueToBytes(text, type) ?: return LuaValue.valueOf(0)
                val addresses = SearchAccess.get().getCurrentResults()
                var edited = 0

                runBlocking {
                    for (addr in addresses) {
                        if (MemoryAccess.get().write(pid, addr, data)) {
                            edited++
                        }
                    }
                }
                return LuaValue.valueOf(edited)
            }
        })

        // ---- Process / target info ----

        gg.set("getTargetInfo", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val pid = ProcessAccess.get().getCurrentPid()
                val table = LuaTable()
                if (pid == null) {
                    table.set("pid", LuaValue.valueOf(0))
                    return table
                }
                val info = runBlocking { ProcessAccess.get().getProcessInfo(pid) }
                table.set("pid", LuaValue.valueOf(pid))
                table.set("cmd", LuaValue.valueOf(info?.name ?: ""))
                table.set("packageName", LuaValue.valueOf(info?.packageName ?: ""))
                table.set("x64", LuaValue.valueOf(info?.is64Bit == true))
                return table
            }
        })

        gg.set("getTargetPackage", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                val pid = ProcessAccess.get().getCurrentPid() ?: return LuaValue.valueOf("")
                val info = runBlocking { ProcessAccess.get().getProcessInfo(pid) }
                return LuaValue.valueOf(info?.packageName ?: info?.name ?: "")
            }
        })

        gg.set("getTargetProcess", object : ZeroArgFunction() {
            override fun call(): LuaValue {
                return LuaValue.valueOf(ProcessAccess.get().getCurrentPid() ?: 0)
            }
        })

        // ---- Utilities ----

        gg.set("toast", object : OneArgFunction() {
            override fun call(msg: LuaValue): LuaValue {
                // Scripts expect this to exist; UI toast needs a Context.
                // We print so output is still visible in execute() result.
                println("[toast] ${msg.optjstring("")}")
                return LuaValue.NONE
            }
        })

        gg.set("alert", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val msg = args.optjstring(1, "")
                println("[alert] $msg")
                return LuaValue.NONE
            }
        })

        gg.set("sleep", object : OneArgFunction() {
            override fun call(ms: LuaValue): LuaValue {
                val time = ms.optlong(0)
                if (time > 0) {
                    try {
                        Thread.sleep(time.coerceAtMost(60_000)) // safety cap 60s
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                return LuaValue.NONE
            }
        })

        gg.set("isVisible", object : ZeroArgFunction() {
            override fun call(): LuaValue = LuaValue.TRUE
        })

        // setRanges – accept but currently treated as "use defaults"
        // Full region filtering can be wired later into MemoryEngine.search
        gg.set("setRanges", object : OneArgFunction() {
            override fun call(ranges: LuaValue): LuaValue = LuaValue.NONE
        })

        // getFile / copy helpers are frequently used; provide minimal stubs
        gg.set("getFile", object : OneArgFunction() {
            override fun call(name: LuaValue): LuaValue {
                // Return empty path – real file APIs can be added later
                return LuaValue.valueOf("")
            }
        })
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun mapType(flags: Int): ValueType = when (flags) {
        1 -> ValueType.BYTE
        2 -> ValueType.WORD
        4 -> ValueType.DWORD
        16 -> ValueType.FLOAT
        32 -> ValueType.QWORD
        64 -> ValueType.DOUBLE
        127 -> ValueType.AUTO
        else -> ValueType.DWORD
    }

    private fun typeToFlags(type: ValueType): Int = when (type) {
        ValueType.BYTE -> 1
        ValueType.WORD -> 2
        ValueType.DWORD -> 4
        ValueType.FLOAT -> 16
        ValueType.QWORD -> 32
        ValueType.DOUBLE -> 64
        ValueType.AUTO -> 127
        else -> 4
    }

    private fun typeSize(type: ValueType): Int = when (type) {
        ValueType.BYTE -> 1
        ValueType.WORD -> 2
        ValueType.DWORD, ValueType.FLOAT -> 4
        ValueType.QWORD, ValueType.DOUBLE -> 8
        else -> 4
    }

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
                ValueType.AOB -> null // not typical for editAll
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

    private fun bytesToString(data: ByteArray, type: ValueType): String {
        if (data.isEmpty()) return ""
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return try {
            when (type) {
                ValueType.BYTE -> (data[0].toInt() and 0xFF).toString()
                ValueType.WORD -> buf.short.toString()
                ValueType.DWORD -> buf.int.toString()
                ValueType.QWORD -> buf.long.toString()
                ValueType.FLOAT -> buf.float.toString()
                ValueType.DOUBLE -> buf.double.toString()
                else -> data.joinToString("") { "%02X".format(it) }
            }
        } catch (_: Exception) {
            data.joinToString("") { "%02X".format(it) }
        }
    }

    private fun radixFor(v: String): Int =
        if (v.startsWith("0x", true) || v.any { it in 'a'..'f' || it in 'A'..'F' }) 16 else 10
}

object LuaAccess {
    @Volatile
    private var instance: LuaHost = DefaultLuaHost()

    fun get(): LuaHost = instance

    fun set(host: LuaHost) {
        instance = host
    }
}
