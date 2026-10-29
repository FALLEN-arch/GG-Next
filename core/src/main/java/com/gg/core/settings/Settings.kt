package com.gg.core.settings

import android.content.Context
import android.content.SharedPreferences
import com.gg.core.memory.ValueType

/**
 * App settings backed by SharedPreferences.
 * Safe to read from any module; call [Settings.init] once from Application.
 */
object Settings {

    private const val PREFS = "gg_clean_settings"

    private const val KEY_VALUE_TYPE = "default_value_type"
    private const val KEY_MAX_RESULTS = "max_results"
    private const val KEY_SU_PATH = "su_path"
    private const val KEY_INCLUDE_SYSTEM = "include_system"
    private const val KEY_SEARCH_TIMEOUT = "search_timeout_ms"
    private const val KEY_PANEL_OPACITY = "panel_opacity"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun p(): SharedPreferences {
        return prefs
            ?: throw IllegalStateException("Settings.init(context) must be called first")
    }

    // ---- Default value type ----

    var defaultValueType: ValueType
        get() = typeFromName(p().getString(KEY_VALUE_TYPE, "DWORD") ?: "DWORD")
        set(value) {
            p().edit().putString(KEY_VALUE_TYPE, value.name).apply()
        }

    // ---- Max results shown / returned ----

    var maxResults: Int
        get() = p().getInt(KEY_MAX_RESULTS, 500).coerceIn(10, 50_000)
        set(value) {
            p().edit().putInt(KEY_MAX_RESULTS, value.coerceIn(10, 50_000)).apply()
        }

    // ---- Custom su path (empty = auto-detect) ----

    var suPath: String
        get() = p().getString(KEY_SU_PATH, "") ?: ""
        set(value) {
            p().edit().putString(KEY_SU_PATH, value.trim()).apply()
        }

    // ---- Include system processes in list ----

    var includeSystemProcesses: Boolean
        get() = p().getBoolean(KEY_INCLUDE_SYSTEM, false)
        set(value) {
            p().edit().putBoolean(KEY_INCLUDE_SYSTEM, value).apply()
        }

    // ---- Search timeout (ms) ----

    var searchTimeoutMs: Long
        get() = p().getLong(KEY_SEARCH_TIMEOUT, 60_000L).coerceIn(5_000L, 300_000L)
        set(value) {
            p().edit().putLong(KEY_SEARCH_TIMEOUT, value.coerceIn(5_000L, 300_000L)).apply()
        }

    // ---- Panel opacity 0.5–1.0 ----

    var panelOpacity: Float
        get() = p().getFloat(KEY_PANEL_OPACITY, 0.95f).coerceIn(0.5f, 1.0f)
        set(value) {
            p().edit().putFloat(KEY_PANEL_OPACITY, value.coerceIn(0.5f, 1.0f)).apply()
        }

    // ---- Helpers ----

    fun typeFromName(name: String): ValueType = when (name.uppercase()) {
        "BYTE" -> ValueType.BYTE
        "WORD" -> ValueType.WORD
        "DWORD" -> ValueType.DWORD
        "QWORD" -> ValueType.QWORD
        "FLOAT" -> ValueType.FLOAT
        "DOUBLE" -> ValueType.DOUBLE
        "UTF8" -> ValueType.UTF8
        "UTF16" -> ValueType.UTF16
        "AOB" -> ValueType.AOB
        "AUTO" -> ValueType.AUTO
        else -> ValueType.DWORD
    }

    fun resetToDefaults() {
        p().edit().clear().apply()
    }
}
