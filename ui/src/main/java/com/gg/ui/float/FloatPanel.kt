package com.gg.ui.float

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gg.core.memory.MemoryAccess
import com.gg.core.memory.SearchRequest
import com.gg.core.memory.ValueType
import com.gg.core.process.ProcessAccess
import com.gg.core.process.ProcessInfo
import com.gg.core.root.RootAccess
import com.gg.core.search.SearchAccess
import com.gg.lua.LuaAccess
import com.gg.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Floating UI with two modes:
 * - Processes: list + attach + quick memory test
 * - Search: first scan / refine + results list
 */
class FloatPanel(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private var bubbleView: View? = null
    private var panelView: View? = null
    private var isExpanded = false
    private var isShowing = false
    private var currentTab = 0 // 0 = processes, 1 = search

    private val bubbleParams: WindowManager.LayoutParams
    private val panelParams: WindowManager.LayoutParams

    private var processAdapter: ProcessAdapter? = null
    private var resultAdapter: ResultAdapter? = null
    private var statusText: TextView? = null
    private var contentContainer: LinearLayout? = null

    // Search inputs (kept across rebuilds of content)
    private var etSearchValue: EditText? = null
    private var etAddress: EditText? = null
    private var etValue: EditText? = null
    private var etEditValue: EditText? = null
    private var selectedValueType: ValueType = try { Settings.defaultValueType } catch (_: Exception) { ValueType.DWORD }
    private var selectedAddress: Long? = null
    private var tvSelected: TextView? = null

    init {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        bubbleParams = WindowManager.LayoutParams(
            dp(56), dp(56),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }

        panelParams = WindowManager.LayoutParams(
            dp(340),
            dp(520),
            type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(80)
        }
    }

    fun show() {
        if (isShowing) return
        isShowing = true
        showBubble()
    }

    fun hide() {
        removeAllViews()
        isShowing = false
        isExpanded = false
    }

    fun destroy() {
        hide()
        scope.coroutineContext[Job]?.cancel()
    }

    // -------------------------------------------------------------------------
    // Bubble
    // -------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        removePanel()
        val bubble = TextView(context).apply {
            background = ovalDrawable(0xFF6200EE.toInt())
            text = "GG"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            elevation = dp(6).toFloat()
        }

        var lastX = 0; var lastY = 0; var startX = 0; var startY = 0; var moved = false
        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX.toInt(); lastY = event.rawY.toInt()
                    startX = bubbleParams.x; startY = bubbleParams.y; moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - lastX
                    val dy = event.rawY.toInt() - lastY
                    if (kotlin.math.abs(dx) > 4 || kotlin.math.abs(dy) > 4) moved = true
                    bubbleParams.x = startX + dx; bubbleParams.y = startY + dy
                    try { windowManager.updateViewLayout(bubble, bubbleParams) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) expand(); true }
                else -> false
            }
        }
        bubbleView = bubble
        try {
            windowManager.addView(bubble, bubbleParams)
        } catch (_: Exception) {
            Toast.makeText(context, "Overlay permission required", Toast.LENGTH_LONG).show()
            isShowing = false
        }
    }

    // -------------------------------------------------------------------------
    // Expanded panel
    // -------------------------------------------------------------------------

    private fun expand() {
        if (isExpanded) return
        isExpanded = true
        removeBubble()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(0xF0222222.toInt(), 16f)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            elevation = dp(8).toFloat()
        }

        // Header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "GG Clean"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = Button(context).apply {
            text = "—"
            minimumWidth = dp(40)
            setOnClickListener { collapse() }
        }
        header.addView(title)
        header.addView(btnClose)
        root.addView(header)

        // Status
        statusText = TextView(context).apply {
            setTextColor(0xFFBBBBBB.toInt())
            textSize = 12f
            setPadding(0, dp(4), 0, dp(6))
            text = buildStatusLine()
        }
        root.addView(statusText)

        // Tabs
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(6))
        }
        val btnProc = Button(context).apply {
            text = "Proc"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(0) }
        }
        val btnSearch = Button(context).apply {
            text = "Search"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(1) }
        }
        val btnScript = Button(context).apply {
            text = "Script"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(2) }
        }
        val btnSettings = Button(context).apply {
            text = "Set"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab(3) }
        }
        tabs.addView(btnProc)
        tabs.addView(btnSearch)
        tabs.addView(btnScript)
        tabs.addView(btnSettings)
        root.addView(tabs)

        // Content container (swapped on tab change)
        contentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(contentContainer)

        panelView = root
        makeDraggable(header, root)

        try {
            panelParams.x = bubbleParams.x
            panelParams.y = bubbleParams.y
            try { root.alpha = Settings.panelOpacity } catch (_: Exception) {}
            windowManager.addView(root, panelParams)
            switchTab(currentTab)
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to show panel: ${e.message}", Toast.LENGTH_LONG).show()
            collapse()
        }
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        val container = contentContainer ?: return
        container.removeAllViews()
        when (tab) {
            0 -> buildProcessTab(container)
            1 -> buildSearchTab(container)
            2 -> buildScriptTab(container)
            3 -> buildSettingsTab(container)
        }
        statusText?.text = buildStatusLine()
    }

    // ---- Process tab ----

    private fun buildProcessTab(container: LinearLayout) {
        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        processAdapter = ProcessAdapter { onProcessSelected(it) }
        recycler.adapter = processAdapter
        container.addView(recycler)

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val btnRefresh = Button(context).apply {
            text = "Refresh"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { loadProcesses() }
        }
        val btnTest = Button(context).apply {
            text = "Test Read"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { testRead() }
        }
        actions.addView(btnRefresh)
        actions.addView(btnTest)
        container.addView(actions)

        val memRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        etAddress = EditText(context).apply {
            hint = "Address (hex)"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            setText("0")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        etValue = EditText(context).apply {
            hint = "Value"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        memRow.addView(etAddress)
        memRow.addView(etValue)
        container.addView(memRow)

        loadProcesses()
    }

    // ---- Search tab ----

    private fun buildSearchTab(container: LinearLayout) {
        // Value input
        etSearchValue = EditText(context).apply {
            hint = "Value (use 0x for hex)"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(etSearchValue)

        // Type selector row
        val typeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val types = listOf(
            "BYTE" to ValueType.BYTE,
            "WORD" to ValueType.WORD,
            "DWORD" to ValueType.DWORD,
            "FLOAT" to ValueType.FLOAT,
            "QWORD" to ValueType.QWORD
        )
        for ((label, type) in types) {
            val btn = Button(context).apply {
                text = label
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                alpha = if (type == selectedValueType) 1f else 0.5f
                setOnClickListener {
                    selectedValueType = type
                    // refresh alphas
                    for (i in 0 until typeRow.childCount) {
                        typeRow.getChildAt(i).alpha = if (i == types.indexOfFirst { it.second == type }) 1f else 0.5f
                    }
                    statusText?.text = buildStatusLine() + "  ·  type $label"
                }
            }
            typeRow.addView(btn)
        }
        container.addView(typeRow)

        // Scan buttons
        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnFirst = Button(context).apply {
            text = "New Scan"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { doFirstScan() }
        }
        val btnRefine = Button(context).apply {
            text = "Refine"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { doRefine() }
        }
        val btnClear = Button(context).apply {
            text = "Clear"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                SearchAccess.get().clear()
                selectedAddress = null
                resultAdapter?.submit(emptyList())
                tvSelected?.text = "No selection"
                statusText?.text = buildStatusLine()
            }
        }
        btnRow.addView(btnFirst)
        btnRow.addView(btnRefine)
        btnRow.addView(btnClear)
        container.addView(btnRow)

        // Selected + edit row
        tvSelected = TextView(context).apply {
            text = "No selection"
            setTextColor(0xFF80CBC4.toInt())
            textSize = 12f
            setPadding(0, dp(6), 0, dp(2))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        container.addView(tvSelected)

        val editRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        etEditValue = EditText(context).apply {
            hint = "New value"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF888888.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnEdit = Button(context).apply {
            text = "Edit"
            setOnClickListener { editSelected() }
        }
        editRow.addView(etEditValue)
        editRow.addView(btnEdit)
        container.addView(editRow)

        // Results list
        val resultsLabel = TextView(context).apply {
            text = "Results (tap to select)"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(0, dp(8), 0, dp(4))
        }
        container.addView(resultsLabel)

        val recycler = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        resultAdapter = ResultAdapter { addr -> onResultClicked(addr) }
        recycler.adapter = resultAdapter
        container.addView(recycler)

        val existing = SearchAccess.get().getCurrentResults()
        resultAdapter?.submit(existing)
        statusText?.text = buildStatusLine()
    }


    // ---- Settings tab ----

    private fun buildSettingsTab(container: LinearLayout) {
        val scroll = android.widget.ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }

        fun section(title: String) {
            val tv = TextView(context).apply {
                text = title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(0, dp(10), 0, dp(4))
            }
            inner.addView(tv)
        }

        fun labeledEdit(label: String, value: String, onChange: (String) -> Unit): EditText {
            val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(context).apply {
                text = label
                setTextColor(0xFFAAAAAA.toInt())
                textSize = 11f
            })
            val et = EditText(context).apply {
                setText(value)
                setTextColor(0xFFFFFFFF.toInt())
                setHintTextColor(0xFF666666.toInt())
                textSize = 13f
            }
            et.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) onChange(et.text.toString())
            }
            row.addView(et)
            inner.addView(row)
            return et
        }

        // Default type
        section("Default value type")
        val typeRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val typeNames = listOf("BYTE", "WORD", "DWORD", "FLOAT", "QWORD")
        for (name in typeNames) {
            val btn = Button(context).apply {
                text = name
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                alpha = if (Settings.defaultValueType.name == name) 1f else 0.45f
                setOnClickListener {
                    Settings.defaultValueType = Settings.typeFromName(name)
                    selectedValueType = Settings.defaultValueType
                    for (i in 0 until typeRow.childCount) {
                        typeRow.getChildAt(i).alpha =
                            if ((typeRow.getChildAt(i) as Button).text == name) 1f else 0.45f
                    }
                    Toast.makeText(context, "Default type: $name", Toast.LENGTH_SHORT).show()
                }
            }
            typeRow.addView(btn)
        }
        inner.addView(typeRow)

        // Max results
        section("Max results (UI list)")
        labeledEdit("10 – 50000", Settings.maxResults.toString()) { v ->
            v.toIntOrNull()?.let {
                Settings.maxResults = it
                Toast.makeText(context, "Max results: ${Settings.maxResults}", Toast.LENGTH_SHORT).show()
            }
        }

        // Su path
        section("Custom su path (empty = auto)")
        labeledEdit("/data/adb/ksu/bin/su", Settings.suPath) { v ->
            Settings.suPath = v
            // Force re-detect on next root use
            Toast.makeText(
                context,
                if (v.isBlank()) "Su path: auto" else "Su path: $v (restart may be needed)",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Include system processes
        section("Process list")
        val sysBtn = Button(context).apply {
            text = if (Settings.includeSystemProcesses) "System processes: ON" else "System processes: OFF"
            setOnClickListener {
                Settings.includeSystemProcesses = !Settings.includeSystemProcesses
                text = if (Settings.includeSystemProcesses) "System processes: ON" else "System processes: OFF"
            }
        }
        inner.addView(sysBtn)

        // Search timeout
        section("Search timeout (seconds)")
        labeledEdit("5 – 300", (Settings.searchTimeoutMs / 1000).toString()) { v ->
            v.toLongOrNull()?.let {
                Settings.searchTimeoutMs = it * 1000
                Toast.makeText(context, "Timeout: ${Settings.searchTimeoutMs / 1000}s", Toast.LENGTH_SHORT).show()
            }
        }

        // Panel opacity
        section("Panel opacity (0.5 – 1.0)")
        labeledEdit("0.95", Settings.panelOpacity.toString()) { v ->
            v.toFloatOrNull()?.let {
                Settings.panelOpacity = it
                panelView?.alpha = Settings.panelOpacity
                Toast.makeText(context, "Opacity: ${Settings.panelOpacity}", Toast.LENGTH_SHORT).show()
            }
        }

        // Reset
        section("Reset")
        val resetBtn = Button(context).apply {
            text = "Reset all to defaults"
            setOnClickListener {
                Settings.resetToDefaults()
                selectedValueType = Settings.defaultValueType
                Toast.makeText(context, "Settings reset", Toast.LENGTH_SHORT).show()
                switchTab(3) // rebuild tab
            }
        }
        inner.addView(resetBtn)

        // Info
        val info = TextView(context).apply {
            text = "Root su: ${try { RootAccess.get().getSuPath() ?: "none" } catch (_: Exception) { "?" }}"
            setTextColor(0xFF888888.toInt())
            textSize = 11f
            setPadding(0, dp(12), 0, 0)
        }
        inner.addView(info)

        scroll.addView(inner)
        container.addView(scroll)
    }

    // ---- Script tab ----

    private var etScript: EditText? = null

    private fun buildScriptTab(container: LinearLayout) {
        val hint = TextView(context).apply {
            text = "Lua script (GG API)"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(0, 0, 0, dp(4))
        }
        container.addView(hint)

        etScript = EditText(context).apply {
            hint = "gg.searchNumber(\"100\", gg.TYPE_DWORD)"
            setTextColor(0xFFEEEEEE.toInt())
            setHintTextColor(0xFF666666.toInt())
            minLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setText(
                """-- Example
gg.clearResults()
local n = gg.searchNumber("100", gg.TYPE_DWORD)
print("found: " .. tostring(n))
print("count: " .. gg.getResultsCount())
""".trimIndent()
            )
        }
        container.addView(etScript)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        val btnRun = Button(context).apply {
            text = "Run"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { runScript() }
        }
        val btnStop = Button(context).apply {
            text = "Stop"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                LuaAccess.get().stop()
                statusText?.text = buildStatusLine() + "  ·  stopped"
            }
        }
        btnRow.addView(btnRun)
        btnRow.addView(btnStop)
        container.addView(btnRow)
    }

    private fun runScript() {
        if (ProcessAccess.get().getCurrentPid() == null) {
            Toast.makeText(context, "Attach to a process first", Toast.LENGTH_SHORT).show()
            return
        }
        val code = etScript?.text?.toString().orEmpty()
        if (code.isBlank()) {
            Toast.makeText(context, "Empty script", Toast.LENGTH_SHORT).show()
            return
        }
        statusText?.text = "Running script…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                LuaAccess.get().execute(code)
            }
            if (result.success) {
                val msg = result.output.ifBlank { "OK" }
                statusText?.text = buildStatusLine() + "  ·  script OK"
                Toast.makeText(context, msg.take(200), Toast.LENGTH_LONG).show()
            } else {
                statusText?.text = buildStatusLine() + "  ·  script error"
                Toast.makeText(context, result.error ?: "Error", Toast.LENGTH_LONG).show()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private fun loadProcesses() {
        statusText?.text = "Loading processes…"
        scope.launch {
            val includeSys = try { Settings.includeSystemProcesses } catch (_: Exception) { false }
            val list = withContext(Dispatchers.IO) {
                ProcessAccess.get().listProcesses(includeSystem = includeSys)
            }
            processAdapter?.submit(list)
            statusText?.text = buildStatusLine() + "  ·  ${list.size} processes"
        }
    }

    private fun onProcessSelected(info: ProcessInfo) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { ProcessAccess.get().attach(info.pid) }
            Toast.makeText(
                context,
                if (ok) "Attached: ${info.displayName} (${info.pid})"
                else "Attach failed: ${info.pid}",
                Toast.LENGTH_SHORT
            ).show()
            statusText?.text = buildStatusLine()
        }
    }

    private fun testRead() {
        val pid = ProcessAccess.get().getCurrentPid()
        if (pid == null) {
            Toast.makeText(context, "Attach to a process first", Toast.LENGTH_SHORT).show()
            return
        }
        val addrText = etAddress?.text?.toString()?.trim()
            ?.removePrefix("0x")?.removePrefix("0X") ?: return
        val address = addrText.toLongOrNull(16)
        if (address == null) {
            Toast.makeText(context, "Invalid address", Toast.LENGTH_SHORT).show()
            return
        }
        statusText?.text = "Reading…"
        scope.launch {
            val data = withContext(Dispatchers.IO) {
                MemoryAccess.get().read(pid, address, 16)
            }
            if (data != null) {
                etValue?.setText(data.joinToString(" ") { "%02X".format(it) })
                statusText?.text = buildStatusLine() + "  ·  Read OK"
            } else {
                etValue?.setText("")
                statusText?.text = buildStatusLine() + "  ·  Read failed"
            }
        }
    }

    private fun doFirstScan() {
        if (ProcessAccess.get().getCurrentPid() == null) {
            Toast.makeText(context, "Attach to a process first", Toast.LENGTH_SHORT).show()
            return
        }
        val value = etSearchValue?.text?.toString()?.trim().orEmpty()
        if (value.isEmpty()) {
            Toast.makeText(context, "Enter a value", Toast.LENGTH_SHORT).show()
            return
        }
        statusText?.text = "Scanning…"
        val type = selectedValueType
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                SearchAccess.get().firstScan(SearchRequest(value, type))
            }
            selectedAddress = null
            tvSelected?.text = "No selection"
            resultAdapter?.submit(result.matches)
            statusText?.text = buildStatusLine() + "  ·  ${result.count} results"
            Toast.makeText(context, "Found ${result.count}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun doRefine() {
        if (ProcessAccess.get().getCurrentPid() == null) {
            Toast.makeText(context, "Attach to a process first", Toast.LENGTH_SHORT).show()
            return
        }
        if (SearchAccess.get().getResultCount() == 0) {
            Toast.makeText(context, "No results to refine – run New Scan first", Toast.LENGTH_SHORT).show()
            return
        }
        val value = etSearchValue?.text?.toString()?.trim().orEmpty()
        if (value.isEmpty()) {
            Toast.makeText(context, "Enter a value", Toast.LENGTH_SHORT).show()
            return
        }
        statusText?.text = "Refining…"
        val type = selectedValueType
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                SearchAccess.get().refine(SearchRequest(value, type))
            }
            selectedAddress = null
            tvSelected?.text = "No selection"
            resultAdapter?.submit(result.matches)
            statusText?.text = buildStatusLine() + "  ·  ${result.count} results"
            Toast.makeText(context, "Refined to ${result.count}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onResultClicked(address: Long) {
        val pid = ProcessAccess.get().getCurrentPid() ?: return
        selectedAddress = address
        scope.launch {
            val type = selectedValueType
            val size = when (type) {
                ValueType.BYTE -> 1
                ValueType.WORD -> 2
                ValueType.DWORD, ValueType.FLOAT -> 4
                ValueType.QWORD, ValueType.DOUBLE -> 8
                else -> 4
            }
            val data = withContext(Dispatchers.IO) {
                MemoryAccess.get().read(pid, address, size)
            }
            val display = if (data != null) formatValue(data, type) else "??"
            tvSelected?.text = "0x${address.toString(16).uppercase()} = $display"
            etEditValue?.setText(display)
            statusText?.text = buildStatusLine()
        }
    }

    private fun editSelected() {
        val pid = ProcessAccess.get().getCurrentPid()
        val address = selectedAddress
        if (pid == null || address == null) {
            Toast.makeText(context, "Select a result first", Toast.LENGTH_SHORT).show()
            return
        }
        val textVal = etEditValue?.text?.toString()?.trim().orEmpty()
        if (textVal.isEmpty()) {
            Toast.makeText(context, "Enter a new value", Toast.LENGTH_SHORT).show()
            return
        }
        val type = selectedValueType
        statusText?.text = "Writing…"
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val bytes = valueToBytes(textVal, type) ?: return@withContext false
                MemoryAccess.get().write(pid, address, bytes)
            }
            if (ok) {
                Toast.makeText(context, "Edited 0x${address.toString(16).uppercase()}", Toast.LENGTH_SHORT).show()
                // refresh displayed value
                onResultClicked(address)
            } else {
                Toast.makeText(context, "Write failed", Toast.LENGTH_SHORT).show()
                statusText?.text = buildStatusLine() + "  ·  write failed"
            }
        }
    }

    private fun formatValue(data: ByteArray, type: ValueType): String {
        if (data.isEmpty()) return ""
        val buf = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
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

    private fun valueToBytes(value: String, type: ValueType): ByteArray? {
        val v = value.trim()
        if (v.isEmpty()) return null
        return try {
            val radix = if (v.startsWith("0x", true) || v.any { it in 'a'..'f' || it in 'A'..'F' }) 16 else 10
            when (type) {
                ValueType.BYTE -> byteArrayOf(v.toInt(radix).toByte())
                ValueType.WORD -> {
                    val s = v.toInt(radix).toShort()
                    java.nio.ByteBuffer.allocate(2).order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(s).array()
                }
                ValueType.DWORD -> {
                    val i = v.toLong(radix).toInt()
                    java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(i).array()
                }
                ValueType.QWORD -> {
                    val l = v.toLong(radix)
                    java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(l).array()
                }
                ValueType.FLOAT -> {
                    java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putFloat(v.toFloat()).array()
                }
                ValueType.DOUBLE -> {
                    java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(v.toDouble()).array()
                }
                else -> v.toByteArray(Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }


    private fun collapse() {
        isExpanded = false
        removePanel()
        showBubble()
    }

    private fun buildStatusLine(): String {
        val rootOk = RootAccess.get().isRootAvailable()
        val pid = ProcessAccess.get().getCurrentPid()
        val results = SearchAccess.get().getResultCount()
        return buildString {
            append(if (rootOk) "Root: OK" else "Root: NO")
            if (pid != null) append("  ·  PID $pid") else append("  ·  Not attached")
            if (results > 0) append("  ·  $results hits")
        }
    }

    // -------------------------------------------------------------------------
    // View helpers
    // -------------------------------------------------------------------------

    private fun removeAllViews() { removeBubble(); removePanel() }

    private fun removeBubble() {
        bubbleView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        bubbleView = null
    }

    private fun removePanel() {
        panelView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        panelView = null
        contentContainer = null
        processAdapter = null
        resultAdapter = null
        statusText = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(handle: View, target: View) {
        var lastX = 0; var lastY = 0; var startX = 0; var startY = 0
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX.toInt(); lastY = event.rawY.toInt()
                    startX = panelParams.x; startY = panelParams.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = startX + (event.rawX.toInt() - lastX)
                    panelParams.y = startY + (event.rawY.toInt() - lastY)
                    try { windowManager.updateViewLayout(target, panelParams) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    private fun ovalDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun roundedDrawable(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, radiusDp, context.resources.displayMetrics
        )
    }
}

// ---- Adapters ----

private class ProcessAdapter(
    private val onClick: (ProcessInfo) -> Unit
) : RecyclerView.Adapter<ProcessAdapter.Holder>() {
    private val items = ArrayList<ProcessInfo>()
    fun submit(list: List<ProcessInfo>) {
        items.clear(); items.addAll(list); notifyDataSetChanged()
    }
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val tv = TextView(parent.context).apply {
            setTextColor(0xFFEEEEEE.toInt()); textSize = 13f
            setPadding(dp(parent, 12), dp(parent, 10), dp(parent, 12), dp(parent, 10))
        }
        return Holder(tv)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val info = items[position]
        val arch = if (info.is64Bit) "64" else "32"
        holder.text.text = "${info.displayName}  ·  ${info.pid}  ·  $arch"
        holder.text.setOnClickListener { onClick(info) }
    }
    override fun getItemCount() = items.size
    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    private fun dp(parent: android.view.ViewGroup, v: Int) =
        (v * parent.resources.displayMetrics.density).toInt()
}

private class ResultAdapter(
    private val onClick: (Long) -> Unit
) : RecyclerView.Adapter<ResultAdapter.Holder>() {
    private val items = ArrayList<Long>()
    fun submit(list: List<Long>) {
        items.clear()
        val cap = try { com.gg.core.settings.Settings.maxResults } catch (_: Exception) { 500 }
        items.addAll(list.take(cap))
        notifyDataSetChanged()
    }
    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
        val tv = TextView(parent.context).apply {
            setTextColor(0xFF80CBC4.toInt()); textSize = 13f
            setPadding(dp(parent, 12), dp(parent, 8), dp(parent, 12), dp(parent, 8))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        return Holder(tv)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val addr = items[position]
        holder.text.text = "0x${addr.toString(16).uppercase()}"
        holder.text.setOnClickListener { onClick(addr) }
    }
    override fun getItemCount() = items.size
    class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    private fun dp(parent: android.view.ViewGroup, v: Int) =
        (v * parent.resources.displayMetrics.density).toInt()
}
