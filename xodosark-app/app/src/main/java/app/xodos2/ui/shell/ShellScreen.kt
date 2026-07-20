package app.xodos2.ui.shell

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import app.xodos2.ui.glass.GlassButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import app.xodos2.ui.glassDialogStyle
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.xodos2.NativeBridge
import app.xodos2.PtyOutputRelay
import app.xodos2.R
import app.xodos2.RustPtySession
import app.xodos2.shell.ShellFonts
import app.xodos2.shell.ShellSessionClient
import app.xodos2.shell.ShellViewClient
import app.xodos2.wayland.input.InputRouteState
import com.termux.view.TerminalView
import org.json.JSONArray

private val DEFAULT_EXTRA_KEYS_JSON = """
[
  ["ESC", "/", "-", "HOME", "UP", "END", "PASTE"],
  ["TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN"]
]
""".trimIndent()

private class ViewCache(
    val controller: ShellSessionController,
    val keysContainer: LinearLayout,
    val fixedContainer: LinearLayout,
    val viewClient: ShellViewClient,
    var appliedExtraKeysJson: String? = null
)

@Composable
fun ShellScreen(
    terminalFontKey: String,
    activeSessionId: Int,
    terminalSessionIds: List<Int>,
    rendererSessionResetEpoch: Int,
    showKeyboardTrigger: Int,
    onKeyboardTriggerConsumed: () -> Unit = {},
    activeSessionHasRootfs: Boolean = true,
    isTerminalFront: Boolean = true,
    onCloseCurrentSession: () -> Unit = {},
    onBackPressed: () -> Unit = {},
    onExitRequested: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = remember { context as Activity }
    val imm = remember(context) {
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    val sharedPrefs = remember {
        context.getSharedPreferences("xodos2_terminal_prefs", Context.MODE_PRIVATE)
    }
    val lifecycleOwner = LocalLifecycleOwner.current

    var showCloseSessionDialog by remember { mutableStateOf(false) }
    var showExtraKeysEditor by remember { mutableStateOf(false) }
    var extraKeysJson by remember {
        mutableStateOf(sharedPrefs.getString("extra_keys_layout", DEFAULT_EXTRA_KEYS_JSON) ?: DEFAULT_EXTRA_KEYS_JSON)
    }

    if (showCloseSessionDialog) {
        AlertDialog(
            onDismissRequest = { showCloseSessionDialog = false },
            title = { Text("Close terminal") },
            text = {
                Text(
                    if (terminalSessionIds.size <= 1) "Are you sure you want to exit?"
                    else "Close current terminal session?"
                )
            },
            confirmButton = {
                GlassButton(onClick = {
                    showCloseSessionDialog = false
                    if (terminalSessionIds.size <= 1) {
                        onExitRequested()
                    } else {
                        onCloseCurrentSession()
                    }
                }) { Text(if (terminalSessionIds.size <= 1) "Exit" else "Close", color = ComposeColor(0xFFC3B6F9), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                GlassButton(onClick = { showCloseSessionDialog = false }) { Text("Cancel", color = ComposeColor.White.copy(alpha = 0.8f)) }
            }
        )
    }

    if (showExtraKeysEditor) {
        var editingJson by remember { mutableStateOf(extraKeysJson) }
        var errorText by remember { mutableStateOf("") }
        
        AlertDialog(
            onDismissRequest = { showExtraKeysEditor = false },
            containerColor = ComposeColor.Transparent,
            modifier = Modifier.glassDialogStyle(),
            title = { Text("Edit Extra Keys", fontWeight = FontWeight.Bold, color = ComposeColor.White) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editingJson,
                        onValueChange = { 
                            editingJson = it
                            errorText = "" 
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ComposeColor(0xFFC3B6F9),
                            unfocusedBorderColor = ComposeColor.White.copy(alpha = 0.2f),
                            focusedLabelColor = ComposeColor(0xFFC3B6F9),
                            unfocusedLabelColor = ComposeColor.White.copy(alpha = 0.5f),
                            focusedTextColor = ComposeColor.White,
                            unfocusedTextColor = ComposeColor.White
                        )
                    )
                    Text(
                        text = "Supported keys: CTRL, ALT, ESC, TAB, HOME, END, PGUP, PGDN, UP, DOWN, LEFT, RIGHT, COPY, PASTE, or any single character (e.g., '-', '/').\n\nMust be a valid 2D JSON Array.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ComposeColor.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    if (errorText.isNotEmpty()) {
                        Text(
                            text = errorText, 
                            color = ComposeColor(0xFFF44336), 
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                GlassButton(
                    onClick = {
                        try {
                            JSONArray(editingJson) 
                            extraKeysJson = editingJson
                            sharedPrefs.edit().putString("extra_keys_layout", editingJson).apply()
                            showExtraKeysEditor = false
                        } catch (e: Exception) {
                            errorText = "Invalid JSON format: ${e.localizedMessage}"
                        }
                    }
                ) { 
                    Text("Save", fontWeight = FontWeight.Bold, color = ComposeColor(0xFFC3B6F9)) 
                }
            },
            dismissButton = {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    GlassButton(
                        onClick = {
                            editingJson = DEFAULT_EXTRA_KEYS_JSON
                        }
                    ) { 
                        Text("Reset", color = ComposeColor(0xFFC3B6F9)) 
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    GlassButton(
                        onClick = { showExtraKeysEditor = false }
                    ) { 
                        Text("Close", color = ComposeColor.White.copy(alpha = 0.8f)) 
                    }
                }
            }
        )
    }

    val defaultTextSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        14f,
        context.resources.displayMetrics
    ).toInt().coerceAtLeast(1)

    var currentTextSize by remember { mutableIntStateOf(defaultTextSizePx) }
    val saveHandler = remember { Handler(Looper.getMainLooper()) }
    
    val saveRunnable = remember {
        Runnable { sharedPrefs.edit().putInt("terminal_zoom_size", currentTextSize).apply() }
    }

    fun scheduleZoomSave() {
        saveHandler.removeCallbacks(saveRunnable)
        saveHandler.postDelayed(saveRunnable, 500L)
    }

    fun saveZoomImmediately() {
        saveHandler.removeCallbacks(saveRunnable)
        sharedPrefs.edit().putInt("terminal_zoom_size", currentTextSize).apply()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                saveZoomImmediately()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            saveZoomImmediately()
            PtyOutputRelay.unbind()
            InputRouteState.shellTerminalView = null
        }
    }

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding(),
        factory = { ctx ->
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            val tv = TerminalView(ctx, null)
            val controller = ShellSessionController(ctx, tv)
            val viewClient = ShellViewClient(tv)

            val wrappedClient = object : com.termux.view.TerminalViewClient by viewClient {
                override fun onScale(scale: Float): Float {
                    val newScale = viewClient.onScale(scale)
                    currentTextSize = tv.mRenderer.getTextSizePx()
                    scheduleZoomSave()
                    return newScale
                }
            }
            tv.setTerminalViewClient(wrappedClient)

            val savedTextSize = sharedPrefs.getInt("terminal_zoom_size", defaultTextSizePx)
            tv.setTextSize(savedTextSize)
            currentTextSize = savedTextSize

            tv.setTypeface(ShellFonts.typefaceForPref(ctx, terminalFontKey))
            InputRouteState.shellTerminalView = tv
            tv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> tv.post { tv.updateSize() } }
            tv.post { tv.updateSize() }
            tv.isFocusable = true
            tv.isFocusableInTouchMode = true
            tv.keepScreenOn = true

            tv.setBackgroundColor(Color.BLACK)

            // ---------- CONTEXT MENU FIX ----------
            tv.setOnCreateContextMenuListener { menu, _, _ ->
                menu.setHeaderTitle("Terminal Options")
                menu.add(0, 1, 0, "Copy Transcript").setOnMenuItemClickListener {
                    val transcript = tv.currentSession?.emulator?.screen?.transcriptText?.trimEnd()
                    if (!transcript.isNullOrEmpty()) {
                        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("Term", transcript))
                    }
                    true
                }
                menu.add(0, 2, 0, "Reset Terminal").setOnMenuItemClickListener {
                    tv.currentSession?.write("\u001Bc") // ANSI Reset Sequence
                    true
                }
            }

            val terminalFrame = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            terminalFrame.addView(
                tv,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            )
            root.addView(terminalFrame)

            // ---------- Extra keys bar (tight fit) ----------
            val bottomBarLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT 
                )
                val barDrawable = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        Color.parseColor("#E6080512"), // 90% opaque dark violet-slate background
                        Color.parseColor("#FA030207")  // 98% opaque bottom
                    )
                ).apply {
                    setStroke(1.dpToPx(ctx), Color.parseColor("#22FFFFFF")) // premium subtle thin top-highlight
                }
                background = barDrawable
                setPadding(0, 4.dpToPx(ctx), 0, 4.dpToPx(ctx))
            }

            val keysScroll = HorizontalScrollView(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isHorizontalScrollBarEnabled = false
                isFillViewport = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            
            val keysContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                // Only a small horizontal padding, no vertical
                setPadding(2.dpToPx(ctx), 0, 2.dpToPx(ctx), 0)
            }
            keysScroll.addView(keysContainer)
            bottomBarLayout.addView(keysScroll)

            val fixedContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                // No top padding, tiny right padding
                setPadding(0, 0, 2.dpToPx(ctx), 0)
            }
            bottomBarLayout.addView(fixedContainer)

            root.addView(bottomBarLayout)

            val cache = ViewCache(controller, keysContainer, fixedContainer, viewClient)
            root.tag = cache
            root.setTag(R.id.xodos2_shell_controller, controller)
            
            root
        },
        update = { root ->
            val cache = root.tag as ViewCache
            val controller = cache.controller
            
            val prevEpoch = root.getTag(R.id.xodos2_renderer_session_epoch) as? Int ?: 0
            if (rendererSessionResetEpoch != prevEpoch) {
                root.setTag(R.id.xodos2_renderer_session_epoch, rendererSessionResetEpoch)
                controller.invalidateAllSessions()
            }
            controller.pruneSessionsExcept(terminalSessionIds.toSet())
            controller.attachSessionIfNeeded(activeSessionId)

            val terminalFrame = root.getChildAt(0) as FrameLayout
            val tv = terminalFrame.getChildAt(0) as TerminalView

            val appliedFont = root.getTag(R.id.xodos2_terminal_font_applied) as? String
            if (appliedFont != terminalFontKey) {
                root.setTag(R.id.xodos2_terminal_font_applied, terminalFontKey)
                tv.setTypeface(ShellFonts.typefaceForPref(tv.context, terminalFontKey))
            }
            
            if (cache.appliedExtraKeysJson != extraKeysJson) {
                cache.appliedExtraKeysJson = extraKeysJson
                rebuildExtraKeys(
                    tv.context, 
                    tv, 
                    cache.viewClient, 
                    cache.keysContainer, 
                    cache.fixedContainer,
                    extraKeysJson
                ) {
                    showExtraKeysEditor = true
                }
            }

            if (showKeyboardTrigger > 0) {
                tv.post {
                    tv.requestFocus()
                    imm.showSoftInput(tv, InputMethodManager.SHOW_IMPLICIT)
                }
                onKeyboardTriggerConsumed()
            }

            tv.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                    if (!isTerminalFront) onBackPressed()
                    else if (activeSessionHasRootfs) showCloseSessionDialog = true
                    else onExitRequested()
                    true
                } else false
            }
        }
    )
}

private fun setRepeatClickListener(view: TextView, onClick: () -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    var isHolding = false
    val initialDelay = 400L
    val repeatInterval = 50L

    val runnable = object : Runnable {
        override fun run() {
            if (isHolding) {
                onClick()
                handler.postDelayed(this, repeatInterval)
            }
        }
    }

    view.setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isHolding = true
                v.isPressed = true
                onClick()
                handler.postDelayed(runnable, initialDelay)
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isHolding = false
                v.isPressed = false
                handler.removeCallbacks(runnable)
                true
            }
            else -> false
        }
    }
}

private fun rebuildExtraKeys(
    context: Context,
    terminalView: TerminalView,
    viewClient: ShellViewClient,
    scrollParent: LinearLayout,
    fixedParent: LinearLayout,
    jsonString: String,
    onEditClicked: () -> Unit
) {
    scrollParent.removeAllViews()
    fixedParent.removeAllViews()

    var ctrlButton: TextView? = null
    var altButton: TextView? = null

    val textColor = Color.parseColor("#E9D5FF") // elegant light purple-white
    val activeTextColor = Color.parseColor("#FFD700") // golden yellow for active modifiers

    fun createNormalDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            Color.parseColor("#22FFFFFF"), // 13% white highlight at top for reflection
            Color.parseColor("#08FFFFFF"), // 3% white at middle
            Color.parseColor("#15000000"), // 8% black shadow at bottom for depth
        )
    ).apply {
        shape = GradientDrawable.RECTANGLE
        setStroke(1.dpToPx(context), Color.parseColor("#3BFFFFFF")) // 23% white glassy rim
        cornerRadius = 8f * context.resources.displayMetrics.density
    }

    fun createPressedDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            Color.parseColor("#5AFFFFFF"), // 35% white highlight at top
            Color.parseColor("#1EFFFFFF"), // 12% white at middle
            Color.parseColor("#00000000"), // transparent at bottom
        )
    ).apply {
        shape = GradientDrawable.RECTANGLE
        setStroke(1.dpToPx(context), Color.parseColor("#80FFFFFF")) // 50% white border
        cornerRadius = 8f * context.resources.displayMetrics.density
    }

    fun createActiveDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(
            Color.parseColor("#E67C3AED"), // 90% opaque vibrant violet
            Color.parseColor("#CC4C1D95")  // 80% opaque deep purple
        )
    ).apply {
        shape = GradientDrawable.RECTANGLE
        setStroke(1.dpToPx(context), Color.parseColor("#FFD700")) // Golden border
        cornerRadius = 8f * context.resources.displayMetrics.density
    }

    fun createDefaultStateList(): android.graphics.drawable.StateListDrawable = 
        android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), createPressedDrawable())
            addState(intArrayOf(), createNormalDrawable())
        }

    fun updateButtonColors() {
        ctrlButton?.let { btn ->
            btn.setTextColor(if (viewClient.ctrlActive) activeTextColor else textColor)
            btn.background = if (viewClient.ctrlActive) createActiveDrawable() else createDefaultStateList()
        }
        altButton?.let { btn ->
            btn.setTextColor(if (viewClient.altActive) activeTextColor else textColor)
            btn.background = if (viewClient.altActive) createActiveDrawable() else createDefaultStateList()
        }
    }

    viewClient.onModifierReset = { terminalView.post { updateButtonColors() } }

    fun makeButton(label: String, isTransparent: Boolean = false, isFixed: Boolean = false): TextView {
        // Dynamic font size for long labels
        val baseTextSize = 12f
        val scaledTextSize = when {
            label.length <= 3 -> baseTextSize
            label.length <= 5 -> baseTextSize * 0.85f
            else            -> baseTextSize * 0.7f
        }

        val drawable = if (label == "CTRL") {
            if (viewClient.ctrlActive) createActiveDrawable() else createDefaultStateList()
        } else if (label == "ALT") {
            if (viewClient.altActive) createActiveDrawable() else createDefaultStateList()
        } else {
            createDefaultStateList()
        }

        return TextView(context).apply {
            text = label
            setTextColor(textColor)
            background = drawable
            textSize = scaledTextSize
            includeFontPadding = false
            isAllCaps = false
            isClickable = !isTransparent
            isFocusable = !isTransparent
            
            if (isTransparent) {
                visibility = android.view.View.INVISIBLE
            }

            layoutParams = LinearLayout.LayoutParams(
                if (isFixed) ViewGroup.LayoutParams.WRAP_CONTENT else 0,
                ViewGroup.LayoutParams.MATCH_PARENT, 
                if (isFixed) 0f else 1f
            ).apply {
                setMargins(2.dpToPx(context), 0, 2.dpToPx(context), 0)
            }
            
            if (isFixed) {
                setPadding(8.dpToPx(context), 4.dpToPx(context), 8.dpToPx(context), 4.dpToPx(context))
            } else {
                setPadding(0, 4.dpToPx(context), 0, 4.dpToPx(context))
            }
            
            gravity = Gravity.CENTER
        }
    }
    
    fun getActionFor(key: String): String {
        return when (key.uppercase()) {
            "CTRL" -> "CTRL_MOD"
            "ALT" -> "ALT_MOD"
            "ESC" -> "\u001B"
            "TAB" -> "\u0009"
            "HOME" -> "\u001B[H"
            "END" -> "\u001B[F"
            "UP" -> "\u001B[A"
            "DOWN" -> "\u001B[B"
            "LEFT" -> "\u001B[D"
            "RIGHT" -> "\u001B[C"
            "PGUP" -> "\u001B[5~"
            "PGDN" -> "\u001B[6~"
            "COPY" -> "SPECIAL_COPY"
            "PASTE" -> "SPECIAL_PASTE"
            else -> key
        }
    }

    fun getDisplayLabel(key: String): String {
        return when (key.uppercase()) {
            "UP" -> "↑"
            "DOWN" -> "↓"
            "LEFT" -> "←"
            "RIGHT" -> "→"
            else -> key
        }
    }

    // Fixed row height to guarantee identical height for every row
    val rowHeight = 44.dpToPx(context)

    fun addScrollRow(keys: List<String>): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
            )
        }
        
        for (label in keys) {
            val displayLabel = getDisplayLabel(label)
            val btn = makeButton(displayLabel)
            val action = getActionFor(label)
            
            when (action) {
                "CTRL_MOD" -> {
                    ctrlButton = btn
                    btn.setOnClickListener {
                        viewClient.ctrlActive = !viewClient.ctrlActive
                        if (viewClient.ctrlActive) viewClient.altActive = false
                        updateButtonColors()
                    }
                }
                "ALT_MOD" -> {
                    altButton = btn
                    btn.setOnClickListener {
                        viewClient.altActive = !viewClient.altActive
                        if (viewClient.altActive) viewClient.ctrlActive = false
                        updateButtonColors()
                    }
                }
                "SPECIAL_COPY" -> btn.setOnClickListener { terminalView.showContextMenu() }
                "SPECIAL_PASTE" -> {
                    btn.setOnClickListener {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.primaryClip?.let { clip ->
                            if (clip.itemCount > 0) {
                                val textToPaste = clip.getItemAt(0).text?.toString() ?: ""
                                terminalView.currentSession?.write(textToPaste)
                            }
                        }
                    }
                }
                else -> {
                    val fireAction = {
                        sendModifiedSequence(
                            terminalView, action, viewClient.ctrlActive, viewClient.altActive,
                            onConsumed = {
                                viewClient.ctrlActive = false
                                viewClient.altActive = false
                                updateButtonColors()
                            }
                        )
                    }

                    if (label.uppercase() in listOf("UP", "DOWN", "LEFT", "RIGHT")) {
                        setRepeatClickListener(btn, fireAction)
                    } else {
                        btn.setOnClickListener { fireAction() }
                    }
                }
            }
            row.addView(btn)
        }
        return row
    }

    try {
        val jsonArray = JSONArray(jsonString)
        for (i in 0 until jsonArray.length()) {
            val rowArray = jsonArray.getJSONArray(i)
            val rowKeys = mutableListOf<String>()
            for (j in 0 until rowArray.length()) {
                rowKeys.add(rowArray.getString(j))
            }
            scrollParent.addView(addScrollRow(rowKeys))
            
            // Fixed column row – same height as scroll rows
            val fixedRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, rowHeight
                )
            }
            
            if (i == jsonArray.length() - 1) {
                val editBtn = makeButton("⚙️", isTransparent = false, isFixed = true)
                editBtn.setOnClickListener { onEditClicked() }
                fixedRow.addView(editBtn)
            } else {
                // Placeholder with same height as the corresponding row's first button
                val placeholderText = getDisplayLabel(rowKeys.firstOrNull() ?: "ESC")
                val spaceBtn = makeButton(placeholderText, isTransparent = true, isFixed = true)
                fixedRow.addView(spaceBtn)
            }
            fixedParent.addView(fixedRow)
        }
    } catch (_: Exception) {
    }
}

private fun sendModifiedSequence(
    terminalView: TerminalView,
    baseSequence: String,
    ctrlActive: Boolean,
    altActive: Boolean,
    onConsumed: () -> Unit
) {
    val session = terminalView.currentSession ?: return
    val text = buildString {
        if (altActive) append('\u001B')
        if (ctrlActive && baseSequence.length == 1) {
            val ch = baseSequence[0]
            append((ch.code and 0x1F).toChar())
        } else {
            append(baseSequence)
        }
    }
    session.write(text)
    onConsumed()
}

private fun Int.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()

private class ShellSessionController(
    private val context: Context,
    private val terminalView: TerminalView
) {
    private val clients = mutableMapOf<Int, ShellSessionClient>()
    private val sessions = mutableMapOf<Int, RustPtySession>()
    private var attachedId: Int = -1

    fun sessionFor(id: Int): RustPtySession {
        return sessions.getOrPut(id) {
            val client = clients.getOrPut(id) { ShellSessionClient(context, terminalView, id) }
            RustPtySession(context, client, terminalView, id)
        }
    }

    fun attachSessionIfNeeded(id: Int) {
        if (id == attachedId) return
        attachedId = id
        val s = sessionFor(id)
        terminalView.attachSession(s)
        PtyOutputRelay.bind(s, terminalView)
        terminalView.post { terminalView.updateSize() }
    }

    fun pruneSessionsExcept(keep: Set<Int>) {
        val removed = sessions.keys.filter { it !in keep }
        for (id in removed) {
            NativeBridge.closeSession(id)
            sessions.remove(id)
            clients.remove(id)
            PtyOutputRelay.discardSessionQueue(id)
        }
        if (attachedId !in keep) {
            attachedId = -1
        }
    }

    fun invalidateAllSessions() {
        for (id in sessions.keys.toList()) {
            NativeBridge.closeSession(id)
            sessions.remove(id)
            clients.remove(id)
            PtyOutputRelay.discardSessionQueue(id)
        }
        attachedId = -1
    }
}