package app.xodos2.ui.shell

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.xodos2.NativeBridge
import app.xodos2.PtyOutputRelay
import app.xodos2.R
import app.xodos2.RustPtySession
import app.xodos2.TerminalSessionIds
import app.xodos2.X11Runtime
import app.xodos2.shell.ShellFonts
import app.xodos2.shell.ShellSessionClient
import app.xodos2.shell.ShellViewClient
import app.xodos2.ui.glass.GlassButton
import app.xodos2.ui.glassDialogStyle
import app.xodos2.wayland.input.InputRouteState
import com.termux.view.TerminalView
import com.termux.x11.EmbeddedX11Controller
import com.termux.x11.LorieView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.ui.graphics.Color as ComposeColor

private val DEFAULT_EXTRA_KEYS_JSON = """
[
  ["ESC", "/", "-", "HOME", "UP", "END", "PASTE"],
  ["TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN"]
]
""".trimIndent()

private data class ExtraKeyItem(
    val key: String = "",
    val macro: String = "",
    val display: String = "",
    val meta: String = ""
)

private class ViewCache(
    val controller: ShellSessionController,
    val keysContainer: LinearLayout,
    val fixedContainer: LinearLayout,
    val viewClient: ShellViewClient,
    val bgImageView: AnimatedBackgroundView,
    val x11Container: FrameLayout,
    val solidBgView: FrameLayout,
    val terminalFrame: FrameLayout
) {
    var appliedExtraKeysJson: String? = null

    var cachedProps: TermuxProperties? = null
    var cachedPropsEpoch: Int = -1
    var cachedPropsContainerId: Int = -1

    var x11Controller: EmbeddedX11Controller? = null
    var x11Lorie: BackgroundLorieView? = null
    var x11Attached: Boolean = false
}

private const val TAG = "ShellScreen"

/**
 * Bridge between AppScreen's enter-*-desktop functions and ShellScreen's
 * background X11 LorieView.
 *
 * termux-x11 allows only one attached LorieView per X server process. When
 * the user opens the X11 desktop, MainActivity.onCreate calls
 * LorieView.connect(fd). If our background LorieView is still attached at
 * that moment, the primary gets a stale fd and the server can hang.
 *
 * So before starting MainActivity we call releaseBeforePrimaryAttaches(),
 * which detaches the background LorieView and frees the slot.
 */
object TerminalBackgroundX11 {
    @Volatile private var detach: (() -> Unit)? = null
    fun register(fn: (() -> Unit)?) { detach = fn }
    fun releaseBeforePrimaryAttaches() {
        try { detach?.invoke() } catch (_: Throwable) {}
    }
}

private class BackgroundLorieView(ctx: Context) : LorieView(ctx) {
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { /* no-op */ }
    override fun dispatchKeyEventPreIme(event: KeyEvent): Boolean = false
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean = false
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = false
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = false
    override fun onTouchEvent(event: MotionEvent): Boolean = false
    override fun onGenericMotionEvent(event: MotionEvent): Boolean = false
}

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
    val ioScope = rememberCoroutineScope()

    val propsAppliedSessions = remember { mutableSetOf<Int>() }
    val x11CacheRef = remember { mutableStateOf<ViewCache?>(null) }
    var wasInBackground by remember { mutableStateOf(false) }

    val activeContainerId = remember(activeSessionId) {
        when (TerminalSessionIds.namespaceOf(activeSessionId)) {
            TerminalSessionIds.NS_ARCH   -> 1
            TerminalSessionIds.NS_DEBIAN -> 2
            TerminalSessionIds.NS_WINE   -> 3
            else -> 1
        }
    }

    var extraKeysJson by remember(activeContainerId) {
        val fromFile = TermuxPropertiesLoader.load(context, activeContainerId).extraKeys
        mutableStateOf(
            fromFile
                ?: sharedPrefs.getString("extra_keys_layout", DEFAULT_EXTRA_KEYS_JSON)
                ?: DEFAULT_EXTRA_KEYS_JSON
        )
    }

    var propertiesEpoch by remember { mutableIntStateOf(0) }
    var showCloseSessionDialog by remember { mutableStateOf(false) }
    var showPropertiesEditor by remember { mutableStateOf(false) }

    if (showCloseSessionDialog) {
        AlertDialog(
            onDismissRequest = { showCloseSessionDialog = false },
            containerColor = ComposeColor.Transparent,
            modifier = Modifier.glassDialogStyle(),
            title = { Text("Close terminal", fontWeight = FontWeight.Bold, color = ComposeColor.White) },
            text = {
                Text(
                    if (terminalSessionIds.size <= 1) "Are you sure you want to exit?"
                    else "Close current terminal session?",
                    color = ComposeColor.White.copy(alpha = 0.85f)
                )
            },
            confirmButton = {
                GlassButton(onClick = {
                    showCloseSessionDialog = false
                    if (terminalSessionIds.size <= 1) onExitRequested() else onCloseCurrentSession()
                }) {
                    Text(
                        if (terminalSessionIds.size <= 1) "Exit" else "Close",
                        color = ComposeColor(0xFFC3B6F9), fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                GlassButton(onClick = { showCloseSessionDialog = false }) {
                    Text("Cancel", color = ComposeColor.White.copy(alpha = 0.8f))
                }
            }
        )
    }

    if (showPropertiesEditor) {
        TermuxPropertiesEditorDialog(
            context = context,
            containerId = activeContainerId,
            onDismiss = { showPropertiesEditor = false },
            onSaved = {
                val reloaded = TermuxPropertiesLoader.load(context, activeContainerId)
                extraKeysJson = reloaded.extraKeys
                    ?: sharedPrefs.getString("extra_keys_layout", DEFAULT_EXTRA_KEYS_JSON)
                    ?: DEFAULT_EXTRA_KEYS_JSON
                showPropertiesEditor = false
                propertiesEpoch++
            }
        )
    }

    val defaultTextSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics
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

    DisposableEffect(lifecycleOwner, activeContainerId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    // Do NOT detach X11 here. Merely backgrounding the app
                    // is not a reason to release the slot — the primary
                    // desktop is not grabbing it in that case. The listener
                    // handles the only legitimate detach trigger.
                    wasInBackground = true
                    saveZoomImmediately()
                }
                Lifecycle.Event.ON_RESUME -> {
                    saveZoomImmediately()
                    if (!wasInBackground) return@LifecycleEventObserver
                    wasInBackground = false

                    val cache = x11CacheRef.value ?: return@LifecycleEventObserver
                    ioScope.launch {
                        val props = withContext(Dispatchers.IO) {
                            TermuxPropertiesLoader.load(context, activeContainerId)
                        }
                        if (props.backgroundDisplay != "x11") return@launch
                        withContext(Dispatchers.Main) {
                            if (com.termux.x11.MainActivity.isPrimaryLorieAttached()) {
                                // Primary owns the slot — stay detached.
                                if (cache.x11Attached) detachOurBackgroundX11(cache)
                            } else if (cache.x11Attached && !LorieView.connected()) {
                                // Our fd went stale while away — fresh attach.
                                forceReattachX11(cache, context, activity, ioScope)
                            } else if (!cache.x11Attached) {
                                // We weren't attached — attach.
                                ensureX11Background(cache, true, context, activity, ioScope)
                            }
                            // else: our fd is still healthy — do nothing.
                        }
                    }
                }
                else -> { /* ignore */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // ── React to primary LorieView attach/detach ──
        com.termux.x11.MainActivity.setX11ConnectionListener { connected ->
            activity.runOnUiThread {
                val cache = x11CacheRef.value ?: return@runOnUiThread
                ioScope.launch {
                    val props = withContext(Dispatchers.IO) {
                        TermuxPropertiesLoader.load(context, activeContainerId)
                    }
                    if (props.backgroundDisplay != "x11") return@launch
                    withContext(Dispatchers.Main) {
                        if (connected) {
                            detachOurBackgroundX11(cache)
                        } else {
                            ensureX11Background(cache, true, context, activity, ioScope)
                        }
                    }
                }
            }
        }

        // ── Pre-release hook that AppScreen calls before starting MainActivity ──
        TerminalBackgroundX11.register {
            x11CacheRef.value?.let { detachOurBackgroundX11(it) }
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            com.termux.x11.MainActivity.setX11ConnectionListener(null)
            TerminalBackgroundX11.register(null)
            saveZoomImmediately()
            PtyOutputRelay.unbind()
            InputRouteState.shellTerminalView = null
            propsAppliedSessions.clear()
            x11CacheRef.value?.let { detachOurBackgroundX11(it) }
            x11CacheRef.value = null
        }
    }

    remember(activeContainerId) {
        TermuxPropertiesLoader.seedNativeDefault(context)
    }

    AndroidView(
        modifier = modifier.fillMaxSize().statusBarsPadding().imePadding(),
        factory = { ctx ->
            // Root layering (bottom → top):
            //   [0] bgImageView     — static / animated image (if set)
            //   [1] x11Container    — LorieView (only when X11 bg is active)
            //   [2] solidBgView     — colour overlay (bgColor, alpha respected)
            //   [3] contentColumn   — terminal text + extra-keys bar
            //
            // The colour is ON TOP of X11 so it acts as a readability tint
            // AND keeps the terminal legible during the brief window when
            // X11 is re-fetching its framebuffer.
            val root = FrameLayout(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            }

            // [0] Image / GIF
            val bgImageView = AnimatedBackgroundView(ctx).apply {
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            root.addView(bgImageView)

            // [1] X11 surface container
            val x11Container = FrameLayout(ctx).apply {
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                visibility = View.GONE
            }
            root.addView(x11Container)

            // [2] Colour overlay (default = fully opaque black until update() sets it)
            val solidBgView = FrameLayout(ctx).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            root.addView(solidBgView)

            // [3] Content column
            val contentColumn = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
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
                override fun shouldEnforceCharBasedInput(): Boolean = false
                override fun isTerminalViewSelected(): Boolean = true

                override fun onEmulatorSet() {
                    val session = tv.currentSession ?: return
                    if (session !is RustPtySession) return
                    if (!propsAppliedSessions.add(session.sessionId)) return

                    val cid = when (TerminalSessionIds.namespaceOf(session.sessionId)) {
                        TerminalSessionIds.NS_ARCH   -> 1
                        TerminalSessionIds.NS_DEBIAN -> 2
                        TerminalSessionIds.NS_WINE   -> 3
                        else -> 1
                    }
                    val rawProps = TermuxPropertiesLoader.load(ctx, cid)
                    // Terminal cells stay transparent — colour tinting comes
                    // from solidBgView at z=2, not from cell bg.
                    val props = rawProps.copy(backgroundColor = 0x00000000)
                    TermuxPropertiesLoader.apply(tv, props)
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
            tv.setBackgroundColor(Color.TRANSPARENT)

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
                    tv.currentSession?.write("\u001Bc")
                    true
                }
            }

            val terminalFrame = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
                )
                setBackgroundColor(Color.TRANSPARENT)
            }
            terminalFrame.addView(
                tv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            contentColumn.addView(terminalFrame)

            val bottomBarLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
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
                setPadding(2.dpToPx(ctx), 0, 2.dpToPx(ctx), 0)
            }
            keysScroll.addView(keysContainer)
            bottomBarLayout.addView(keysScroll)

            val fixedContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 0, 2.dpToPx(ctx), 0)
            }
            bottomBarLayout.addView(fixedContainer)

            contentColumn.addView(bottomBarLayout)

            root.addView(
                contentColumn,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            val cache = ViewCache(
                controller = controller,
                keysContainer = keysContainer,
                fixedContainer = fixedContainer,
                viewClient = viewClient,
                bgImageView = bgImageView,
                x11Container = x11Container,
                solidBgView = solidBgView,
                terminalFrame = terminalFrame
            )
            root.tag = cache
            root.setTag(R.id.xodos2_shell_controller, controller)
            x11CacheRef.value = cache

            root
        },
        update = { root ->
            @Suppress("UNUSED_VARIABLE")
            val epoch = propertiesEpoch

            val cache = root.tag as ViewCache
            val controller = cache.controller

            val prevEpoch = root.getTag(R.id.xodos2_renderer_session_epoch) as? Int ?: 0
            if (rendererSessionResetEpoch != prevEpoch) {
                root.setTag(R.id.xodos2_renderer_session_epoch, rendererSessionResetEpoch)
                controller.invalidateAllSessions()
                propsAppliedSessions.clear()
                cache.cachedProps = null
                cache.cachedPropsEpoch = -1
                cache.cachedPropsContainerId = -1
            }
            controller.pruneSessionsExcept(terminalSessionIds.toSet())
            controller.attachSessionIfNeeded(activeSessionId)

            val tv = cache.terminalFrame.getChildAt(0) as TerminalView

            tv.post {
                val props = if (
                    cache.cachedProps == null ||
                    cache.cachedPropsEpoch != propertiesEpoch ||
                    cache.cachedPropsContainerId != activeContainerId
                ) {
                    val raw = TermuxPropertiesLoader.load(root.context, activeContainerId).also {
                        // Force transparent cell bg — tinting lives in solidBgView.
                        TermuxPropertiesLoader.apply(tv, it.copy(backgroundColor = 0x00000000))
                        cache.cachedProps = it
                        cache.cachedPropsEpoch = propertiesEpoch
                        cache.cachedPropsContainerId = activeContainerId
                    }
                    raw
                } else cache.cachedProps!!

                val desiredMode = props.backgroundDisplay.takeIf { it == "x11" }
                val imgPath = props.backgroundImagePath

                // ── [2] Colour overlay ──
                // User-set colour wins. Otherwise a soft 23 % black tint when
                // X11 or image is active (keeps terminal text legible), and
                // solid black otherwise.
                val bgColor: Int = props.backgroundColor
                    ?: if (!imgPath.isNullOrBlank() || desiredMode != null) 0x3B000000
                       else Color.BLACK
                cache.solidBgView.setBackgroundColor(bgColor)

                // TerminalFrame is always transparent; the tint lives above it.
                cache.terminalFrame.setBackgroundColor(Color.TRANSPARENT)

                // ── [0] Image layer ──
                // Show whenever an image is set. X11 sits on top; where X11
                // doesn't paint (fetch blank, or transparent regions), the
                // image shows through.
                if (!imgPath.isNullOrBlank()) {
                    cache.bgImageView.setSource(imgPath)
                    cache.bgImageView.visibility = View.VISIBLE
                } else {
                    cache.bgImageView.clear()
                    cache.bgImageView.visibility = View.GONE
                }

                // ── [1] X11 layer ──
                ensureX11Background(
                    cache = cache,
                    enabled = desiredMode == "x11",
                    context = root.context,
                    activity = activity,
                    scope = ioScope
                )

                // ── Extra-keys bar alpha follows bgColor ──
                val bgAlpha = (bgColor ushr 24) and 0xFF
                val barTop    = (bgAlpha shl 24) or 0x080512
                val barBottom = (bgAlpha shl 24) or 0x030207
                val barDrawable = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(barTop, barBottom)
                ).apply { setStroke(1.dpToPx(root.context), Color.parseColor("#22FFFFFF")) }
                (cache.keysContainer.parent?.parent as? LinearLayout)?.background = barDrawable

                props.extraKeys?.let { fileJson ->
                    if (cache.appliedExtraKeysJson != fileJson) {
                        cache.appliedExtraKeysJson = fileJson
                        rebuildExtraKeys(
                            tv.context, tv, cache.viewClient,
                            cache.keysContainer, cache.fixedContainer, fileJson
                        ) { showPropertiesEditor = true }
                    }
                }
            }

            val appliedFont = root.getTag(R.id.xodos2_terminal_font_applied) as? String
            if (appliedFont != terminalFontKey) {
                root.setTag(R.id.xodos2_terminal_font_applied, terminalFontKey)
                tv.setTypeface(ShellFonts.typefaceForPref(tv.context, terminalFontKey))
            }

            if (cache.appliedExtraKeysJson != extraKeysJson) {
                cache.appliedExtraKeysJson = extraKeysJson
                rebuildExtraKeys(
                    tv.context, tv, cache.viewClient,
                    cache.keysContainer, cache.fixedContainer, extraKeysJson
                ) { showPropertiesEditor = true }
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

// ─────────────────────────────────────────────────────────────
// X11 background surface management
// ─────────────────────────────────────────────────────────────

private fun ensureX11Background(
    cache: ViewCache,
    enabled: Boolean,
    context: Context,
    activity: Activity,
    scope: CoroutineScope
) {
    if (!enabled) {
        cache.x11Container.visibility = View.GONE
        return
    }

    // THE GATE: only one LorieView may own the X11 slot at a time.
    // isPrimaryLorieAttached() asks MainActivity specifically — it does
    // NOT reflect our own background LorieView.
    if (com.termux.x11.MainActivity.isPrimaryLorieAttached()) {
        if (cache.x11Attached) detachOurBackgroundX11(cache)
        cache.x11Container.visibility = View.GONE
        return
    }

    cache.x11Container.visibility = View.VISIBLE
    if (cache.x11Attached) return

    scope.launch(Dispatchers.IO) {
        X11Runtime.ensureX11ServerProcessStarted(context)
    }

    val lv = BackgroundLorieView(context).apply {
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
    }
    cache.x11Lorie = lv

    val frame = FrameLayout(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = false
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }
    frame.addView(
        lv,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    )

    val x11Controller = EmbeddedX11Controller()
    try { x11Controller.setMouseMode(EmbeddedX11Controller.MouseMode.TOUCHPAD) }
    catch (_: Throwable) {}
    try { x11Controller.attach(activity, frame, lv) }
    catch (t: Throwable) { Log.e(TAG, "X11 attach failed", t) }

    cache.x11Controller = x11Controller
    cache.x11Container.removeAllViews()
    cache.x11Container.addView(
        frame,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    )
    cache.x11Attached = true
}

private fun detachOurBackgroundX11(cache: ViewCache) {
    try { cache.x11Controller?.detach() } catch (_: Throwable) {}
    cache.x11Controller = null
    cache.x11Lorie = null
    cache.x11Container.removeAllViews()
    cache.x11Container.visibility = View.GONE
    cache.x11Attached = false
}

private fun forceReattachX11(
    cache: ViewCache,
    context: Context,
    activity: Activity,
    scope: CoroutineScope
) {
    if (cache.x11Attached) detachOurBackgroundX11(cache)
    ensureX11Background(cache, true, context, activity, scope)
}

// ─────────────────────────────────────────────────────────────
// Existing helpers (unchanged)
// ─────────────────────────────────────────────────────────────

private fun isKeyboardVisible(view: View): Boolean {
    if (view.rootView == null) return false
    val rect = Rect()
    view.getWindowVisibleDisplayFrame(rect)
    val screenHeight = view.rootView.height
    if (screenHeight == 0) return false
    val keypadHeight = screenHeight - rect.bottom
    return keypadHeight > screenHeight * 0.15
}

private fun setRepeatClickListener(view: TextView, onClick: () -> Unit) {
    val handler = Handler(Looper.getMainLooper())
    var isHolding = false
    val initialDelay = 400L
    val repeatInterval = 50L
    val runnable = object : Runnable {
        override fun run() {
            if (isHolding) { onClick(); handler.postDelayed(this, repeatInterval) }
        }
    }
    view.setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isHolding = true; v.isPressed = true; onClick()
                handler.postDelayed(runnable, initialDelay); true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isHolding = false; v.isPressed = false
                handler.removeCallbacks(runnable); true
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

    val textColor = Color.parseColor("#E9D5FF")
    val activeTextColor = Color.parseColor("#FFD700")

    fun createNormalDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor("#22FFFFFF"), Color.parseColor("#08FFFFFF"), Color.parseColor("#15000000"))
    ).apply {
        shape = GradientDrawable.RECTANGLE
        setStroke(1.dpToPx(context), Color.parseColor("#3BFFFFFF"))
        cornerRadius = 8f * context.resources.displayMetrics.density
    }

    fun createPressedDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor("#5AFFFFFF"), Color.parseColor("#1EFFFFFF"), Color.parseColor("#00000000"))
    ).apply {
        shape = GradientDrawable.RECTANGLE
        setStroke(1.dpToPx(context), Color.parseColor("#80FFFFFF"))
        cornerRadius = 8f * context.resources.displayMetrics.density
    }

    fun createActiveDrawable(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.parseColor("#E67C3AED"), Color.parseColor("#CC4C1D95"))
    ).apply {
        shape = GradientDrawable.RECTANGLE
        setStroke(1.dpToPx(context), Color.parseColor("#FFD700"))
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
        val baseTextSize = 12f
        val scaledTextSize = when {
            label.length <= 3 -> baseTextSize
            label.length <= 5 -> baseTextSize * 0.85f
            else -> baseTextSize * 0.7f
        }
        val drawable = if (label == "CTRL") {
            if (viewClient.ctrlActive) createActiveDrawable() else createDefaultStateList()
        } else if (label == "ALT") {
            if (viewClient.altActive) createActiveDrawable() else createDefaultStateList()
        } else createDefaultStateList()

        return TextView(context).apply {
            text = label
            setTextColor(textColor)
            background = drawable
            textSize = scaledTextSize
            includeFontPadding = false
            isAllCaps = false
            isClickable = !isTransparent
            isFocusable = false
            if (isTransparent) visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(
                if (isFixed) ViewGroup.LayoutParams.WRAP_CONTENT else 0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (isFixed) 0f else 1f
            ).apply { setMargins(2.dpToPx(context), 0, 2.dpToPx(context), 0) }
            if (isFixed) setPadding(8.dpToPx(context), 4.dpToPx(context), 8.dpToPx(context), 4.dpToPx(context))
            else setPadding(0, 4.dpToPx(context), 0, 4.dpToPx(context))
            gravity = Gravity.CENTER
        }
    }

    fun toggleKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (isKeyboardVisible(terminalView)) {
            imm.hideSoftInputFromWindow(terminalView.windowToken, 0)
        } else {
            terminalView.requestFocus()
            terminalView.postDelayed({
                imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
            }, 60)
        }
    }

    fun getActionFor(key: String): String = when (key.uppercase()) {
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

    fun getDisplayLabel(key: String): String = when (key.uppercase()) {
        "UP" -> "↑"; "DOWN" -> "↓"; "LEFT" -> "←"; "RIGHT" -> "→"; else -> key
    }

    val rowHeight = 44.dpToPx(context)

    fun addScrollRow(keys: List<ExtraKeyItem>): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, rowHeight
            )
        }
        for (item in keys) {
            val displayLabel = if (item.display.isNotEmpty()) item.display else getDisplayLabel(item.key)
            val btn = makeButton(displayLabel)

            if (item.macro.isNotEmpty()) {
                btn.setOnClickListener { terminalView.currentSession?.write(item.macro) }
            } else {
                val action = getActionFor(item.key)
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
                    "SPECIAL_PASTE" -> btn.setOnClickListener {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.primaryClip?.let { clip ->
                            if (clip.itemCount > 0) {
                                terminalView.currentSession?.write(clip.getItemAt(0).text?.toString() ?: "")
                            }
                        }
                    }
                    else -> {
                        val metaUpper = item.meta.uppercase()
                        val metaCtrl = "CTRL" in metaUpper || "C-" in metaUpper
                        val metaAlt  = "ALT" in metaUpper  || "M-" in metaUpper
                        val fireAction = {
                            sendModifiedSequence(
                                terminalView, action,
                                viewClient.ctrlActive || metaCtrl,
                                viewClient.altActive  || metaAlt,
                                onConsumed = {
                                    viewClient.ctrlActive = false
                                    viewClient.altActive = false
                                    updateButtonColors()
                                }
                            )
                        }
                        if (item.key.uppercase() in listOf("UP", "DOWN", "LEFT", "RIGHT")) {
                            setRepeatClickListener(btn, fireAction)
                        } else {
                            btn.setOnClickListener { fireAction() }
                        }
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
            val rowKeys = mutableListOf<ExtraKeyItem>()
            for (j in 0 until rowArray.length()) {
                when (val item = rowArray.get(j)) {
                    is String -> rowKeys.add(ExtraKeyItem(key = item))
                    is JSONObject -> rowKeys.add(
                        ExtraKeyItem(
                            key = item.optString("key", ""),
                            macro = item.optString("macro", ""),
                            display = item.optString("display", ""),
                            meta = item.optString("meta", "")
                        )
                    )
                }
            }
            scrollParent.addView(addScrollRow(rowKeys))

            val fixedRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, rowHeight
                )
            }

            when {
                i == jsonArray.length() - 2 -> {
                    val kbBtn = makeButton("⌨️", isFixed = true)
                    kbBtn.setOnClickListener { toggleKeyboard() }
                    fixedRow.addView(kbBtn)
                }
                i == jsonArray.length() - 1 -> {
                    val editBtn = makeButton("⚙️", isFixed = true)
                    editBtn.setOnClickListener { onEditClicked() }
                    fixedRow.addView(editBtn)
                }
                else -> {
                    val placeholderText = if (rowKeys.isNotEmpty()) {
                        if (rowKeys.first().display.isNotEmpty()) rowKeys.first().display
                        else getDisplayLabel(rowKeys.first().key)
                    } else "ESC"
                    fixedRow.addView(makeButton(placeholderText, isTransparent = true, isFixed = true))
                }
            }
            fixedParent.addView(fixedRow)
        }

        if (jsonArray.length() == 0) {
            listOf("⌨️" to { toggleKeyboard() }, "⚙️" to { onEditClicked() }).forEach { (label, cb) ->
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, rowHeight
                    )
                }
                val btn = makeButton(label, isFixed = true)
                btn.setOnClickListener { cb() }
                row.addView(btn)
                fixedParent.addView(row)
            }
        } else if (jsonArray.length() == 1) {
            val kbRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, rowHeight
                )
            }
            val kbBtn = makeButton("⌨️", isFixed = true)
            kbBtn.setOnClickListener { toggleKeyboard() }
            kbRow.addView(kbBtn)
            fixedParent.addView(kbRow, 0)
        }
    } catch (e: Exception) {
        Log.e(TAG, "rebuildExtraKeys failed", e)
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
            append((baseSequence[0].code and 0x1F).toChar())
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
        val attachLogic = {
            attachedId = id
            val s = sessionFor(id)
            runCatching { PtyOutputRelay.unbind() }
            terminalView.attachSession(s)
            PtyOutputRelay.bind(s, terminalView)
            terminalView.updateSize()
        }
        if (terminalView.width > 0 && terminalView.height > 0) {
            attachLogic()
        } else {
            terminalView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View, left: Int, top: Int, right: Int, bottom: Int,
                    oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                ) {
                    v.removeOnLayoutChangeListener(this)
                    attachLogic()
                }
            })
        }
    }

    fun pruneSessionsExcept(keep: Set<Int>) {
        val removed = sessions.keys.filter { it !in keep }
        for (id in removed) {
            NativeBridge.closeSession(id)
            sessions.remove(id)
            clients.remove(id)
            PtyOutputRelay.discardSessionQueue(id)
        }
        if (attachedId !in keep) attachedId = -1
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