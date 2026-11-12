package app.xodos2.ui

import app.xodos2.ui.glass.GlassButton
import androidx.compose.material.icons.filled.Close
import android.Manifest
import android.content.ActivityNotFoundException
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import android.util.Log
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.provider.OpenableColumns
import android.util.Log as AndroidLog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import app.xodos2.ui.glass.glassBlurModifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import app.xodos2.*
import app.xodos2.TerminalSessionIds
import app.xodos2.WaylandBridge
import app.xodos2.wayland.input.InputRouteState
import app.xodos2.shell.ShellFonts
import app.xodos2.ui.dialog.MOUSE_MODE_TABLET
import app.xodos2.ui.dialog.MOUSE_MODE_TOUCHPAD
import app.xodos2.ui.drawer.AppDrawer
import app.xodos2.ui.drawer.pages.*
import app.xodos2.ui.orb.FloatingMenuOrb
import app.xodos2.ui.prefs.AppPrefs
import app.xodos2.ui.runtime.*
import app.xodos2.ui.runtime.NativeInstallCoordinator.DistroDescriptor
import app.xodos2.ui.setup.InstallScreen
import app.xodos2.ui.shell.ShellScreen
import app.xodos2.wayland.WaylandSurfaceView
import app.xodos2.ui.x11.EmbeddedX11Surface
import com.termux.x11.EmbeddedX11Controller
import com.termux.x11.X11OutputSettings
import com.termux.x11.X11ServerService
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.Pattern
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URL
import java.net.HttpURLConnection
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import android.view.WindowManager
import app.xodos2.ui.shell.TerminalBackgroundX11
import app.xodos2.ui.glassDialogStyle
import androidx.compose.ui.res.stringResource
import app.xodos2.R
import app.xodos2.ui.util.FitText
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private val VULKAN_MODES = listOf("LLVMPIPE", "VENUS", "TURNIP", "WRAPPER","PANVK")
private val OPENGL_MODES = listOf("LLVMPIPE", "VIRGL", "ZINK", "GL4ES")


private val WINE_NATIVE_DEPENDENCIES = listOf(
    "libandroid-support", "libx11", "libxcb", "libxau", "libxdmcp",
    "libxext", "libxfixes", "libxi", "libxrender", "libxrandr",
    "libxcursor", "libxinerama", "libxcomposite", "libxdamage",
    "libxft", "libxmu", "libxt", "libxtst", "libxss", "libxv",
    "libxkbcommon", "libxshmfence", "xorg-xrandr", "mesa", "libdrm",
    "fontconfig", "freetype", "glib", "zlib",  "hangover-libwow64fex", "hangover-libarm64ecfex", "hangover-wowbox64", "7zip", "zstd", "gnutls",
    "openssl", "openal-soft", "gstreamer", "virglrenderer-venus",
    "angle-android", "pulseaudio", "libsndfile", "alsa-lib", "libevdev",
    "mesa-vulkan-icd-freedreno", "vulkan-loader-generic", "sdl2", "libntsync-android",
    "vulkan-wrapper", "malig57-vulkan-panvcake", "mesa-vulkan-icd-virtio-dri3"
)

private const val X11_MODE_LABEL_NATIVE = "Native"
private const val X11_MODE_LABEL_SCALED = "Scaled"
private const val X11_MODE_LABEL_EXACT = "Fixed size"
private const val X11_MODE_LABEL_CUSTOM = "Custom"

private fun x11ResolutionModeLabelForInternal(mode: String): String = when (mode) {
    "native" -> X11_MODE_LABEL_NATIVE
    "scaled" -> X11_MODE_LABEL_SCALED
    "exact" -> X11_MODE_LABEL_EXACT
    "custom" -> X11_MODE_LABEL_CUSTOM
    else -> X11_MODE_LABEL_NATIVE
}

 /**
 * Writes /.x11 or /.wayland into every installed container's rootfs.
 * The opposite marker is removed *before* the new one is ensured,
 * so the two markers can never exist simultaneously.
 */
fun updateLauncherMarkers(context: Context, launcherDefault: String) {
    val filesDir = context.filesDir

    // Use the stable constants, not substring matching
    val markerName = when (launcherDefault) {
        AppPrefs.LAUNCHER_PREF_X11      -> ".x11"
        AppPrefs.LAUNCHER_PREF_WAYLAND  -> ".wayland"
        else -> return   // Terminal or anything else → remove both markers if you want
    }

    val otherMarker = if (markerName == ".x11") ".wayland" else ".x11"

    for (id in 1..3) {
        val rootfs = File(filesDir, "containers/$id")
        if (!rootfs.exists()) continue

        // Delete the opposite marker first
        File(rootfs, otherMarker).delete()

        // Ensure the chosen marker exists
        File(rootfs, markerName).apply {
            if (!exists()) {
                createNewFile()
                writeText(System.currentTimeMillis().toString())
            }
        }
    }
}
private fun x11ResolutionModeInternalForLabel(label: String): String = when (label) {
    X11_MODE_LABEL_NATIVE -> "native"
    X11_MODE_LABEL_SCALED -> "scaled"
    X11_MODE_LABEL_EXACT -> "exact"
    X11_MODE_LABEL_CUSTOM -> "custom"
    else -> "native"
}

private val X11_CUSTOM_RESOLUTION_PATTERN: Pattern = Pattern.compile("^\\s*(\\d{2,4})\\s*x\\s*(\\d{2,4})\\s*\$")

//private enum class UiMode { TERMINAL, ARCH_WAYLAND_DESKTOP, DEBIAN_X11_DESKTOP }
private enum class UiMode {
    TERMINAL,
    ARCH_WAYLAND_DESKTOP,
    DEBIAN_WAYLAND_DESKTOP,
    WINE_WAYLAND_DESKTOP,
    DEBIAN_X11_DESKTOP
}
private enum class DistroFetchState { LOADING, LOADED, ERROR }
/**
 * Removes all leftover virgl payload directories (virgl.payload*)
 * from the app's internal files directory.
 */
fun cleanVirglPayloadDirs(context: Context) {
    val filesDir = context.filesDir
    val dirs = filesDir.listFiles { file ->
        file.isDirectory && file.name.startsWith("virgl.payload")
    }
    dirs?.forEach { dir ->
        // deleteRecursively() removes everything inside and then the directory itself
        if (dir.deleteRecursively()) {
            android.util.Log.d("VirglCleanup", "Deleted: ${dir.absolutePath}")
        } else {
            android.util.Log.w("VirglCleanup", "Failed to delete: ${dir.absolutePath}")
        }
    }
}


// ---------------------------------------------------------------
// AppLogger (unchanged) moved to another class 
// ---------------------------------------------------------------


object AppLogger {
    private const val TAG = "Xodosark"

    /** Log a message – captured by the running logcat process. */
    fun log(msg: String) {
        Log.d(TAG, msg)
    }

    /** No longer needed – the LogcatLogger manages its own file. */
    fun close() {}
}


/**
 * Bridge between AppScreen's enter-*-desktop functions and ShellScreen's
 * background X11 LorieView.
 *
 * `termux-x11` allows only one attached LorieView per X server process. When
 * the user opens the X11 desktop, MainActivity.onCreate calls
 * LorieView.connect(fd) — if our background LorieView is still attached at
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
// =============================================================================
// AppScreen – container‑based distro installer + original terminal/desktop UI
// =============================================================================
@Composable
fun AppScreen(
    startInTerminal: Boolean = false,
    onAppReady: () -> Unit = {}   
    
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { context.getSharedPreferences("xodos2_prefs", 0) }
// Keep screen on always while the app is in the foreground
LaunchedEffect(Unit) {
    (context as? Activity)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}    
var showExitDialog by remember { mutableStateOf(false) }
    // ----- storage permission -----
    var storagePermissionGranted by remember { mutableStateOf(false) }
    val requestStoragePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        storagePermissionGranted = granted
       // AppLogger.init(context, granted)
    }
 // Start logcat capture when the composable enters composition
    DisposableEffect(Unit) {
        LogcatLogger.start(context)
        onDispose {
            LogcatLogger.stop()
        }
    }
    
    // ── Background mic-safety watcher ──
// On ON_STOP, wait 5 s. If the app is still backgrounded and not in PIP,
// unload the mic module (releases Android's AudioRecord → prevents the
// "mic locked" state that required a reboot). If a real recording is
// still running (Wine / ffmpeg), the unload is skipped so the user's
// stream is not interrupted.
val lifecycleOwner = LocalLifecycleOwner.current
var pendingUnloadJob by remember { mutableStateOf<Job?>(null) }

DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> {
                pendingUnloadJob?.cancel()
                pendingUnloadJob = null
                if (MicManager.isEnabled(context)) {
                    scope.launch { MicManager.hotApply(true) }
                }
            }
            Lifecycle.Event.ON_STOP -> {
                pendingUnloadJob?.cancel()
                pendingUnloadJob = scope.launch {
                    delay(5_000)
                    val inPip = (context as? Activity)?.isInPictureInPictureMode == true
                    val backgrounded = !lifecycleOwner.lifecycle.currentState
                        .isAtLeast(Lifecycle.State.STARTED)
                    if (backgrounded && !inPip) {
                        withContext(Dispatchers.IO) { MicManager.safeUnloadIfIdle() }
                    }
                }
            }
            else -> {}
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
        lifecycleOwner.lifecycle.removeObserver(observer)
        pendingUnloadJob?.cancel()
    }
}
// ── end mic-safety watcher ──
    LaunchedEffect(Unit) {
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            storagePermissionGranted = true
            //AppLogger.init(context, true)
        } else {
          //  AppLogger.init(context, false)
            requestStoragePermission.launch(permission)
        }
       // cleanVirglPayloadDirs(context) 
    }

    // ----- core state -----
    var initialized by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf(0 to context.getString(R.string.appscreen_preparing)) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var installInProgress by remember { mutableStateOf(false) }
   var installDone by remember { mutableStateOf(false) }       
    // ----- container state (new) -----
    var hasContainer1 by remember { mutableStateOf(false) }
    var hasContainer2 by remember { mutableStateOf(false) }
    var hasContainer3 by remember { mutableStateOf(false) }
    var anyRootfsInstalled by remember { mutableStateOf(false) }
// Inside AppScreen, with the other state vars:
var showContainerManager by remember { mutableStateOf(false) }
var pendingOverwriteSlot by remember { mutableStateOf<Int?>(null) }
var pendingContainerForInstall by remember { mutableStateOf<Int?>(null) }
var confirmOverwriteContinuation by remember { mutableStateOf<CancellableContinuation<Boolean>?>(null) }

var showDeleteConfirmation by remember { mutableStateOf<Int?>(null) }   // container id to delete
var showCleanCacheConfirmation by remember { mutableStateOf(false) }
var deleteInProgress by remember { mutableStateOf(false) }
var removingNativeEnvInProgress by remember { mutableStateOf(false) } 

var pendingContainerForBackup by remember { mutableStateOf<Int?>(null) }
var backupInProgress by remember { mutableStateOf(false) }
var backupProgress by remember { mutableStateOf(0 to "") }  // (pct, msg)
// ── Wine download dialog state ──
var showWineDownloadDialog by remember { mutableStateOf(false) }
var wineDownloadInProgress by remember { mutableStateOf(false) }
var wineDownloadProgress by remember { mutableStateOf(0 to "") }
var wineDownloadJob by remember { mutableStateOf<Job?>(null) }
var wineCustomUrl by remember { mutableStateOf("") }
var wineCustomUrlError by remember { mutableStateOf("") }
// --- Bootstrap package installation ---
var bootstrapUri by remember { mutableStateOf<Uri?>(null) }
var bootstrapInProgress by remember { mutableStateOf(false) }
var bootstrapProgress by remember { mutableStateOf(0 to "") }   // (pct, msg)
var bootstrapDownloadJob by remember { mutableStateOf<Job?>(null) }
var fullDesktopDownloadJob by remember { mutableStateOf<Job?>(null) }
var bootstrapError by remember { mutableStateOf<String?>(null) }
// --- Bootstrap download ---
var bootstrapDownloadInProgress by remember { mutableStateOf(false) }
var bootstrapDownloadProgress by remember { mutableStateOf(0 to "") }
var showNativeContainerPrompt by remember { mutableStateOf<Int?>(null) }  
// --- Full Desktop GUI download ---
var fullDesktopDownloadInProgress by remember { mutableStateOf(false) }
var fullDesktopDownloadProgress by remember { mutableStateOf(0 to "") }
var showRemoveNativeEnvConfirmation by remember { mutableStateOf(false) }



fun removeNativeEnvironment() {
    scope.launch {
        removingNativeEnvInProgress = true

        val success = withContext(Dispatchers.IO) {
            val usrDir = File(context.filesDir, "usr")
            usrDir.exists() && usrDir.deleteRecursively()
        }

        if (success) {
            // Small pause so the user sees the overlay, then restart
            delay(400)
            val intent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            if (intent != null) context.startActivity(intent)
            (context as? Activity)?.finishAffinity()
            System.exit(0)
        } else {
            removingNativeEnvInProgress = false
            Toast.makeText(
                context,
                context.getString(R.string.appscreen_native_environment_not_found_or_failed_to_remove),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

    fun refreshContainerState() {
        val mask = NativeBridge.getInstalledContainersMask()
        hasContainer1 = (mask and 1) != 0
        hasContainer2 = (mask and 2) != 0
        hasContainer3 = (mask and 4) != 0
        anyRootfsInstalled = mask != 0
    }

    // Legacy flags for drawer pages that expect them (Arch, Debian, Wine)
    var hasArchRootfs by remember { mutableStateOf(false) }
    var hasDebianRootfs by remember { mutableStateOf(false) }
    var hasWineRootfs by remember { mutableStateOf(false) }

    fun refreshLegacyFlags() {
        val mask = NativeBridge.getInstalledContainersMask()
        hasArchRootfs = (mask and 1) != 0
        hasDebianRootfs = (mask and 2) != 0
        hasWineRootfs = (mask and 4) != 0
    }
    

val backupFilePicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/x-xz")
) { uri: Uri? ->
    if (uri != null) {
        val id = pendingContainerForBackup ?: return@rememberLauncherForActivityResult
        pendingContainerForBackup = null
        scope.launch {
            backupInProgress = true
            NativeInstallCoordinator.backupContainerToUri(
                context = context,
                containerId = id,
                destUri = uri,
                onProgress = { pct, msg -> backupProgress = pct to msg }
            )
            backupInProgress = false
            // Refresh state after backup (just in case)
            refreshContainerState()
            refreshLegacyFlags()
        }
    }
}
// ----- File picker for bootstrap archive -----
val pickBootstrapFile = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri: Uri? ->
    if (uri != null) {
        val exists = try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
                cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)) > -1
            } ?: false
        } catch (e: Exception) { false }

        if (exists) {
            bootstrapUri = uri
        } else {
            Toast.makeText(context, context.getString(R.string.appscreen_selected_file_no_longer_exists), Toast.LENGTH_LONG).show()
        }
    }
}

// Pick a component file from Android storage and copy it to usr/wincomponents/d3d/
val pickWineComponent = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri: Uri? ->
    if (uri == null) return@rememberLauncherForActivityResult
    scope.launch {
        wineDownloadInProgress = true
        wineDownloadProgress = 0 to context.getString(R.string.appscreen_copying_archive)
        try {
            val fileName = withContext(Dispatchers.IO) {
                var name = "component.bin"
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = c.getString(idx) ?: name
                    }
                }
                val targetDir = File(context.filesDir, "usr/wincomponents/d3d")
                targetDir.mkdirs()
                val outputFile = File(targetDir, name)
                val tmpFile = File(targetDir, "$name.tmp")
                tmpFile.delete()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmpFile.outputStream().use { output -> input.copyTo(output) }
                } ?: throw Exception("Cannot open input stream")
                if (!tmpFile.renameTo(outputFile)) throw Exception("Failed to rename file")
                name
            }
            withContext(Dispatchers.Main) {
                wineDownloadProgress = 100 to fileName
                Toast.makeText(context, fileName, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                wineDownloadProgress = -1 to context.getString(R.string.appscreen_error_with_message, e.message ?: "")
            }
        } finally {
            withContext(Dispatchers.Main) { wineDownloadInProgress = false }
        }
    }
}
    // ----- distro selection state -----
    var availableDistros by remember { mutableStateOf<List<DistroDescriptor>>(emptyList()) }
    var distroFetchState by remember { mutableStateOf(DistroFetchState.LOADING) }
    var showDistroSelection by remember { mutableStateOf(false) }
    var showSlotPicker by remember { mutableStateOf(false) }
    var pendingDistro by remember { mutableStateOf<DistroDescriptor?>(null) }
    var pendingLocalUri by remember { mutableStateOf<Uri?>(null) }
    val setupAlreadyCompleted = prefs.getBoolean("setup_done", false)
// Direct URL install states
var customUrlText by remember { mutableStateOf("") }
var customUrlError by remember { mutableStateOf("") }
var isFetchingCustomUrl by remember { mutableStateOf(false) }
var showCustomUrlDialog by remember { mutableStateOf(false) }
var fetchedDistro by remember { mutableStateOf<DistroDescriptor?>(null) }

fun markContainerAsNativeTerminal(containerId: Int) {
    val containerDir = File(context.filesDir, "containers/$containerId")
    containerDir.mkdirs()
    File(containerDir, ".xodos2_rootfs_ok").createNewFile()
    File(containerDir, ".rootfs_type").writeText("XoDos-Ark")
    NativeInstallCoordinator.saveContainerDistro(context, containerId, "XoDos-Ark")
    refreshContainerState()
    refreshLegacyFlags()
}

/**
 * Installs the Wine runtime dependencies into a native terminal session.
 *
 *  • If any native container has a live terminal session → reuse it.
 *  • Otherwise spawn the default native session (ARCH_TERMINAL, container 1).
 *
 *  The `apt update` prefix is added so freshly-added packages can be
 *  resolved even if the local package index is stale.
 */
fun installWineDependenciesInNativeSession() {
    // 1. Find a native container (1..3)
    val nativeContainerId = (1..3).firstOrNull { cid ->
        DesktopDetector.isNativeContainer(context, cid)
    }

    val sessionId: Int = when (nativeContainerId) {
        1 -> TerminalSessionIds.ARCH_TERMINAL
        2 -> TerminalSessionIds.DEBIAN_TERMINAL
        3 -> TerminalSessionIds.WINE_TERMINAL
        else -> TerminalSessionIds.ARCH_TERMINAL   // default
    }

    // 2. Spawn the session if it isn't already running
    if (!NativeBridge.isSessionAlive(sessionId)) {
        NativeBridge.spawnSessionInRootfs(
            sessionId, 80, 24,
            TerminalSessionIds.rootfsKindForNativeId(sessionId)
        )
    }

    // 3. Push the apt install command to the shell
    val pkgList = WINE_NATIVE_DEPENDENCIES.joinToString(" ")
    val cmd = "apt update && apt install -y --no-install-recommends $pkgList\n"
    NativeBridge.writeInput(sessionId, cmd.toByteArray())
    AppLogger.log("Queued wine dependency install on session $sessionId")
}

fun proceedToDistroSelection(containerId: Int) {
    pendingContainerForInstall = containerId
    showDistroSelection = true
    if (availableDistros.isEmpty()) {
        NativeInstallCoordinator.invalidateDistroCache()
        distroFetchState = DistroFetchState.LOADING
        scope.launch {
            availableDistros = withContext(Dispatchers.IO) {
                NativeInstallCoordinator.fetchAvailableDistros(NativeInstallCoordinator.DistroSource.CUSTOM)
            }
            distroFetchState = if (availableDistros.isEmpty()) DistroFetchState.ERROR else DistroFetchState.LOADED
        }
    }
}

fun handleContainerInstallClick(containerId: Int) {
    // When the user explicitly clicks "+" on a container, give them a choice:
    //   • use it as a native Android terminal (bash -l, Termux-style), or
    //   • install a real distro (proot.rs spawns the distro shell).
    //
    // Container 1 is auto-marked as native on first launch (see
    // LaunchedEffect(Unit)), so the terminal already works out of the box —
    // this prompt only appears when the user asks to install something.
    val hasNativeEnv = File(context.filesDir, "usr/bin/bash").exists() ||
                       File(context.filesDir, "usr/bin/sh").exists()

    if (hasNativeEnv) {
        showContainerManager = false
        showNativeContainerPrompt = containerId
    } else {
        // No native bash/sh available → distro is the only option.
        proceedToDistroSelection(containerId)
    }
}


    // ----- mode & drawer state -----
    var menuOpen by remember { mutableStateOf(false) }
    var uiMode by remember(startInTerminal) { mutableStateOf(UiMode.TERMINAL) }
    var waylandVisible by remember { mutableStateOf(false) }
  //  val showWayland = waylandVisible && uiMode != UiMode.TERMINAL
    val showWayland = waylandVisible && uiMode != UiMode.TERMINAL && uiMode != UiMode.DEBIAN_X11_DESKTOP
    val showX11 = uiMode == UiMode.DEBIAN_X11_DESKTOP
    var settingsOpen by remember { mutableStateOf(false) }
    var waylandScriptEditorOpen by remember { mutableStateOf(false) }
    var x11ScriptEditorOpen by remember { mutableStateOf(false) }
 var archX11ScriptEditorOpen by remember { mutableStateOf(false) }   
var wineScriptEditorOpen by remember { mutableStateOf(false) }  


var debianX11ScriptEditorOpen by remember { mutableStateOf(false) }
var wineX11ScriptEditorOpen by remember { mutableStateOf(false) }
var debianWaylandScriptEditorOpen by remember { mutableStateOf(false) }
var wineWaylandScriptEditorOpen by remember { mutableStateOf(false) }

    var terminalSessionState by remember { mutableStateOf(TerminalSessionController.initialState()) }
    var mouseMode by remember { mutableStateOf(MOUSE_MODE_TOUCHPAD) }
    var resolutionPercent by remember { mutableStateOf(100) }
    var scalePercent by remember { mutableStateOf(100) }
    var showKeyboardTrigger by remember { mutableStateOf(0) }
    var keyboardWanted by remember { mutableStateOf(false) }
    var pendingAutoShowWayland by remember { mutableStateOf(false) }
    var desktopVulkanMode by remember { mutableStateOf("LLVMPIPE") }
    var desktopOpenGLMode by remember { mutableStateOf("LLVMPIPE") }
    var desktopHiddenInjectedKey by remember { mutableStateOf("") }
    var rendererSessionResetEpoch by remember { mutableIntStateOf(0) }
    var desktopLaunchBlackout by remember(startInTerminal) { mutableStateOf(!startInTerminal) }
    var terminalFontKey by remember { mutableStateOf(ShellFonts.DEFAULT_ID) }

    var x11MouseMode by remember { mutableStateOf(EmbeddedX11Controller.MouseMode.TOUCHPAD) }
    var x11ResolutionModeLabel by remember { mutableStateOf(X11_MODE_LABEL_NATIVE) }
    var x11DisplayScale by remember { mutableStateOf(100) }
    var x11ResolutionExact by remember { mutableStateOf("1280x1024") }
    var x11ResolutionCustom by remember { mutableStateOf("1280x1024") }
    val headlessX11InjectHandler = remember { Handler(Looper.getMainLooper()) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val waylandRuntimeDir = remember(context) {
        File(context.filesDir, "usr/tmp").apply { mkdirs() }.absolutePath
    }
    
    val desktopSocketName = "wayland-xodos2-desktop"
    var desktopServerId by remember { mutableStateOf(0L) }
    var launcherDefault by remember { mutableStateOf(AppPrefs.readLauncherDefault(prefs)) }
    var showTurnipDriverDialog by remember { mutableStateOf(false) }
var turnipMissingContainers by remember { mutableStateOf<List<Int>>(emptyList()) }
var turnipDownloadProgress by remember { mutableStateOf(0 to "") }
var turnipDownloadInProgress by remember { mutableStateOf(false) }
var showPanvkDriverDialog by remember { mutableStateOf(false) }
var panvkMissingContainers by remember { mutableStateOf<List<Int>>(emptyList()) }
var panvkDownloadProgress by remember { mutableStateOf(0 to "") }
var panvkDownloadInProgress by remember { mutableStateOf(false) }
    // ── Distro installation helpers ──────────────────────────────
fun installIntoSlot(distro: DistroDescriptor, containerId: Int) {
    scope.launch {
        
        try {
            val ok = NativeInstallCoordinator.installDistroToContainer(
                context = context,
                distro = distro,
                containerId = containerId,
                onProgress = { pct, msg ->
                installInProgress = true
                  installProgress = pct to msg },
                onConfirmOverwrite = {
                    // We'll suspend here until the user responds.
                    // Show the confirmation dialog by setting a state, then wait.
                    pendingOverwriteSlot = containerId
                    // Wait until pendingOverwriteSlot is reset (by the dialog’s answer)
                    while (pendingOverwriteSlot != null) { delay(100) }
                    // If the user confirmed, the slot will have been processed.
                    // Return true if the user said "yes" in the dialog.
                    // We'll track the answer in a separate variable.
                    // For simplicity, we'll introduce a mutable variable here.
                    // Actually, we can use a CompletableDeferred or similar.
                    // Let's use a simple boolean flag captured by the dialog.
                    // This lambda will be called from inside a coroutine, so we can
                    // use a CompletableDeferred<Boolean>.
                    // We'll refactor the dialog to set a `var confirmAnswer: Boolean?`
                    // and wait for it.
                    // But to keep it simple in this snippet, we'll assume a callback
                    // set by the dialog. I'll show the pattern using a suspendCancellableCoroutine.
                    // For brevity, I'll use a `suspendCancellableCoroutine<Boolean>`.
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                        confirmOverwriteContinuation = cont
                    }
                }
            )
            if (ok) {
                refreshContainerState()
                refreshLegacyFlags()
                showDistroSelection = false
                showSlotPicker = false
                pendingDistro = null
                prefs.edit().putBoolean("setup_done", true).apply()
              installDone = true    
               }
        } finally {
            installInProgress = false
        }
    }
}

fun extractLocalIntoSlot(uri: Uri, containerId: Int) {
    scope.launch {
        
        try {
            val ok = NativeInstallCoordinator.extractRootfsFromUriToContainer(
                context = context,
                uri = uri,
                containerId = containerId,
                onProgress = { pct, msg ->
                installInProgress = true
                  installProgress = pct to msg },
                onConfirmOverwrite = {
                pendingOverwriteSlot = containerId 
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                        confirmOverwriteContinuation = cont
                    }
                }
            )
            if (ok) {
                refreshContainerState()
                refreshLegacyFlags()
                showDistroSelection = false
                showSlotPicker = false
                pendingDistro = null
                pendingLocalUri = null
                prefs.edit().putBoolean("setup_done", true).apply()
                installDone = true    
            }
        } finally {
            installInProgress = false
        }
    }
}
    // ----- File picker for local archive -----
    val pickFile = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument()
) { uri: Uri? ->
    if (uri != null) {
        // Verify the file still exists
        val exists = try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                cursor.moveToFirst()
                // A valid file will have a size column; if size is > -1 it exists
                cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)) > -1
            } ?: false
        } catch (e: Exception) { false }

        if (exists) {
            pendingLocalUri = uri
            showSlotPicker = true
        } else {
            Toast.makeText(context, context.getString(R.string.appscreen_selected_file_no_longer_exists_please_pick_a_val), Toast.LENGTH_LONG).show()
        }
    }
}

    // ----- mode enter functions (unchanged) -----
    fun enterTerminal() {
        try { WaylandBridge.nativeSetWmMode(WaylandBridge.WM_MODE_NESTED) } catch (_: Throwable) {}
        uiMode = UiMode.TERMINAL
        keyboardWanted = false 
        waylandVisible = false
        PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.LEGACY_ARCH_X11_PTY, false)
        PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.ARCH_WAYLAND_DISPLAY, false)
        PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.DEBIAN_X11_DISPLAY, false)
        desktopLaunchBlackout = false
        menuOpen = false
        AppLogger.log("Switched to terminal mode")
    }
    
    fun requestKeyboard() {
    keyboardWanted = true
    showKeyboardTrigger++
}

fun enterArchX11Desktop() {
    // Ensure the shared X11 server is running
    X11Runtime.ensureX11ServerProcessStarted(context)

    // Run the Arch X11 startup script
    DisplayOrchestrator.runArchX11DesktopStartupScript(
        context = context,
        prefs = prefs,
        headlessInjectHandler = headlessX11InjectHandler,
        hasArchRootfs = hasContainer1,
    )

    // Switch to X11 desktop mode (same as Debian/Wine)
    menuOpen = false
    desktopLaunchBlackout = false
    waylandVisible = false
    pendingAutoShowWayland = false
    PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.ARCH_X11_DISPLAY, false)

   // uiMode = UiMode.DEBIAN_X11_DESKTOP   // reuse the X11 UI mode
uiMode = UiMode.TERMINAL
TerminalBackgroundX11.releaseBeforePrimaryAttaches()
    
       try {
        val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.appscreen_failed_to_open_x11_desktop), Toast.LENGTH_SHORT).show()
    }
    AppLogger.log("Entered Arch X11 desktop")
}

/**
 * Starts the Wayland backend (server + startup script) but stays in terminal mode.
 */
fun prepareWaylandBackend() {
    if (!DisplayOrchestrator.prepareWaylandRuntimeAndStartServer(context, waylandRuntimeDir)) return
    if (desktopServerId == 0L) {
        desktopServerId = try {
            WaylandBridge.nativeCreateServer(waylandRuntimeDir, desktopSocketName)
        } catch (_: Throwable) { 0L }
    }
    if (desktopServerId != 0L) {
        try { WaylandBridge.nativeSetActiveServer(desktopServerId) } catch (_: Throwable) {}
    }
    desktopHiddenInjectedKey = DisplayOrchestrator.runArchWaylandStartupScriptIfNeeded(
        prefs = prefs,
        desktopSocketName = desktopSocketName,
        vulkanMode = desktopVulkanMode,
        openGLMode = desktopOpenGLMode,
        currentHiddenInjectedKey = desktopHiddenInjectedKey,
    ).hiddenInjectedKey
    AppLogger.log("Wayland backend prepared")
}

/**
 * Starts the X11 server and runs the startup script, but stays in terminal mode.
 */
fun prepareX11Backend() {
    X11Runtime.ensureX11ServerProcessStarted(context)
    if (NativeBridge.isSessionAlive(TerminalSessionIds.ARCH_X11_DISPLAY)) {
        AppLogger.log("X11 backend already running")
        return
    }
    DisplayOrchestrator.runArchX11DesktopStartupScript(
        context = context,
        prefs = prefs,
        headlessInjectHandler = headlessX11InjectHandler,
        hasArchRootfs = hasContainer1,
    )
    AppLogger.log("X11 backend prepared")
}

    fun enterArchWaylandDesktop() {
        if (!DisplayOrchestrator.prepareWaylandRuntimeAndStartServer(context, waylandRuntimeDir)) {
            menuOpen = false
            return
        }
        if (desktopServerId == 0L) {
            desktopServerId = try { WaylandBridge.nativeCreateServer(waylandRuntimeDir, desktopSocketName) } catch (_: Throwable) { 0L }
        }
        if (desktopServerId != 0L) { try { WaylandBridge.nativeSetActiveServer(desktopServerId) } catch (_: Throwable) {} }
        try { WaylandBridge.nativeSetWmMode(WaylandBridge.WM_MODE_NESTED) } catch (_: Throwable) {}
        desktopLaunchBlackout = true
        desktopHiddenInjectedKey = DisplayOrchestrator.runArchWaylandStartupScriptIfNeeded(
            prefs = prefs,
            desktopSocketName = desktopSocketName,
            vulkanMode = desktopVulkanMode,
            openGLMode = desktopOpenGLMode,
            currentHiddenInjectedKey = desktopHiddenInjectedKey,
        ).hiddenInjectedKey
        pendingAutoShowWayland = true
        PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.LEGACY_ARCH_X11_PTY, false)
        PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.ARCH_WAYLAND_DISPLAY, false)
        PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.DEBIAN_X11_DISPLAY, false)
        uiMode = UiMode.ARCH_WAYLAND_DESKTOP
        waylandVisible = false
        menuOpen = false
        AppLogger.log("Entered Arch Wayland desktop")
    }



fun enterWineDesktop() {
    X11Runtime.ensureX11ServerProcessStarted(context)

    // Check if the Wine X11 session is already running
    if (NativeBridge.isSessionAlive(TerminalSessionIds.WINE_X11_DISPLAY)) {
        AppLogger.log("Wine X11 desktop already running – skipping startup script")
        menuOpen = false
        desktopLaunchBlackout = false
        waylandVisible = false
        pendingAutoShowWayland = false
        uiMode = UiMode.TERMINAL
        TerminalBackgroundX11.releaseBeforePrimaryAttaches()
        
        try {
            val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.appscreen_failed_to_open_x11_desktop), Toast.LENGTH_SHORT).show()
        }
        return
    }

    // Not running → launch the script
    DisplayOrchestrator.runWineX11DesktopStartupScript(
        context = context,
        prefs = prefs,
        headlessInjectHandler = headlessX11InjectHandler,
        hasWineRootfs = hasContainer3,
    )

    menuOpen = false
    desktopLaunchBlackout = false
    waylandVisible = false
    pendingAutoShowWayland = false
    PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.WINE_X11_DISPLAY, false)
    uiMode = UiMode.TERMINAL
    
    TerminalBackgroundX11.releaseBeforePrimaryAttaches()

    try {
        val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.appscreen_failed_to_open_x11_desktop), Toast.LENGTH_SHORT).show()
    }
    AppLogger.log("Entered Wine X11 desktop")
}

fun enterDebianWaylandDesktop() {
    // Prepare Wayland runtime + server (reuse if already created)
    if (!DisplayOrchestrator.prepareWaylandRuntimeAndStartServer(context, waylandRuntimeDir)) {
        menuOpen = false; return
    }
    if (desktopServerId == 0L) {
        desktopServerId = try { WaylandBridge.nativeCreateServer(waylandRuntimeDir, desktopSocketName) } catch (_: Throwable) { 0L }
    }
    if (desktopServerId != 0L) { try { WaylandBridge.nativeSetActiveServer(desktopServerId) } catch (_: Throwable) {} }
    try { WaylandBridge.nativeSetWmMode(WaylandBridge.WM_MODE_NESTED) } catch (_: Throwable) {}

    // Use the interactive Debian terminal to run the script
    val sessionId = TerminalSessionIds.DEBIAN_TERMINAL
    if (!NativeBridge.isSessionAlive(sessionId)) {
        NativeBridge.spawnSessionInRootfs(sessionId, 80, 24, TerminalSessionIds.rootfsKindForNativeId(sessionId))
    }

    // Read the shared Wayland startup script (same key as Arch) and inject it
    val script = prefs.getString("ddesktop_startup_script", "") ?: ""
    if (script.isNotEmpty()) {
        NativeBridge.writeInput(sessionId, "$script\n".toByteArray())
    }

    // UI state
    desktopLaunchBlackout = true
    pendingAutoShowWayland = true
    PtyOutputRelay.setSessionIoLoggingEnabled(sessionId, false)
    uiMode = UiMode.DEBIAN_WAYLAND_DESKTOP
    waylandVisible = false
    menuOpen = false
    AppLogger.log("Entered Debian Wayland desktop")
}

fun enterWineWaylandDesktop() {
    if (!DisplayOrchestrator.prepareWaylandRuntimeAndStartServer(context, waylandRuntimeDir)) {
        menuOpen = false; return
    }
    if (desktopServerId == 0L) {
        desktopServerId = try { WaylandBridge.nativeCreateServer(waylandRuntimeDir, desktopSocketName) } catch (_: Throwable) { 0L }
    }
    if (desktopServerId != 0L) { try { WaylandBridge.nativeSetActiveServer(desktopServerId) } catch (_: Throwable) {} }
    try { WaylandBridge.nativeSetWmMode(WaylandBridge.WM_MODE_NESTED) } catch (_: Throwable) {}

    val sessionId = TerminalSessionIds.WINE_TERMINAL
    if (!NativeBridge.isSessionAlive(sessionId)) {
        NativeBridge.spawnSessionInRootfs(sessionId, 80, 24, TerminalSessionIds.rootfsKindForNativeId(sessionId))
    }

    val script = prefs.getString("wdesktop_startup_script", "") ?: ""
    if (script.isNotEmpty()) {
        NativeBridge.writeInput(sessionId, "$script\n".toByteArray())
    }

    desktopLaunchBlackout = true
    pendingAutoShowWayland = true
    PtyOutputRelay.setSessionIoLoggingEnabled(sessionId, false)
    uiMode = UiMode.WINE_WAYLAND_DESKTOP
    waylandVisible = false
    menuOpen = false
    AppLogger.log("Entered Wine Wayland desktop")
}

fun openX11Settings() {
    try {
        val intent = Intent(context, com.termux.x11.LoriePreferences::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.appscreen_failed_to_open_x11_settings), Toast.LENGTH_SHORT).show()
    }
}

    fun enterDebianDesktop() {
    X11Runtime.ensureX11ServerProcessStarted(context)

    //  Check if the Debian X11 session is already running
    if (NativeBridge.isSessionAlive(TerminalSessionIds.DEBIAN_X11_DISPLAY)) {
        AppLogger.log("Debian X11 desktop already running – skipping startup script")
        menuOpen = false
        desktopLaunchBlackout = false
        waylandVisible = false
        pendingAutoShowWayland = false
        uiMode = UiMode.TERMINAL
        TerminalBackgroundX11.releaseBeforePrimaryAttaches()
        try {
            val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, context.getString(R.string.appscreen_failed_to_open_x11_desktop), Toast.LENGTH_SHORT).show()
        }
        return
    }

    // If not running, run the startup script as before
    DisplayOrchestrator.runDebianX11DesktopStartupScript(
        context = context,
        prefs = prefs,
        headlessInjectHandler = headlessX11InjectHandler,
        hasDebianRootfs = hasContainer2,
    )

    menuOpen = false
    desktopLaunchBlackout = false
    PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.DEBIAN_X11_DISPLAY, false)
    waylandVisible = false
    pendingAutoShowWayland = false
    uiMode = UiMode.TERMINAL
    TerminalBackgroundX11.releaseBeforePrimaryAttaches()

    try {
        val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.appscreen_failed_to_open_x11_desktop), Toast.LENGTH_SHORT).show()
    }
    AppLogger.log("Entered Debian X11 desktop")
}

    fun cycleLauncherDefault() {
        launcherDefault = AppPrefs.cycleLauncherDefaultPref(launcherDefault)
        AppPrefs.writeLauncherDefault(prefs, launcherDefault)
        updateLauncherMarkers(context, launcherDefault) 
    }

    fun setLauncherDefaultFromMenuLabel(menuLabel: String) {
        launcherDefault = AppPrefs.menuLabelToLauncherPref(menuLabel)
        AppPrefs.writeLauncherDefault(prefs, launcherDefault)
        updateLauncherMarkers(context, launcherDefault) 
    }
    


    // ----- initialisation effects (unchanged) -----
    LaunchedEffect(startInTerminal) {
        if (startInTerminal) {
            uiMode = UiMode.TERMINAL
            pendingAutoShowWayland = false
            desktopLaunchBlackout = false
        }
    }

    LaunchedEffect(launcherDefault) {
    if (!anyRootfsInstalled) return@LaunchedEffect   // only when containers exist
    when (launcherDefault) {
        AppPrefs.LAUNCHER_PREF_WAYLAND -> prepareWaylandBackend()
        AppPrefs.LAUNCHER_PREF_X11     -> prepareX11Backend()
        // Terminal – do nothing
    }
}


fun runWineDesktopFromEnv() {
    scope.launch {
        // 1. Close the drawer
        drawerState.close()

        // 2. Make sure the Wine terminal session exists
        if (!NativeBridge.isSessionAlive(TerminalSessionIds.WINE_TERMINAL)) {
            NativeBridge.spawnSessionInRootfs(
                TerminalSessionIds.WINE_TERMINAL, 80, 24,
                TerminalSessionIds.rootfsKindForNativeId(TerminalSessionIds.WINE_TERMINAL)
            )
            delay(300)
        }

        // 3. Run xfex (sources env, starts wineserver, shows launcher)
        NativeBridge.writeInput(
            TerminalSessionIds.WINE_TERMINAL,
            "xfex\n".toByteArray()
        )

        // 4. Give Wine a moment to initialize
        delay(2000)

        // 5. Open X11 (this runs the Wine desktop startup script
        //    and switches to MainActivity's X11 view)
        enterWineDesktop()
    }
}

fun requestAppRestart() {
    val intent = context.packageManager
        .getLaunchIntentForPackage(context.packageName)
        ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
    if (intent != null) context.startActivity(intent)
    (context as? Activity)?.finishAffinity()
    System.exit(0)
}

LaunchedEffect(installDone) {
    if (installDone) {
        delay(500) // brief pause to let UI settle
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (intent != null) {
            context.startActivity(intent)
        }
        // Force‑close the current process to avoid any lingering state
        (context as? Activity)?.finish()
        System.exit(0)
    }
}

suspend fun downloadAndExtractTurnipDrivers(containerIds: List<Int>) = withContext(Dispatchers.IO) {
    val baseUrl = "https://github.com/xodiosx/mesa-for-android-container/releases/download/mirror-turnip-26.2.0-devel-20260511"
    val driversDir = File(context.filesDir, "drivers")
    driversDir.mkdirs()

    // ---- Gl4es driver (shared, download once) ----
    val gl4esName = "xodos-gl4es-driver.tar.xz"
    val gl4esFile = File(driversDir, gl4esName)
    val gl4esTmpFile = File(driversDir, "$gl4esName.tmp")

    if (!gl4esFile.exists() || gl4esFile.length() == 0L) {
        withContext(Dispatchers.Main) {
            turnipDownloadProgress = 0 to context.getString(R.string.appscreen_downloading_gl4es_driver)
        }
        gl4esTmpFile.delete()
        try {
            val url = URL("$baseUrl/$gl4esName")
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()
            connection.inputStream.use { input ->
                gl4esTmpFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (gl4esTmpFile.length() == 0L) throw Exception("Gl4es download empty")
            if (!gl4esTmpFile.renameTo(gl4esFile)) throw Exception("Failed to rename gl4es archive")
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                turnipDownloadProgress = -1 to context.getString(R.string.appscreen_gl4es_download_failed_with_message, e.message ?: "")
            }
            return@withContext
        }
    }

    // ---- Per‑container Turnip + gl4es ----
    for (id in containerIds) {
        val distro = DisplayOrchestrator.getContainerDistroType(context, id) ?: continue
        val assetName = "turnip_26.2.0-devel-20260511_${DisplayOrchestrator.turnipAssetPattern(distro)}_arm64.tar.gz"
        val downloadUrl = "$baseUrl/$assetName"
        val destFile = File(driversDir, assetName)
        val tmpFile = File(driversDir, "$assetName.tmp")

        // If Turnip archive already exists, just extract (and also extract gl4es)
        if (destFile.exists() && destFile.length() > 0) {
            withContext(Dispatchers.Main) {
                turnipDownloadProgress = 80 to context.getString(R.string.appscreen_turnip_already_downloaded_extracting)
            }
            val ok = DisplayOrchestrator.extractTurnipDriver(context, id, distro)
            if (ok) {
                // Extract gl4es using the shared method
                DisplayOrchestrator.extractDriverTarball(context, id, gl4esFile)
            }
            withContext(Dispatchers.Main) {
                turnipDownloadProgress = if (ok) 100 to context.getString(R.string.appscreen_done) else -1 to context.getString(R.string.appscreen_extraction_failed_2)
            }
            continue
        }

        tmpFile.delete()
        withContext(Dispatchers.Main) { turnipDownloadInProgress = true }
        try {
            val url = URL(downloadUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()
            val totalSize = connection.contentLengthLong
            connection.inputStream.use { input ->
                tmpFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesCopied = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesCopied += read
                        val pct = if (totalSize > 0) (bytesCopied * 100 / totalSize).toInt() else 0
                        withContext(Dispatchers.Main) {
                            turnipDownloadProgress = pct to context.getString(R.string.appscreen_downloading_turnip_for_container, id)
                        }
                    }
                }
            }
            if (tmpFile.length() == 0L) throw Exception("Turnip download empty")
            if (!tmpFile.renameTo(destFile)) throw Exception("Failed to rename Turnip archive")

            withContext(Dispatchers.Main) { turnipDownloadProgress = 80 to context.getString(R.string.appscreen_extracting) }
            val turnipOk = DisplayOrchestrator.extractTurnipDriver(context, id, distro)
            if (turnipOk) {
                // Extract gl4es into the same container
                DisplayOrchestrator.extractDriverTarball(context, id, gl4esFile)
            }
            withContext(Dispatchers.Main) {
                turnipDownloadProgress = if (turnipOk) 100 to context.getString(R.string.appscreen_done) else -1 to context.getString(R.string.appscreen_turnip_extraction_failed)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                turnipDownloadProgress = -1 to context.getString(R.string.appscreen_download_failed_with_message, e.message ?: "")
            }
            tmpFile.delete()
        } finally {
            withContext(Dispatchers.Main) {
                turnipDownloadInProgress = false
                if (turnipDownloadProgress.first == 100) {
                    DisplayOrchestrator.updateContainersSystemEnvironment(context, prefs)
                }
            }
        }
    }
}

/*fun downloadBootstrapArchive() {
    bootstrapDownloadJob?.cancel()
    bootstrapDownloadJob = scope.launch {
        bootstrapDownloadInProgress = true
        bootstrapDownloadProgress = 0 to context.getString(R.string.appscreen_starting_download)

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val outputFile = File(downloadsDir, "fexcore.tar.xz")
        val tmpFile    = File(downloadsDir, "fexcore.tar.xz.tmp")

        try {
            withContext(Dispatchers.IO) {
                val url = URL("https://github.com/xodiosx/XoDos-Ark/releases/download/vwine10.1/xfex.tar.xz")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "XoDosArk/1.0")
                connection.connect()

                val totalSize = connection.contentLengthLong
                tmpFile.delete()
                connection.inputStream.use { input ->
                    tmpFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesCopied = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            coroutineContext.ensureActive()       // ← cancellation point
                            output.write(buffer, 0, read)
                            bytesCopied += read
                            if (totalSize > 0) {
                                val pct = (bytesCopied * 100 / totalSize).toInt()
                                withContext(Dispatchers.Main) {
                                    bootstrapDownloadProgress = pct to context.getString(R.string.appscreen_downloading_percent, pct)
                                }
                            }
                        }
                    }
                }
            }

            // Atomic swap only after the whole download succeeded
            if (!tmpFile.renameTo(outputFile)) throw Exception("Failed to rename downloaded file")

            withContext(Dispatchers.Main) {
                bootstrapDownloadProgress = 100 to context.getString(R.string.appscreen_download_complete)
                Toast.makeText(context, context.getString(R.string.appscreen_archive_saved_to_downloads), Toast.LENGTH_LONG).show()
            }
        } catch (e: CancellationException) {
            tmpFile.delete()
            throw e
        } catch (e: Exception) {
            Log.e("BootstrapDownload", "Download failed", e)
            tmpFile.delete()
            withContext(Dispatchers.Main) {
                bootstrapDownloadProgress = -1 to context.getString(R.string.appscreen_download_failed_with_message, e.message ?: "")
                Toast.makeText(context, context.getString(R.string.appscreen_download_failed), Toast.LENGTH_LONG).show()
            }
        } finally {
            withContext(Dispatchers.Main) {
                bootstrapDownloadInProgress = false
            }
        }
    }
}
*/
@Composable
 fun WineDownloadOption(title: String, hint: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
    ) {
        FitText(
            text = title,
            color = Color(0xFFC3B6F9),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge,
            maxFontSize = 16.sp,
            minFontSize = 11.sp,
        )
        FitText(
            text = hint,
            color = Color.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall,
            maxFontSize = 12.sp,
            minFontSize = 9.sp,
        )
    }
}

fun downloadAndInstallWine(url: String, fileName: String) {
    wineDownloadJob?.cancel()
    wineDownloadJob = scope.launch {
        wineDownloadInProgress = true
        wineDownloadProgress = 0 to context.getString(R.string.appscreen_starting_download)

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val outputFile = File(downloadsDir, fileName)
        val tmpFile    = File(downloadsDir, "$fileName.tmp")

        try {
            // ── 1. Download to /sdcard/Download (skip if already cached) ──
            val alreadyCached = outputFile.exists() && outputFile.length() > 0L

            if (alreadyCached) {
                // Short pause so the UI switches to progress view before the
                // (very fast) extraction begins — otherwise it may feel like nothing happened.
                withContext(Dispatchers.Main) {
                    wineDownloadProgress = 100 to context.getString(R.string.appscreen_wine_using_cached, fileName)
                }
                delay(400)
            } else {
                withContext(Dispatchers.IO) {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 15_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "XoDosArk/1.0")
                    conn.connect()
                    val totalSize = conn.contentLengthLong
                    tmpFile.delete()
                    conn.inputStream.use { input ->
                        tmpFile.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var copied = 0L
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                coroutineContext.ensureActive()
                                output.write(buf, 0, read)
                                copied += read
                                if (totalSize > 0) {
                                    val pct = (copied * 100 / totalSize).toInt()
                                    withContext(Dispatchers.Main) {
                                        wineDownloadProgress = pct to
                                            context.getString(R.string.appscreen_downloading_percent, pct)
                                    }
                                }
                            }
                        }
                    }
                }
                if (!tmpFile.renameTo(outputFile)) throw Exception("Failed to rename downloaded file")
            }

            // ── 2. Install: rm -rf usr/opt/winece, then extract ──
            withContext(Dispatchers.Main) {
                wineDownloadProgress = 100 to context.getString(R.string.appscreen_extracting)
            }

            val dataDir   = context.filesDir
            val usrDir    = File(dataDir, "usr")
            val cacheDir  = context.cacheDir
            val xzBinary  = File(usrDir, "bin/xz").absolutePath
            val pvBinary  = File(usrDir, "bin/pv").absolutePath
            val tarBinary = File(usrDir, "bin/tar").absolutePath
            val libDir    = File(usrDir, "lib").absolutePath
            val wineceDir = File(usrDir, "opt/winece").absolutePath

            for (p in listOf(xzBinary, pvBinary, tarBinary)) {
                if (!File(p).exists()) throw Exception("Missing binary: $p")
                File(p).setExecutable(true)
            }

            val env = mapOf(
                "PATH"            to "${File(usrDir, "bin").absolutePath}:/system/bin:/sbin:/bin",
                "LD_LIBRARY_PATH" to "$libDir:/system/lib64:/lib",
                "TMPDIR"          to cacheDir.absolutePath
            )

            // rough uncompressed estimate for pv progress
            val estTotalSize = outputFile.length() * 3

            val shellCmd =
                "rm -rf $wineceDir && " +
                "$xzBinary -dc ${outputFile.absolutePath} | " +
                "$pvBinary -n -s $estTotalSize | " +
                "$tarBinary -xf - -C ${dataDir.absolutePath}"

            withContext(Dispatchers.IO) {
                val process = ProcessBuilder("/system/bin/sh", "-c", shellCmd)
                    .directory(cacheDir)
                    .apply { environment().putAll(env) }
                    .redirectErrorStream(false)
                    .start()

                val progressJob = launch {
                    process.errorStream.bufferedReader().use { reader ->
                        var line = reader.readLine()
                        while (line != null) {
                            val pct = line.toIntOrNull()
                            if (pct != null && pct in 0..100) {
                                withContext(Dispatchers.Main) {
                                    wineDownloadProgress = pct to
                                        context.getString(R.string.appscreen_extracting_percent, pct)
                                }
                            }
                            line = reader.readLine()
                        }
                    }
                }
                val exitCode = process.waitFor()
                progressJob.join()

                // If the user pressed Cancel while extraction was running,
                // don't show a success toast for a job the user already left.
                coroutineContext.ensureActive()

                withContext(Dispatchers.Main) {
                    if (exitCode == 0) {
                        wineDownloadProgress = 100 to context.getString(R.string.appscreen_installed)
                        Toast.makeText(context, context.getString(R.string.appscreen_package_installed), Toast.LENGTH_SHORT).show()
                        installWineDependenciesInNativeSession()
                        showWineDownloadDialog = false
                    } else {
                        wineDownloadProgress = -1 to
                            context.getString(R.string.appscreen_extraction_failed_exit, exitCode, "")
                    }
                }
            }
        } catch (e: CancellationException) {
            tmpFile.delete()          // tmp file only — never the cached outputFile
            throw e
        } catch (e: Exception) {
            tmpFile.delete()
            withContext(Dispatchers.Main) {
                wineDownloadProgress = -1 to
                    context.getString(R.string.appscreen_download_failed_with_message, e.message ?: "")
            }
        } finally {
            withContext(Dispatchers.Main) { wineDownloadInProgress = false }
        }
    }
}

fun downloadFullDesktopGUIArchive() {
    fullDesktopDownloadJob?.cancel()
    fullDesktopDownloadJob = scope.launch {
        fullDesktopDownloadInProgress = true
        fullDesktopDownloadProgress = 0 to context.getString(R.string.appscreen_starting_download)

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()
        val outputFile = File(downloadsDir, "ark-desktop-gui.tar.xz")
        val tmpFile    = File(downloadsDir, "ark-desktop-gui.tar.xz.tmp")

        try {
            withContext(Dispatchers.IO) {
                val url = URL("https://github.com/xodiosx/XoDos-Ark/releases/download/v0.6.3-full/ark2.tar.xz")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "XoDosArk/1.0")
                connection.connect()

                val totalSize = connection.contentLengthLong
                tmpFile.delete()
                connection.inputStream.use { input ->
                    tmpFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesCopied = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            coroutineContext.ensureActive()
                            output.write(buffer, 0, read)
                            bytesCopied += read
                            if (totalSize > 0) {
                                val pct = (bytesCopied * 100 / totalSize).toInt()
                                withContext(Dispatchers.Main) {
                                    fullDesktopDownloadProgress = pct to context.getString(R.string.appscreen_downloading_percent, pct)
                                }
                            }
                        }
                    }
                }
            }

            if (!tmpFile.renameTo(outputFile)) throw Exception("Failed to rename downloaded file")

            withContext(Dispatchers.Main) {
                fullDesktopDownloadProgress = 100 to context.getString(R.string.appscreen_download_complete)
                Toast.makeText(context, context.getString(R.string.appscreen_xfce4_desktop_gui_archive_saved_to_downloads), Toast.LENGTH_LONG).show()
            }
        } catch (e: CancellationException) {
            tmpFile.delete()
            throw e
        } catch (e: Exception) {
            Log.e("FullDesktopDownload", "Download failed", e)
            tmpFile.delete()
            withContext(Dispatchers.Main) {
                fullDesktopDownloadProgress = -1 to context.getString(R.string.appscreen_download_failed_with_message, e.message ?: "")
                Toast.makeText(context, context.getString(R.string.appscreen_full_desktop_gui_download_failed), Toast.LENGTH_LONG).show()
            }
        } finally {
            withContext(Dispatchers.Main) {
                fullDesktopDownloadInProgress = false
            }
        }
    }
}

    // ----- native init and container check -----
    
LaunchedEffect(Unit) {
    AppLogger.log("Starting native init and asset sync")
    //  clean mic
    MicManager.startupCleanup()
    IsoManager.syncWfmFromAssets(context)
for (id in 1..3) {
        val opts = NativeInstallCoordinator.loadProotOptions(context, id)
        NativeInstallCoordinator.saveProotOptions(context, id, opts)
    }
        
    // Snapshot before init — used to detect "bootstrap was just installed"
    val pkgPath = File(context.filesDir, "usr/bin/pkg")
    val hadPkgBefore = pkgPath.exists()

    val r = NativeInstallCoordinator.initNativeAndSyncAssets(
        context = context,
        prefs = prefs,
        allowedVulkan = VULKAN_MODES,
        allowedOpenGL = OPENGL_MODES,
        onBootstrapProgress = { pct, msg ->
            withContext(Dispatchers.Main) {
                bootstrapInProgress = true
                bootstrapProgress = pct to msg
            }
        },
    )

    bootstrapInProgress = false

    if (!r.ok) {
        if (!pkgPath.exists()) {
            bootstrapError = bootstrapProgress.second
        } else {
            errorMsg = context.getString(R.string.appscreen_failed_to_init_native_layer)
            AppLogger.log("Native init FAILED")
        }
        return@LaunchedEffect
    }

    // If bootstrap was just extracted on this launch, restart the app —
    // exactly the same flow as after a distro install (installDone → restart).
    if (!hadPkgBefore && pkgPath.exists()) {
        AppLogger.log("Bootstrap just installed – restarting app")
        installDone = true
        return@LaunchedEffect
    }

    initialized = true
refreshContainerState()
refreshLegacyFlags()

// ── Auto-activate native terminal ───────────────────────────────
// If no container has been marked installed yet, but the native
// environment exists under $PREFIX, silently mark container 1 as
// native. This removes the need for the "activate native terminal"
// dialog and lets the terminal start immediately.
//
// If any container already has a real distro (marker present),
// leave it alone — proot.rs will run its shell.
if (!anyRootfsInstalled) {
    val hasNativeEnv =
        File(context.filesDir, "usr/bin/bash").exists() ||
        File(context.filesDir, "usr/bin/sh").exists()
    if (hasNativeEnv) {
        markContainerAsNativeTerminal(1)
        refreshContainerState()
        refreshLegacyFlags()
        AppLogger.log("Auto-marked container 1 as native terminal (bash -l)")
    }
}

// Automatically detect and save the distro type for every installed container
val mask = NativeBridge.getInstalledContainersMask()
for (id in 1..3) {
    if ((mask and (1 shl (id - 1))) != 0) {
        withContext(Dispatchers.IO) {
            val distro = DisplayOrchestrator.getContainerDistroType(context, id)
                ?: NativeInstallCoordinator.detectDistroFromRootfs(context, id)
            if (distro != null) {
                NativeInstallCoordinator.saveContainerDistro(context, id, distro)
                NativeInstallCoordinator.writeContainerEnvironment(context, id, distro)
            }
        }
    }
}

File(context.filesDir, "drivers").listFiles { f ->
    f.name.startsWith("turnip_") && f.name.endsWith(".tmp")
}?.forEach { it.delete() }

onAppReady()
desktopVulkanMode = r.desktopModes.vulkan
desktopOpenGLMode = r.desktopModes.openGL
GraphicsModeController.applyAndMaybeToggleVirglHost(
    prefs = prefs,
    previous = GraphicsModeController.Modes(desktopVulkanMode, desktopOpenGLMode),
    modes = GraphicsModeController.Modes(desktopVulkanMode, desktopOpenGLMode),
)

updateLauncherMarkers(context, launcherDefault)

// Show the distro picker ONLY if, after the auto-activation
// above, there is still genuinely nothing to run
// (no distro AND no native env to fall back to).
if (!anyRootfsInstalled) {
    distroFetchState = DistroFetchState.LOADING
    availableDistros = withContext(Dispatchers.IO) {
        NativeInstallCoordinator.fetchAvailableDistros(NativeInstallCoordinator.DistroSource.CUSTOM)
    }
    distroFetchState = if (availableDistros.isEmpty()) {
        DistroFetchState.ERROR
    } else {
        DistroFetchState.LOADED
    }
    showDistroSelection = true
}

}
// Pin the mic as default source independently of native init.
// Runs in parallel — never blocks terminal startup.
LaunchedEffect(Unit) {
    if (!MicManager.isEnabled(context)) return@LaunchedEffect
    // Wait up to 15 s for PulseAudio to accept commands, then pin
    var waited = 0
    while (waited < 15_000) {
        if (MicManager.getSourceName() != null) {
            MicManager.pinDefaultSource()
            return@LaunchedEffect
        }
        delay(500)
        waited += 500
    }
}
    // ----- wayland / X11 effects (unchanged) -----
    LaunchedEffect(showWayland) {
        setImmersiveMode(context as? Activity, immersive = showWayland)
        InputRouteState.waylandVisible = showWayland
        if (showWayland) {
            desktopLaunchBlackout = false
            try { WaylandBridge.nativeResetKeyboardState() } catch (_: Throwable) {}
        }
    }

    LaunchedEffect(showX11) {
        InputRouteState.lorieX11DisplayVisible = showX11
        if (showX11) {
            waylandVisible = false
            pendingAutoShowWayland = false
        }
    }

    LaunchedEffect(menuOpen, settingsOpen, waylandScriptEditorOpen, x11ScriptEditorOpen) {
        if (menuOpen || settingsOpen || waylandScriptEditorOpen || x11ScriptEditorOpen) {
            keyboardWanted = false
        }
    }

    LaunchedEffect(pendingAutoShowWayland) {
        if (!pendingAutoShowWayland) return@LaunchedEffect
        WaylandVisibilityCoordinator.waitUntilDesktopClientReady(
          //  isStillPending = { pendingAutoShowWayland && uiMode == UiMode.ARCH_WAYLAND_DESKTOP && !waylandVisible },
            isStillPending = {
    pendingAutoShowWayland &&
    (uiMode == UiMode.ARCH_WAYLAND_DESKTOP || uiMode == UiMode.DEBIAN_WAYLAND_DESKTOP || uiMode == UiMode.WINE_WAYLAND_DESKTOP) &&
    !waylandVisible
},
            hasActiveClients = { WaylandBridge.nativeHasActiveClients() },
            onReady = {
                waylandVisible = true
                pendingAutoShowWayland = false
            },
        )
    }

 LaunchedEffect(anyRootfsInstalled, startInTerminal) {
    if (!anyRootfsInstalled) return@LaunchedEffect

    // Wait for the headless display sessions to be ready (native init already did)
    var waited = 0
    while (!NativeBridge.isSessionAlive(TerminalSessionIds.ARCH_WAYLAND_DISPLAY) && waited < 100) {
        delay(20)
        waited++
    }
    // Even if wait fails, we try anyway – the prepare functions handle missing sessions internally

    desktopLaunchBlackout = false
    if (uiMode == UiMode.ARCH_WAYLAND_DESKTOP) return@LaunchedEffect
    uiMode = UiMode.TERMINAL

// ── Auto‑start backend based on saved launcher default ──
val effectiveDefault = AppPrefs.migrateLauncherPref(launcherDefault)
AppLogger.log("Auto-start check: launcherDefault = $launcherDefault, effective = $effectiveDefault")
when (effectiveDefault) {
    AppPrefs.LAUNCHER_PREF_WAYLAND -> {
        AppLogger.log("Preparing Wayland backend...")
        prepareWaylandBackend()
    }
    AppPrefs.LAUNCHER_PREF_X11 -> {
        AppLogger.log("Preparing X11 backend...")
        prepareX11Backend()
    }
    // Terminal or anything else → do nothing
    else -> AppLogger.log("Default is Terminal – no backend to prepare")
}
    
    
}
    
    
// Keep the X11 task manager in sync with the active terminal session
LaunchedEffect(terminalSessionState.activeSessionId) {
    prefs.edit().putInt("active_terminal_session_id", terminalSessionState.activeSessionId).apply()
}

// Bootstrap extraction effect
LaunchedEffect(bootstrapUri) {
    val uri = bootstrapUri ?: return@LaunchedEffect
    // Prevent starting a new extraction if one is already running
    if (bootstrapInProgress) return@LaunchedEffect

    bootstrapInProgress = true
    bootstrapProgress = 0 to context.getString(R.string.appscreen_copying_archive)

    val dataDir = context.filesDir          // /data/data/app.xodos2/files
    val usrDir = File(dataDir, "usr")       // /data/data/app.xodos2/files/usr
    val cacheDir = context.cacheDir
    val tempArchive = File(cacheDir, "bootstrap_${System.currentTimeMillis()}.tar.xz")

    try {
        // 1. Copy selected file to cache
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempArchive.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }

        // 2. Total uncompressed size for pv (209 MB – adjust if needed)
        val totalSize = 209L * 1024 * 1024

        // 3. Binary paths
        val xzBinary  = File(usrDir, "bin/xz").absolutePath
        val pvBinary  = File(usrDir, "bin/pv").absolutePath
        val tarBinary = File(usrDir, "bin/tar").absolutePath
        val libDir    = File(usrDir, "lib").absolutePath

        // Check that all binaries exist
        for (path in listOf(xzBinary, pvBinary, tarBinary)) {
            if (!File(path).exists()) throw Exception("Missing binary: $path")
            File(path).setExecutable(true)
        }

        val env = mapOf(
            "PATH"            to "${File(usrDir, "bin").absolutePath}:/system/bin:/sbin:/bin",
            "LD_LIBRARY_PATH" to "$libDir:/system/lib64:/lib",
            "TMPDIR"          to cacheDir.absolutePath
        )

        // Command: xz -dc archive | pv -n -s totalsize | tar -xf - -C dataDir
        // Remove conflicting directory before extraction
val shellCmd = "rm -rf ${dataDir.absolutePath}/usr/share/X11/xkb && " +
               "$xzBinary -dc ${tempArchive.absolutePath} | " +
               "$pvBinary -n -s $totalSize | " +
               "$tarBinary -xf - -C ${dataDir.absolutePath}"
        Log.d("BootstrapExtract", "Running: $shellCmd")

        val cmd = arrayOf("/system/bin/sh", "-c", shellCmd)

        // 4. Run extraction and read progress
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(*cmd)
                .directory(cacheDir)
                .apply { environment().putAll(env) }
                .redirectErrorStream(false)   // pv writes numeric progress to stderr
                .start()

            // Read stderr (pv progress) and collect error output
            val stderrLines = mutableListOf<String>()
            val progressJob = launch {
                process.errorStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        stderrLines.add(line)
                        val percent = line.toIntOrNull()
                        if (percent != null && percent in 0..100) {
                            withContext(Dispatchers.Main) {
                                bootstrapProgress = percent to context.getString(R.string.appscreen_extracting_percent, percent)
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }

            val exitCode = process.waitFor()
            progressJob.join()   // wait for all progress lines to be processed
            tempArchive.delete()

            val stderrLog = stderrLines.joinToString("\n")
            Log.d("BootstrapExtract", "Exit code: $exitCode, stderr:\n$stderrLog")

            withContext(Dispatchers.Main) {
                if (exitCode == 0) {
                    bootstrapProgress = 100 to context.getString(R.string.appscreen_installed)
                    Toast.makeText(context, context.getString(R.string.appscreen_package_installed), Toast.LENGTH_SHORT).show()
                    installWineDependenciesInNativeSession()
                } else {
                    val errMsg = if (stderrLog.isNotEmpty()) stderrLog else "unknown error"
                    bootstrapError = context.getString(R.string.appscreen_extraction_failed_exit, exitCode, errMsg)
                    Toast.makeText(context, context.getString(R.string.appscreen_extraction_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    } catch (e: Exception) {
        Log.e("BootstrapExtract", "Extraction error", e)
        withContext(Dispatchers.Main) {
            bootstrapError = context.getString(R.string.appscreen_error_with_message, e.message ?: "")
            Toast.makeText(context, context.getString(R.string.appscreen_installation_error), Toast.LENGTH_LONG).show()
        }
    } finally {
        // Clear the URI only after everything finishes – prevents early cancellation
        withContext(Dispatchers.Main) {
            bootstrapInProgress = false
            bootstrapUri = null
        }
    }
}

    // ----- graphics helpers -----
    fun checkAndPromptPanvkDrivers() {
    val missing = mutableListOf<Int>()
    for (id in 1..3) {
        val installed = when (id) {
            1 -> hasContainer1
            2 -> hasContainer2
            3 -> hasContainer3
            else -> false
        }
        if (!installed) continue

        // Skip containers that already have the PANVK driver marker
        if (DisplayOrchestrator.isPanvkDriverInstalled(context, id)) {
            Log.d("Panvk", "Container $id already has PANVK driver")
            continue
        }

        var distro = DisplayOrchestrator.getContainerDistroType(context, id)
        if (distro == null) {
            distro = NativeInstallCoordinator.detectDistroFromRootfs(context, id)
            if (distro != null) {
                NativeInstallCoordinator.saveContainerDistro(context, id, distro)
                NativeInstallCoordinator.writeContainerEnvironment(context, id, distro)
            }
        }
        if (distro == null) {
            Log.w("Panvk", "Skipping container $id – cannot determine distro type")
            continue
        }

        missing.add(id)
    }

    if (missing.isNotEmpty()) {
        Log.d("Panvk", "Missing PANVK drivers for: $missing")
        panvkMissingContainers = missing
        showPanvkDriverDialog = true
    } else {
        Log.d("Panvk", "All containers have PANVK driver.")
    }
}

suspend fun downloadAndExtractPanvkDrivers(containerIds: List<Int>) = withContext(Dispatchers.IO) {
    val baseUrl = "https://github.com/xodiosx/mesa-for-android-container/releases/download/mirror-turnip-26.2.0-devel-20260511"
    val driversDir = File(context.filesDir, "drivers")
    driversDir.mkdirs()

    // ---- Shared gl4es driver (download once) ----
    val gl4esName = "xodos-gl4es-driver.tar.xz"
    val gl4esFile = File(driversDir, gl4esName)
    val gl4esTmpFile = File(driversDir, "$gl4esName.tmp")

    if (!gl4esFile.exists() || gl4esFile.length() == 0L) {
        withContext(Dispatchers.Main) {
            panvkDownloadProgress = 0 to context.getString(R.string.appscreen_downloading_gl4es_driver)
        }
        gl4esTmpFile.delete()
        try {
            val url = URL("$baseUrl/$gl4esName")
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()
            connection.inputStream.use { input ->
                gl4esTmpFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (gl4esTmpFile.length() == 0L) throw Exception("gl4es download empty")
            if (!gl4esTmpFile.renameTo(gl4esFile)) throw Exception("Failed to rename gl4es archive")
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                panvkDownloadProgress = -1 to context.getString(R.string.appscreen_gl4es_download_failed_lower_with_message, e.message ?: "")
            }
            return@withContext
        }
    }

    // ---- Shared PANVK driver (download once) ----
    val panvkName = "panvk.tar.xz"
    val panvkFile = File(driversDir, panvkName)
    val panvkTmpFile = File(driversDir, "$panvkName.tmp")

    if (!panvkFile.exists() || panvkFile.length() == 0L) {
        withContext(Dispatchers.Main) {
            panvkDownloadProgress = 0 to context.getString(R.string.appscreen_downloading_panvk_driver)
        }
        panvkTmpFile.delete()
        try {
            val url = URL("$baseUrl/$panvkName")
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()
            val totalSize = connection.contentLengthLong
            connection.inputStream.use { input ->
                panvkTmpFile.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesCopied = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesCopied += read
                        val pct = if (totalSize > 0) (bytesCopied * 100 / totalSize).toInt() else 0
                        withContext(Dispatchers.Main) {
                            panvkDownloadProgress = pct to context.getString(R.string.appscreen_downloading_panvk_percent, pct)
                        }
                    }
                }
            }
            if (panvkTmpFile.length() == 0L) throw Exception("PANVK download empty")
            if (!panvkTmpFile.renameTo(panvkFile)) throw Exception("Failed to rename PANVK archive")
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                panvkDownloadProgress = -1 to context.getString(R.string.appscreen_panvk_download_failed, e.message ?: "")
            }
            return@withContext
        }
    }

    // ---- Extract PANVK + gl4es into each container ----
    for (id in containerIds) {
        withContext(Dispatchers.Main) {
            panvkDownloadProgress = 80 to context.getString(R.string.appscreen_extracting_panvk_into_container, id)
        }
        val panvkOk = DisplayOrchestrator.extractPanvkDriver(context, id)
        if (!panvkOk) {
            withContext(Dispatchers.Main) {
                panvkDownloadProgress = -1 to context.getString(R.string.appscreen_panvk_extraction_failed_container, id)
            }
            return@withContext
        }

        // Extract the shared gl4es archive into the same container
        withContext(Dispatchers.Main) {
            panvkDownloadProgress = 90 to context.getString(R.string.appscreen_extracting_gl4es_into_container, id)
        }
        DisplayOrchestrator.extractDriverTarball(context, id, gl4esFile)
    }

    withContext(Dispatchers.Main) {
        panvkDownloadProgress = 100 to context.getString(R.string.appscreen_done)
        DisplayOrchestrator.updateContainersSystemEnvironment(context, prefs)
    }
}


fun checkAndPromptTurnipDrivers() {
    val missing = mutableListOf<Int>()
    for (id in 1..3) {
        val installed = when (id) {
            1 -> hasContainer1
            2 -> hasContainer2
            3 -> hasContainer3
            else -> false
        }
        if (!installed) continue

        // Skip containers that already have the custom driver marker
        if (DisplayOrchestrator.isTurnipDriverInstalled(context, id)) {
            Log.d("Turnip", "Container $id already has Turnip driver")
            continue
        }

        var distro = DisplayOrchestrator.getContainerDistroType(context, id)
        if (distro == null) {
            distro = NativeInstallCoordinator.detectDistroFromRootfs(context, id)
            if (distro != null) {
                NativeInstallCoordinator.saveContainerDistro(context, id, distro)
                NativeInstallCoordinator.writeContainerEnvironment(context, id, distro)
            }
        }
        if (distro == null) {
            Log.w("Turnip", "Skipping container $id – cannot determine distro type")
            continue
        }

        missing.add(id)
    }

    if (missing.isNotEmpty()) {
        Log.d("Turnip", "Missing drivers for: $missing")
        turnipMissingContainers = missing
        showTurnipDriverDialog = true
    } else {
        Log.d("Turnip", "All containers have the Turnip driver.")
    }
}
    
    fun injectGraphicsEnvToAllTerminals() {
    val envContent = DisplayOrchestrator.buildSystemGraphicsEnv(prefs)
    // Keep only non‑blank lines; they already include "export"/"unset"
    val snippet = envContent.lines()
        .filter { it.isNotBlank() }
        .joinToString(separator = "\n") + "\n"
    val bytes = snippet.toByteArray(Charsets.UTF_8)

    // Inject into all currently active terminal sessions
    val ids = listOf(
        TerminalSessionIds.ARCH_TERMINAL,
        TerminalSessionIds.DEBIAN_TERMINAL,
        TerminalSessionIds.WINE_TERMINAL
    )
    for (id in ids) {
        if (NativeBridge.isSessionAlive(id)) {
            NativeBridge.writeInput(id, bytes)
        }
    }
    // Also inject into headless display sessions
    val headlessIds = listOf(
        TerminalSessionIds.ARCH_WAYLAND_DISPLAY,
        TerminalSessionIds.DEBIAN_X11_DISPLAY,
        TerminalSessionIds.ARCH_X11_DISPLAY,
        TerminalSessionIds.WINE_X11_DISPLAY
    )
    for (id in headlessIds) {
        if (NativeBridge.isSessionAlive(id)) {
            NativeBridge.writeInput(id, bytes)
        }
    }
}

    /**
 * If Adrenotools has a *custom* driver active (not "system") AND the desktop
 * Vulkan mode is "WRAPPER", re-source the drv file in the active terminal so
 * the Adrenotools environment wins over the drawer's graphics env.
 */
fun applyAdrenotoolsDrvIfNeeded() {
    val adrenotoolsActive = AdrenotoolsDriverManager.activeName(context) != null
    if (!adrenotoolsActive || desktopVulkanMode != "WRAPPER") return

    val sessionId = terminalSessionState.activeSessionId
    if (NativeBridge.isSessionAlive(sessionId)) {
        NativeBridge.writeInput(
            sessionId,
            "source ${AdrenotoolsDriverManager.DRV_PATH_IN_CONTAINER}\n".toByteArray()
        )
    }
    AppLogger.log("Adrenotools drv re-sourced (WRAPPER + custom driver active)")
}
    
    fun setDesktopVulkanMode(mode: String) {
    val prev = GraphicsModeController.Modes(desktopVulkanMode, desktopOpenGLMode)
    var next = GraphicsModeController.sanitize(
        GraphicsModeController.Modes(vulkan = mode, openGL = desktopOpenGLMode),
        allowedVulkan = VULKAN_MODES,
        allowedOpenGL = OPENGL_MODES,
    )

    // force OpenGL to LLVMPIPE when Venus is active
    if (next.vulkan == "VENUS") {
        next = next.copy(openGL = "LLVMPIPE")
    }

    // ── NEW: Adrenotools + WRAPPER ⇒ force LLVMPIPE if VIRGL was selected ──
    if (next.vulkan == "WRAPPER" && next.openGL == "VIRGL" &&
        AdrenotoolsDriverManager.activeName(context) != null
    ) {
        next = next.copy(openGL = "LLVMPIPE")
    }

    if (GraphicsModeController.applyAndMaybeToggleVirglHost(prefs, prev, next)) {
        rendererSessionResetEpoch++
    }

    desktopVulkanMode = next.vulkan
    desktopOpenGLMode = next.openGL

    DisplayOrchestrator.updateContainersSystemEnvironment(context, prefs)
    injectGraphicsEnvToAllTerminals()

    // ── NEW: re-apply adrenotools env if active + WRAPPER selected ──
    applyAdrenotoolsDrvIfNeeded()

    if (next.vulkan == "TURNIP") {
        checkAndPromptTurnipDrivers()
    }
    if (next.vulkan == "PANVK") {
        checkAndPromptPanvkDrivers()
    }

    AppLogger.log("Vulkan mode set to $mode (OpenGL forced to LLVMPIPE if Venus)")
}
    
fun setDesktopOpenGLMode(mode: String) {
    // ── NEW: if Adrenotools is active + WRAPPER, disallow VIRGL → use LLVMPIPE ──
    val effectiveMode =
        if (mode == "VIRGL" &&
            AdrenotoolsDriverManager.activeName(context) != null &&
            desktopVulkanMode == "WRAPPER"
        ) "LLVMPIPE" else mode

    val prev = GraphicsModeController.Modes(desktopVulkanMode, desktopOpenGLMode)
    val next = GraphicsModeController.sanitize(
        GraphicsModeController.Modes(vulkan = desktopVulkanMode, openGL = effectiveMode),
        allowedVulkan = VULKAN_MODES,
        allowedOpenGL = OPENGL_MODES,
    )
    if (GraphicsModeController.applyAndMaybeToggleVirglHost(prefs, prev, next)) {
        rendererSessionResetEpoch++
    }
    desktopVulkanMode = next.vulkan
    desktopOpenGLMode = next.openGL
    DisplayOrchestrator.updateContainersSystemEnvironment(context, prefs)
    injectGraphicsEnvToAllTerminals()

    // ── NEW: re-apply adrenotools env after every OpenGL change if condition holds ──
    applyAdrenotoolsDrvIfNeeded()

    AppLogger.log(
        "OpenGL mode set to $mode (effective ${next.openGL})"
    )
}

    // ----- persistence helpers (unchanged) -----
    fun persistMouseMode(mode: Int) { mouseMode = mode; AppPrefs.writeInt(prefs, "mouse_mode", mode) }
    fun persistResolutionPercent(pct: Int) {
        resolutionPercent = pct.coerceIn(10, 100)
        AppPrefs.writeInt(prefs, "resolution_percent", resolutionPercent)
    }
    fun persistScalePercent(pct: Int) {
        scalePercent = pct.coerceIn(100, 1000).let { v -> ((v + 50) / 100) * 100 }
        AppPrefs.writeInt(prefs, "scale_percent", scalePercent)
    }
    fun persistTerminalFont(key: String) { terminalFontKey = key; AppPrefs.writeString(prefs, ShellFonts.PREF_KEY, key) }
    fun persistX11MouseMode(mode: EmbeddedX11Controller.MouseMode) {
        x11MouseMode = mode
        AppPrefs.writeString(prefs, "x11_mouse_mode", if (mode == EmbeddedX11Controller.MouseMode.TOUCH) "touch" else "touchpad")
    }

    fun applyX11ResolutionModeFromLabel(label: String) {
        x11ResolutionModeLabel = label
        X11OutputSettings.setResolutionMode(context.applicationContext, x11ResolutionModeInternalForLabel(label))
    }
    fun applyX11DisplayScaleFromLabel(label: String) {
        val pct = label.removeSuffix("%").trim().toIntOrNull() ?: return
        x11DisplayScale = pct.coerceIn(30, 300).let { v -> (v / 10) * 10 }
        X11OutputSettings.setDisplayScalePercent(context.applicationContext, x11DisplayScale)
    }
    fun applyX11ResolutionExactFromLabel(label: String) {
        if (label.isBlank()) return
        x11ResolutionExact = label
        X11OutputSettings.setResolutionExact(context.applicationContext, label)
    }
    fun applyX11ResolutionCustomWxh() {
        val raw = x11ResolutionCustom.trim()
        val m = X11_CUSTOM_RESOLUTION_PATTERN.matcher(raw)
        if (!m.matches()) return
        val wxh = "${m.group(1)}x${m.group(2)}"
        x11ResolutionCustom = wxh
        X11OutputSettings.setResolutionCustom(context.applicationContext, wxh)
    }

    // =================================================================
    // UI Rendering
    // =================================================================
    errorMsg?.let { msg ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(msg, color = MaterialTheme.colorScheme.onBackground)
        }
        return
    }
    
    if (bootstrapInProgress) {
    InstallScreen(progress = bootstrapProgress.first, message = bootstrapProgress.second)
    return
}

    if (!initialized) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

if (deleteInProgress || removingNativeEnvInProgress) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF07040E)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp), color = Color(0xFFC3B6F9))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    if (removingNativeEnvInProgress) stringResource(R.string.appscreen_removing_native_environment)
                    else stringResource(R.string.appscreen_deleting_container),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White
                )
            }
        }
    }
    return
}

if (backupInProgress) {
    InstallScreen(progress = backupProgress.first, message = backupProgress.second)
    return
}
    if (installInProgress) {
        InstallScreen(progress = installProgress.first, message = installProgress.second)
        return
    }




if (bootstrapError != null) {
    AlertDialog(
        onDismissRequest = { bootstrapError = null },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = { Text(stringResource(R.string.appscreen_package_installation), fontWeight = FontWeight.Bold, color = Color.White) },
        text = { Text(bootstrapError ?: "", color = Color.White.copy(alpha = 0.85f)) },
        confirmButton = {
            GlassButton(onClick = { bootstrapError = null }) { Text(stringResource(R.string.appscreen_ok), color = Color(0xFFC3B6F9), fontWeight = FontWeight.Bold) }
        }
    )
}

fun downloadCustomWinComponent(url: String) {
    wineDownloadJob?.cancel()
    wineDownloadJob = scope.launch {
        wineDownloadInProgress = true
        wineDownloadProgress = 0 to context.getString(R.string.appscreen_starting_download)

        val fileName = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        if (fileName.isBlank()) {
            wineDownloadProgress = -1 to context.getString(R.string.appscreen_invalid_url)
            wineDownloadInProgress = false
            return@launch
        }

        val targetDir = File(context.filesDir, "usr/wincomponents/d3d")
        targetDir.mkdirs()
        val outputFile = File(targetDir, fileName)
        val tmpFile = File(targetDir, "$fileName.tmp")

        try {
            withContext(Dispatchers.IO) {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "XoDosArk/1.0")
                conn.connect()
                val totalSize = conn.contentLengthLong
                tmpFile.delete()
                conn.inputStream.use { input ->
                    tmpFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            coroutineContext.ensureActive()
                            output.write(buf, 0, read)
                            copied += read
                            if (totalSize > 0) {
                                val pct = (copied * 100 / totalSize).toInt()
                                withContext(Dispatchers.Main) {
                                    wineDownloadProgress = pct to
                                        context.getString(R.string.appscreen_downloading_percent, pct)
                                }
                            }
                        }
                    }
                }
            }

            if (!tmpFile.renameTo(outputFile)) throw Exception("Failed to rename file")

            withContext(Dispatchers.Main) {
                wineDownloadProgress = 100 to fileName
                Toast.makeText(context, fileName, Toast.LENGTH_SHORT).show()
                wineCustomUrl = ""
                wineDownloadInProgress = false
            }
        } catch (e: CancellationException) {
            tmpFile.delete()
            throw e
        } catch (e: Exception) {
            tmpFile.delete()
            withContext(Dispatchers.Main) {
                wineDownloadProgress = -1 to
                    context.getString(R.string.appscreen_download_failed_with_message, e.message ?: "")
                wineDownloadInProgress = false
            }
        }
    }
}

if (showWineDownloadDialog) {
    val busy = wineDownloadInProgress
    AlertDialog(
        onDismissRequest = { if (!busy) showWineDownloadDialog = false },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = {
            FitText(
                text = stringResource(R.string.appscreen_download_wine_250mb),
                fontWeight = FontWeight.Bold,
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                maxFontSize = 20.sp,
                minFontSize = 13.sp,
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (!busy) {
                    // ── 3 preset download rows ──
                    WineDownloadOption(
                        title = stringResource(R.string.appscreen_wine_components),
                        hint  = stringResource(R.string.appscreen_wine_components_hint),
                        onClick = {
                            downloadAndInstallWine(
                                "https://github.com/xodiosx/XoDos-Ark/releases/download/vwine10.6/wcomp.tar.xz",
                                "wcomp.tar.xz"
                            )
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    WineDownloadOption(
                        title = stringResource(R.string.appscreen_wine_bionic),
                        hint  = stringResource(R.string.appscreen_wine_bionic_hint),
                        onClick = {
                            downloadAndInstallWine(
                                "https://github.com/xodiosx/XoDos-Ark/releases/download/vwine10.6/xfex-bio.tar.xz",
                                "xfex-bio.tar.xz"
                            )
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                    WineDownloadOption(
                        title = stringResource(R.string.appscreen_wine_bionic_ntsync),
                        hint  = stringResource(R.string.appscreen_wine_bionic_ntsync_hint),
                        onClick = {
                            downloadAndInstallWine(
                                "https://github.com/xodiosx/XoDos-Ark/releases/download/vwine10.6/xfex-ntsync.tar.xz",
                                "xfex-ntsync.tar.xz"
                            )
                        }
                    )

                    Spacer(Modifier.height(20.dp))
                    HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 1.dp)
                    Spacer(Modifier.height(16.dp))

                    // ── Custom URL input ──
                    OutlinedTextField(
                        value = wineCustomUrl,
                        onValueChange = {
                            wineCustomUrl = it
                            wineCustomUrlError = ""
                        },
                        placeholder = {
                            FitText(
                                text = stringResource(R.string.appscreen_wine_custom_url_hint),
                                color = Color.White.copy(alpha = 0.35f),
                                style = MaterialTheme.typography.bodySmall,
                                maxFontSize = 12.sp,
                                minFontSize = 8.sp,
                            )
                        },
                        isError = wineCustomUrlError.isNotEmpty(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFC3B6F9),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            cursorColor = Color(0xFFC3B6F9)
                        )
                    )
                    if (wineCustomUrlError.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        FitText(
                            text = wineCustomUrlError,
                            color = Color(0xFFFF6B6B),
                            style = MaterialTheme.typography.bodySmall,
                            maxFontSize = 12.sp,
                            minFontSize = 9.sp,
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── Download from URL ──
                    GlassButton(
                        onClick = {
                            val url = wineCustomUrl.trim()
                            val pattern = Regex(
                                "^https?://.*\\.(tar\\.xz|tar\\.gz|tgz|7z|zip|tar)$",
                                RegexOption.IGNORE_CASE
                            )
                            if (!pattern.matches(url)) {
                                wineCustomUrlError = context.getString(R.string.appscreen_wine_custom_url_invalid)
                            } else {
                                wineCustomUrlError = ""
                                downloadCustomWinComponent(url)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FitText(
                            text = stringResource(R.string.appscreen_wine_custom_download),
                            color = Color(0xFFC3B6F9),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            maxFontSize = 14.sp,
                            minFontSize = 9.sp,
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // ── Pick from device ──
                    GlassButton(
                        onClick = {
                            pickWineComponent.launch(
                                arrayOf(
                                    "application/x-xz",
                                    "application/x-tar",
                                    "application/gzip",
                                    "application/x-7z-compressed",
                                    "application/zip",
                                    "*/*"
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FitText(
                            text = stringResource(R.string.appscreen_wine_custom_pick_file),
                            color = Color(0xFFC3B6F9),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                            maxFontSize = 14.sp,
                            minFontSize = 9.sp,
                        )
                    }
                } else {
                    // ── Progress view ──
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        if (wineDownloadProgress.first >= 0) {
                            LinearProgressIndicator(
                                progress = { wineDownloadProgress.first / 100f },
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(0xFFC3B6F9),
                                trackColor = Color.White.copy(alpha = 0.1f)
                            )
                            Spacer(Modifier.height(8.dp))
                            FitText(
                                text = wineDownloadProgress.second,
                                color = Color.White.copy(alpha = 0.85f),
                                style = MaterialTheme.typography.bodyMedium,
                                maxFontSize = 14.sp,
                                minFontSize = 9.sp,
                            )
                        } else {
                            FitText(
                                text = wineDownloadProgress.second,
                                color = Color(0xFFFF6B6B),
                                style = MaterialTheme.typography.bodyMedium,
                                maxFontSize = 14.sp,
                                minFontSize = 9.sp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            GlassButton(onClick = {
                if (busy) {
                    wineDownloadJob?.cancel()
                    wineDownloadJob = null
                    wineDownloadInProgress = false
                    wineDownloadProgress = 0 to ""
                } else {
                    showWineDownloadDialog = false
                }
            }) {
                FitText(
                    text = stringResource(R.string.appscreen_cancel),
                    color = if (busy) Color(0xFFFF6B6B) else Color.White.copy(alpha = 0.8f),
                    fontWeight = if (busy) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.bodyMedium,
                    maxFontSize = 14.sp,
                    minFontSize = 9.sp,
                )
            }
        }
    )
}

/*
if (bootstrapDownloadInProgress) {
    AlertDialog(
        onDismissRequest = { // cannot dismiss while downloading 
         },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = { Text(stringResource(R.string.appscreen_downloading_wine_250mb), fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (bootstrapDownloadProgress.first >= 0) {
                    LinearProgressIndicator(
                        progress = { bootstrapDownloadProgress.first / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFC3B6F9),
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        bootstrapDownloadProgress.second,
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        bootstrapDownloadProgress.second,
                        color = Color(0xFFFF6B6B)
                    )
                }
            }
        },
        confirmButton = {
    GlassButton(onClick = {
        bootstrapDownloadJob?.cancel()
        bootstrapDownloadJob = null
        bootstrapDownloadInProgress = false
    }) {
        Text(stringResource(R.string.appscreen_cancel), color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
    }
}
        
    )
}
*/

if (fullDesktopDownloadInProgress) {
    AlertDialog(
        onDismissRequest = { /* cannot dismiss while downloading */ },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = { Text(stringResource(R.string.appscreen_downloading_xfce4_desktop_gui_800mb), fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (fullDesktopDownloadProgress.first >= 0) {
                    LinearProgressIndicator(
                        progress = { fullDesktopDownloadProgress.first / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFC3B6F9),
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        fullDesktopDownloadProgress.second,
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        fullDesktopDownloadProgress.second,
                        color = Color(0xFFFF6B6B)
                    )
                }
            }
        },
        confirmButton = {
    GlassButton(onClick = {
        fullDesktopDownloadJob?.cancel()
        fullDesktopDownloadJob = null
        fullDesktopDownloadInProgress = false
    }) {
        Text(stringResource(R.string.appscreen_cancel), color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
    }
}
    )
}

// ── Restart dialog after installation ───────────────────────────
if (installDone) {
    // Full‑screen surface to avoid the default window background
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF07040E)
    ) {
        // The dialog itself – no way to close it except the Restart button
        
        
    }
    return
}

if (showPanvkDriverDialog) {
    val isDownloading = panvkDownloadInProgress

    AlertDialog(
        onDismissRequest = {
            if (!isDownloading) showPanvkDriverDialog = false
        },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = { Text(stringResource(R.string.appscreen_panvk_drivers_needed), fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column {
                if (!isDownloading) {
                    Text(stringResource(R.string.appscreen_panvk_vulkan_driver_not_found_for_containers), color = Color.White.copy(alpha = 0.85f))
                    Spacer(Modifier.height(8.dp))
                    panvkMissingContainers.forEach { id ->
                        val distro = DisplayOrchestrator.getContainerDistroType(context, id) ?: "unknown"
                        Text(stringResource(R.string.appscreen_bullet_container, id, distro), color = Color.White.copy(alpha = 0.85f))
                    }
                    Text(stringResource(R.string.appscreen_do_you_want_to_download_and_install_them_now), color = Color.White.copy(alpha = 0.85f))
                } else {
                    // Download progress UI
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp), color = Color(0xFFC3B6F9))
                        Spacer(Modifier.height(16.dp))
                        Text(panvkDownloadProgress.second, color = Color.White.copy(alpha = 0.85f))
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { panvkDownloadProgress.first / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFC3B6F9),
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                        Text(stringResource(R.string.appscreen_percent, panvkDownloadProgress.first), color = Color.White.copy(alpha = 0.65f))
                    }
                }
            }
        },
        confirmButton = {
            if (isDownloading) {
                GlassButton(onClick = {}) { Text(stringResource(R.string.appscreen_downloading), color = Color.White.copy(alpha = 0.5f)) }
            } else {
                GlassButton(onClick = {
                    panvkDownloadInProgress = true
                    scope.launch {
                        downloadAndExtractPanvkDrivers(panvkMissingContainers)
                        panvkDownloadInProgress = false
                        showPanvkDriverDialog = false
                    }
                }) {
                    FitText(
        text = stringResource(R.string.appscreen_download_install),
        color = Color(0xFFC3B6F9),
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.bodyMedium,
        maxFontSize = 14.sp,
        minFontSize = 9.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
                }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                GlassButton(onClick = { showPanvkDriverDialog = false }) {
                    Text(stringResource(R.string.appscreen_cancel), color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}

if (showTurnipDriverDialog) {
    val isDownloading = turnipDownloadInProgress

    AlertDialog(
        onDismissRequest = {
            // Prevent dismiss while download is in progress
            if (!isDownloading) showTurnipDriverDialog = false
        },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = { Text(stringResource(R.string.appscreen_turnip_drivers_needed), fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column {
                if (!isDownloading) {
                    // Pre‑download summary
                    Text(stringResource(R.string.appscreen_turnip_vulkan_driver_not_found_for_containers), color = Color.White.copy(alpha = 0.85f))
                    Spacer(Modifier.height(8.dp))
                    turnipMissingContainers.forEach { id ->
                        val distro = DisplayOrchestrator.getContainerDistroType(context, id) ?: "unknown"
                        Text(stringResource(R.string.appscreen_bullet_container, id, distro), color = Color.White.copy(alpha = 0.85f))
                    }
                    Text(stringResource(R.string.appscreen_do_you_want_to_download_and_install_them_now), color = Color.White.copy(alpha = 0.85f))
                } else {
                    // Download progress
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp), color = Color(0xFFC3B6F9))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            turnipDownloadProgress.second,
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { turnipDownloadProgress.first / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFC3B6F9),
                            trackColor = Color.White.copy(alpha = 0.1f)
                        )
                        Text(
                            stringResource(R.string.appscreen_percent, turnipDownloadProgress.first),
                            color = Color.White.copy(alpha = 0.65f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (isDownloading) {
                // Show "Cancel download" button
                GlassButton(onClick = {
                    // You can implement cancellation logic here if needed
                    // For now, just allow closing only after completion
                }) {
                    Text(stringResource(R.string.appscreen_downloading), color = Color.White.copy(alpha = 0.5f))
                }
            } else {
                GlassButton(onClick = {
                    // Start download, do not close dialog
                    turnipDownloadInProgress = true
                    scope.launch {
                        downloadAndExtractTurnipDrivers(turnipMissingContainers)
                        // After completion, close dialog
                        turnipDownloadInProgress = false
                        showTurnipDriverDialog = false
                    }
                }) {
                    Text(stringResource(R.string.appscreen_download_install), color = Color(0xFFC3B6F9), fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                GlassButton(onClick = {
                    showTurnipDriverDialog = false
                }) {
                    Text(stringResource(R.string.appscreen_cancel), color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                }
            } else {
                // Optionally add a "Cancel download" button here
            }
        }
    )
}

    // ── Slot picker dialog ───────────────────────────────────────
  if (showSlotPicker) {
    val distro = pendingDistro
    val localUri = pendingLocalUri
    AlertDialog(
        onDismissRequest = {
            showSlotPicker = false
            pendingDistro = null
            pendingLocalUri = null
            confirmOverwriteContinuation?.cancel()
            confirmOverwriteContinuation = null
        },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = { Text(stringResource(R.string.appscreen_select_installation_slot), fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Column {
                val target = distro?.distroName ?: "archive"
                Text(stringResource(R.string.appscreen_install_target_to_which_container, target), color = Color.White.copy(alpha = 0.85f))
                Spacer(modifier = Modifier.height(16.dp))
                for (id in 1..3) {
                    val occupied = when (id) {
                        1 -> hasContainer1
                        2 -> hasContainer2
                        3 -> hasContainer3
                        else -> false
                    }
                    val label = stringResource(R.string.appscreen_container_label, id, stringResource(if (occupied) R.string.appscreen_container_occupied else R.string.appscreen_container_empty))
                    Button(
                        onClick = {
                            showSlotPicker = false

                                // Empty slot – install directly
                                if (distro != null) installIntoSlot(distro, id)
                                else if (localUri != null) extractLocalIntoSlot(localUri, id)
                                pendingDistro = null
                                pendingLocalUri = null
                            
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        colors = if (occupied) ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF6B6B).copy(alpha = 0.15f),
                            contentColor = Color(0xFFFF8B8B)
                        ) else ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC3B6F9).copy(alpha = 0.15f),
                            contentColor = Color(0xFFE2DCF9)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = if (occupied) listOf(
                                    Color(0xFFFF6B6B).copy(alpha = 0.3f),
                                    Color(0xFFFF6B6B).copy(alpha = 0.05f)
                                ) else listOf(
                                    Color(0xFFC3B6F9).copy(alpha = 0.3f),
                                    Color(0xFFC3B6F9).copy(alpha = 0.05f)
                                )
                            )
                        )
                    ) {
                        FitText(
        text = label,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.bodyMedium,
        maxFontSize = 14.sp,
        minFontSize = 10.sp,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
                    }
                }
            }
        },
        confirmButton = {
            GlassButton(onClick = {
                showSlotPicker = false
                pendingDistro = null
                pendingLocalUri = null
            }) { Text(stringResource(R.string.appscreen_cancel), color = Color.White.copy(alpha = 0.8f)) }
        }
    )
}

if (pendingOverwriteSlot != null) {
    val slot = pendingOverwriteSlot!!
    AlertDialog(
        onDismissRequest = {
            // User cancelled – resume with false
            pendingOverwriteSlot = null
            confirmOverwriteContinuation?.resumeWith(Result.success(false))
            confirmOverwriteContinuation = null
        },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = { Text(stringResource(R.string.appscreen_overwrite_container_title, slot), fontWeight = FontWeight.Bold, color = Color.White) },
        text = { Text(stringResource(R.string.appscreen_this_container_already_has_a_rootfs_installed_al), color = Color.White.copy(alpha = 0.85f)) },
        confirmButton = {
            GlassButton(onClick = {
                pendingOverwriteSlot = null
                confirmOverwriteContinuation?.resumeWith(Result.success(true))
                confirmOverwriteContinuation = null
            }) { Text(stringResource(R.string.appscreen_overwrite), color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            GlassButton(onClick = {
                pendingOverwriteSlot = null
                confirmOverwriteContinuation?.resumeWith(Result.success(false))
                confirmOverwriteContinuation = null
            }) { Text(stringResource(R.string.appscreen_cancel), color = Color.White.copy(alpha = 0.8f)) }
        }
    )
}

if (showContainerManager) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF07040E),
                            Color(0xFF140D2F)
                        )
                    )
                )
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Header with title and close button ──
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.appscreen_container_manager),
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                style = MaterialTheme.typography.headlineMedium.copy(fontSize = 32.sp)
                            )
                            Text(
                                text = stringResource(R.string.appscreen_manage_installed_environments),
                                color = Color(0xFF9F92EC),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        IconButton(onClick = { showContainerManager = false }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.appscreen_close),
                                tint = Color.White
                            )
                        }
                    }
                }

                // ── Container rows ──
                for (id in 1..3) {
                    item {
                        val occupied = when (id) {
                            1 -> hasContainer1
                            2 -> hasContainer2
                            3 -> hasContainer3
                            else -> false
                        }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(glassBlurModifier()),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                            border = BorderStroke(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.22f),
                                        Color.White.copy(alpha = 0.03f)
                                    )
                                )
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.appscreen_container_label, id, stringResource(if (occupied) R.string.appscreen_container_installed else R.string.appscreen_container_empty)),
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = {
                                    showContainerManager = false
                                    handleContainerInstallClick(id)
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.AddCircle,
                                        contentDescription = stringResource(R.string.appscreen_install_to_container_desc, id),
                                        tint = Color(0xFFC3B6F9)
                                    )
                                }
                                if (occupied) {
                                    IconButton(onClick = { 
                                    showContainerManager = false
                                    showDeleteConfirmation = id }) {
                                        Icon(Icons.Default.Delete, stringResource(R.string.appscreen_delete), tint = Color(0xFFFF6B6B))
                                    }
                                    IconButton(onClick = {
                                        pendingContainerForBackup = id
                                        backupFilePicker.launch("container${id}_backup.tar.xz")
                                    }) {
                                        Icon(Icons.Default.Save, stringResource(R.string.appscreen_backup), tint = Color(0xFFE9D5FF))
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Divider ──
                item {
                    HorizontalDivider(color = Color(0xFF2A1E4A), thickness = 1.dp)
                }

                // ── Action buttons ──
                item {
                    Button(
                        onClick = {
                            showContainerManager = false
                            pickBootstrapFile.launch(arrayOf("application/x-xz", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.07f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.03f))
                            )
                        )
                    ) {
                        Text(stringResource(R.string.appscreen_install_native_packages_wine), fontWeight = FontWeight.Bold)
                    }
                }

                item {
                    Button(
                        onClick = {
                            showContainerManager = false
                            showWineDownloadDialog = true
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.07f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.03f))
                            )
                        )
                    ) {
                        Text(stringResource(R.string.appscreen_download_wine_250mb), fontWeight = FontWeight.Bold)
                    }
                }

               /* item {
                    Button(
                        onClick = {
                            showContainerManager = false
                            downloadFullDesktopGUIArchive()
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.07f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.03f))
                            )
                        )
                    ) {
                        Text(stringResource(R.string.appscreen_download_xfce4_desktop_gui), fontWeight = FontWeight.Bold)
                    }
                }
*/
                item {
                    Button(
                        onClick = { 
                        showContainerManager = false
                        showCleanCacheConfirmation = true },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.07f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.03f))
                            )
                        )
                    ) {
                        Text(stringResource(R.string.appscreen_clean_cache_and_downloaded_packages), fontWeight = FontWeight.Bold)
                    }
                }

                item {
                    Button(
                        onClick = { 
                        showContainerManager = false
                        showRemoveNativeEnvConfirmation = true },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF6B6B).copy(alpha = 0.15f),
                            contentColor = Color(0xFFFF8B8B)
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFFFF6B6B).copy(alpha = 0.3f), Color(0xFFFF6B6B).copy(alpha = 0.05f))
                            )
                        )
                    ) {
                        Text(stringResource(R.string.appscreen_remove_native_environment), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    
    return 
}

// ── Delete container confirmation dialog ──────────────────────────
if (showDeleteConfirmation != null) {
    val containerId = showDeleteConfirmation!!
    AlertDialog(
        onDismissRequest = { showDeleteConfirmation = null },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = { Text(stringResource(R.string.appscreen_delete_container_title, containerId), fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Text(stringResource(R.string.appscreen_delete_container_warning, containerId), color = Color.White.copy(alpha = 0.85f))
        },
        confirmButton = {
            GlassButton(onClick = {
                showDeleteConfirmation = null   // close the dialog
                deleteInProgress = true         // show loading overlay
                scope.launch {
                    try {
                        if (NativeInstallCoordinator.deleteContainerContents(context, containerId)) {
                            refreshContainerState()
                            refreshLegacyFlags()
                        }
                    } finally {
                        deleteInProgress = false   // hide overlay when done
                    }
                }
            }) {
                Text(stringResource(R.string.appscreen_delete), color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            GlassButton(onClick = { showDeleteConfirmation = null }) {
                Text(stringResource(R.string.appscreen_cancel), color = Color.White.copy(alpha = 0.8f))
            }
        }
    )
}


// ── Native‑container prompt dialog ──────────────────────────
if (showNativeContainerPrompt != null) {
    val containerId = showNativeContainerPrompt!!
    AlertDialog(
        onDismissRequest = {
            showNativeContainerPrompt = null
        },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = { Text(stringResource(R.string.appscreen_use_as_native_terminal), fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Text(stringResource(R.string.appscreen_use_native_body, containerId), color = Color.White.copy(alpha = 0.85f))
        },
        confirmButton = {
    GlassButton(onClick = {
        markContainerAsNativeTerminal(containerId)
        showNativeContainerPrompt = null
        Toast.makeText(context, context.getString(R.string.appscreen_native_terminal_set_toast, containerId), Toast.LENGTH_SHORT).show()
    }) {
        FitText(
            text = stringResource(R.string.appscreen_yes_use_as_native),
            color = Color(0xFFC3B6F9),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            maxFontSize = 14.sp,
            minFontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
},
dismissButton = {
    GlassButton(onClick = {
        showNativeContainerPrompt = null
        proceedToDistroSelection(containerId)
    }) {
        FitText(
            text = stringResource(R.string.appscreen_no_install_a_distro),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.bodyMedium,
            maxFontSize = 14.sp,
            minFontSize = 9.sp,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
        
        
    )
}
// ── Clean cache confirmation dialog ───────────────────────────────
if (showCleanCacheConfirmation) {
    AlertDialog(
        onDismissRequest = { showCleanCacheConfirmation = false },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = { Text(stringResource(R.string.appscreen_clean_downloaded_archives), fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Text(stringResource(R.string.appscreen_all_distribution_tarballs_tar_xz_stored_in_the_a), color = Color.White.copy(alpha = 0.85f))
        },
        confirmButton = {
            GlassButton(onClick = {
                scope.launch {
                    NativeInstallCoordinator.cleanCacheTarballs(context)
                }
                showCleanCacheConfirmation = false
            }) {
                Text(stringResource(R.string.appscreen_clean), color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            GlassButton(onClick = { showCleanCacheConfirmation = false }) {
                Text(stringResource(R.string.appscreen_cancel), color = Color.White.copy(alpha = 0.8f))
            }
        }
    )
}

if (showRemoveNativeEnvConfirmation) {
    AlertDialog(
        onDismissRequest = { showRemoveNativeEnvConfirmation = false },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = { Text(stringResource(R.string.appscreen_remove_native_environment_2), fontWeight = FontWeight.Bold, color = Color.White) },
        text = {
            Text(
                stringResource(R.string.appscreen_this_will_delete_the_installed_native_rootfs_pre),
                color = Color.White.copy(alpha = 0.85f)
            )
        },
        confirmButton = {
            GlassButton(onClick = {
                showRemoveNativeEnvConfirmation = false
                removeNativeEnvironment()
            }) {
                Text(stringResource(R.string.appscreen_remove), color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            GlassButton(onClick = { showRemoveNativeEnvConfirmation = false }) {
                Text(stringResource(R.string.appscreen_cancel), color = Color.White.copy(alpha = 0.8f))
            }
        }
    )
}
//───────────────────────────────
// ── Exit dialog ──────────────────────────────────────────────────
if (showExitDialog) {
    AlertDialog(
        onDismissRequest = { showExitDialog = false },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = { Text(stringResource(R.string.appscreen_exit), fontWeight = FontWeight.Bold, color = Color.White) },
        text = { Text(stringResource(R.string.appscreen_are_you_sure_you_want_to_exit), color = Color.White.copy(alpha = 0.85f)) },
        confirmButton = {
            GlassButton(onClick = {
                showExitDialog = false
                // 1. Stop the X11 server service
                val x11Intent = Intent(context, X11ServerService::class.java)
                context.stopService(x11Intent)

                // 2. the entire app process, just like Android’s Force Stop
                //    finishAffinity() ensures all activities are removed from the stack
                (context as? Activity)?.finishAffinity()
                System.exit(0)
            }) { Text(stringResource(R.string.appscreen_yes), color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            GlassButton(onClick = { showExitDialog = false }) { Text(stringResource(R.string.appscreen_no), color = Color.White.copy(alpha = 0.8f)) }
        }
    )
}
    // ── Distro selection screen ──────────────────────────────────
// ── Distro selection screen ──────────────────────────────────
if (showDistroSelection) {
    // Track which source is currently active. Default to CUSTOM
    var selectedSource by remember { mutableStateOf(NativeInstallCoordinator.DistroSource.CUSTOM) }
    var customUrlText by remember { mutableStateOf("") }
    var customUrlError by remember { mutableStateOf("") }
    var isFetchingCustomUrl by remember { mutableStateOf(false) }
    var showCustomUrlDialog by remember { mutableStateOf(false) }
    var fetchedDistro by remember { mutableStateOf<DistroDescriptor?>(null) }

    // Reusable function to trigger the fetch based on the selected source
    val loadDistros = { source: NativeInstallCoordinator.DistroSource ->
        scope.launch {
            distroFetchState = DistroFetchState.LOADING
            availableDistros = withContext(Dispatchers.IO) {
                NativeInstallCoordinator.fetchAvailableDistros(source)
            }
            distroFetchState = if (availableDistros.isEmpty()) DistroFetchState.ERROR else DistroFetchState.LOADED
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF07040E),
                            Color(0xFF140D2F)
                        )
                    )
                )
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Header ──
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = stringResource(R.string.appscreen_xodos_ark),
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium.copy(
                                fontSize = 38.sp,
                                letterSpacing = (-1.5).sp
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.appscreen_select_environment),
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFC3B6F9),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 18.sp,
                                letterSpacing = 1.5.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.appscreen_choose_your_workspace_template_to_install_into_y),
                            color = Color(0xFF9F92EC),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                // ── Segmented Control ──
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(glassBlurModifier())
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.08f),
                                        Color.White.copy(alpha = 0.02f)
                                    )
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.25f),
                                        Color.White.copy(alpha = 0.04f)
                                    )
                                ),
                                shape = RoundedCornerShape(16.dp)
                            )
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val tabs = listOf(
                            stringResource(R.string.appscreen_tab_latest) to NativeInstallCoordinator.DistroSource.EASYCLI,
                            stringResource(R.string.appscreen_tab_kali) to NativeInstallCoordinator.DistroSource.KALI,
                            stringResource(R.string.appscreen_tab_custom) to NativeInstallCoordinator.DistroSource.CUSTOM
                        )
                        tabs.forEach { (label, source) ->
                            val isActive = selectedSource == source
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isActive) Color(0xCE7C3AED) else Color.Transparent)
                                    .clickable {
                                        if (!isActive) {
                                            selectedSource = source
                                            loadDistros(source)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                FitText(
    text = label,
    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
    color = if (isActive) Color.White else Color(0xFF9F92EC),
    style = MaterialTheme.typography.bodyMedium,
    maxFontSize = 14.sp,
    minFontSize = 9.sp,
)
                            }
                        }
                    }
                }

                // ── Direct URL Install (only on Custom tab) ──
                if (selectedSource == NativeInstallCoordinator.DistroSource.CUSTOM) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(glassBlurModifier()),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                            border = BorderStroke(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.22f),
                                        Color.White.copy(alpha = 0.03f)
                                    )
                                )
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.appscreen_direct_url_install),
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = customUrlText,
                                    onValueChange = {
                                        customUrlText = it
                                        customUrlError = ""
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = {
                                        Text("https://.../distro.tar.xz", color = Color(0xFF9F92EC))
                                    },
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = Color(0xFFC3B6F9),
                                        unfocusedBorderColor = Color(0xFF9F92EC),
                                        cursorColor = Color(0xFFC3B6F9),
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    )
                                )
                                if (customUrlError.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = customUrlError,
                                        color = Color(0xFFFF6B6B),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                GlassButton(
                                    onClick = {
                                        if (!isFetchingCustomUrl) {
                                            val trimmedUrl = customUrlText.trim()
                                            val pattern = Regex(
                                                "^https?://.*\\.(tar\\.xz|tar\\.gz)$",
                                                RegexOption.IGNORE_CASE
                                            )
                                            if (!pattern.matches(trimmedUrl)) {
                                                customUrlError = context.getString(R.string.appscreen_invalid_url)
                                            } else {
                                                customUrlError = ""
                                                scope.launch {
                                                    isFetchingCustomUrl = true
                                                    try {
                                                        val descriptor = withContext(Dispatchers.IO) {
                                                            NativeInstallCoordinator.fetchDistroInfoFromUrl(trimmedUrl)
                                                        }
                                                        fetchedDistro = descriptor
                                                        showCustomUrlDialog = true
                                                    } catch (e: Exception) {
                                                        customUrlError = context.getString(R.string.appscreen_failed_to_fetch_url, e.message ?: "")
                                                    } finally {
                                                        isFetchingCustomUrl = false
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isFetchingCustomUrl) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            color = Color.White,
                                            strokeWidth = 2.dp
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(R.string.appscreen_get_it),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Local file picker ──
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(glassBlurModifier())
                            .clickable {
                                pickFile.launch(arrayOf("application/x-xz", "*/*"))
                            },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.05f)
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.22f),
                                    Color.White.copy(alpha = 0.03f)
                                )
                            )
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color(0xFF2A1E4A), shape = RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = stringResource(R.string.appscreen_select_local_file),
                                    modifier = Modifier.size(24.dp),
                                    tint = Color(0xFF7C3AED)
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.appscreen_select_local_distro),
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.appscreen_install_from_a_local_tar_xz_archive),
                                    color = Color(0xFF9F92EC),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                // ── Divider ──
                item {
                    HorizontalDivider(color = Color(0xFF2A1E4A), thickness = 1.dp)
                }

                // ── Content depending on fetch state ──
                when {
                    distroFetchState == DistroFetchState.LOADING -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Color(0xFF7C3AED))
                            }
                        }
                    }

                    distroFetchState == DistroFetchState.ERROR && availableDistros.isEmpty() -> {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = Color(0xFFEF4444)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stringResource(R.string.appscreen_connection_offline),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.appscreen_check_your_internet_connection_and_try_again),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFF9F92EC),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                GlassButton(onClick = {
                                    NativeInstallCoordinator.invalidateDistroCache()
                                    loadDistros(selectedSource)
                                }) {
                                    Text(stringResource(R.string.appscreen_retry_connection), color = Color(0xFFC3B6F9), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    else -> {
                        items(availableDistros) { distro ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(glassBlurModifier())
                                    .clickable {
                                        val targetContainer = pendingContainerForInstall
                                        if (targetContainer != null) {
                                            pendingContainerForInstall = null
                                            pendingDistro = distro
                                            val occupied = when (targetContainer) {
                                                1 -> hasContainer1
                                                2 -> hasContainer2
                                                3 -> hasContainer3
                                                else -> false
                                            }
                                            if (occupied) {
                                                pendingOverwriteSlot = targetContainer
                                            } else {
                                                installIntoSlot(distro, targetContainer)
                                            }
                                        } else {
                                            pendingDistro = distro
                                            showSlotPicker = true
                                        }
                                    },
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.White.copy(alpha = 0.05f)
                                ),
                                border = BorderStroke(
                                    width = 1.dp,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            Color.White.copy(alpha = 0.22f),
                                            Color.White.copy(alpha = 0.03f)
                                        )
                                    )
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // icon
                                    val resourceName = distro.distroType.lowercase(Locale.ROOT).trim()
                                    val iconResId = context.resources.getIdentifier(
                                        resourceName, "drawable", context.packageName
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(Color(0xFF2A1E4A), shape = RoundedCornerShape(12.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (iconResId != 0) {
                                            Image(
                                                painter = painterResource(iconResId),
                                                contentDescription = distro.distroType,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        } else {
                                            val fallbackResId = context.resources.getIdentifier(
                                                "linux", "drawable", context.packageName
                                            )
                                            if (fallbackResId != 0) {
                                                Image(
                                                    painter = painterResource(fallbackResId),
                                                    contentDescription = stringResource(R.string.appscreen_linux_fallback),
                                                    modifier = Modifier.size(32.dp)
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = Icons.Default.Folder,
                                                    contentDescription = stringResource(R.string.appscreen_generic_distro),
                                                    modifier = Modifier.size(28.dp),
                                                    tint = Color(0xFF9F92EC)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = distro.distroName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() },
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 19.sp
                                            )
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = distro.archiveName,
                                            color = Color(0xFF9F92EC),
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFF2A1E4A), shape = RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "v${distro.version}",
                                                    color = Color(0xFFC3B6F9),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 10.sp
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .background(Color(0xFF2A1E4A), shape = RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = distro.size,
                                                    color = Color(0xFFC3B6F9),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 10.sp
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.AddCircle,
                                        contentDescription = stringResource(R.string.appscreen_install_distro),
                                        tint = Color(0xFF7C3AED),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Continue button ──
                item {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .then(glassBlurModifier()),
                        onClick = {
                            showDistroSelection = false
                            pendingContainerForInstall = null
                            prefs.edit().putBoolean("setup_done", true).apply()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.09f),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.25f),
                                    Color.White.copy(alpha = 0.04f)
                                )
                            )
                        )
                    ) {
                        FitText(
                            text = stringResource(R.string.appscreen_continue_without_installing),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium,
                            maxFontSize = 14.sp,
        minFontSize = 10.sp,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
                        
                    }
                }
            }
        }
    }

    // ─── Custom URL metadata dialog ─────────────────────
    if (showCustomUrlDialog && fetchedDistro != null) {
        val descriptor = fetchedDistro!!
        AlertDialog(
            onDismissRequest = { showCustomUrlDialog = false },
            containerColor = Color.Transparent,
            modifier = Modifier.glassDialogStyle(),
            title = { Text(stringResource(R.string.appscreen_install_from_url), fontWeight = FontWeight.Bold, color = Color.White) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.appscreen_you_are_about_to_install),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = descriptor.distroName,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.appscreen_version_prefix, descriptor.version),
                        color = Color(0xFFC3B6F9),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(R.string.appscreen_size_prefix, descriptor.size),
                        color = Color(0xFFC3B6F9),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(R.string.appscreen_archive_prefix, descriptor.archiveName),
                        color = Color(0xFF9F92EC),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                GlassButton(onClick = {
                    showCustomUrlDialog = false
                    val targetContainer = pendingContainerForInstall
                    if (targetContainer != null) {
                        pendingContainerForInstall = null
                        pendingDistro = descriptor
                        val occupied = when (targetContainer) {
                            1 -> hasContainer1
                            2 -> hasContainer2
                            3 -> hasContainer3
                            else -> false
                        }
                        if (occupied) pendingOverwriteSlot = targetContainer
                        else installIntoSlot(descriptor, targetContainer)
                    } else {
                        pendingDistro = descriptor
                        showSlotPicker = true
                    }
                }) {
                    Text(stringResource(R.string.appscreen_install), color = Color(0xFFC3B6F9), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                GlassButton(onClick = { showCustomUrlDialog = false }) {
                    Text(stringResource(R.string.appscreen_cancel), color = Color.White.copy(alpha = 0.8f))
                }
            }
        )
    }

    return
}
    // ── Original main UI (drawer + terminal/desktop) ────────────
    val isAnyDialogVisible = showContainerManager || 
                             showDeleteConfirmation != null || 
                             showCleanCacheConfirmation || 
                             showRemoveNativeEnvConfirmation ||
                             showNativeContainerPrompt != null || 
                             showSlotPicker || 
                             showTurnipDriverDialog || 
                             showExitDialog ||
                             showWineDownloadDialog ||
                             wineDownloadInProgress

    AppDrawer(
        drawerState = drawerState,
        isBackgroundBlurred = isAnyDialogVisible,
        drawerContent = {
      

            DrawerPagedHost(
    archContent = {
        ArchDrawerPage(
            prefs = prefs,
            drawerState = drawerState,
            scope = scope,
            terminalFontKey = terminalFontKey,
            terminalSessionState = terminalSessionState,
            launcherDefault = launcherDefault,
            desktopVulkanMode = desktopVulkanMode,
            desktopOpenGLMode = desktopOpenGLMode,
            mouseMode = mouseMode,
            resolutionPercent = resolutionPercent,
            scalePercent = scalePercent,
            waylandScriptEditorOpen = waylandScriptEditorOpen,
            archX11ScriptEditorOpen = archX11ScriptEditorOpen,
            onRequestKeyboard = { requestKeyboard() },
            onArchX11ScriptEditorOpenChange = { archX11ScriptEditorOpen = it },
            onEnterArchX11Desktop = { enterArchX11Desktop() },
            onWaylandScriptEditorOpenChange = { waylandScriptEditorOpen = it },
            onEnterWaylandDesktop = { enterArchWaylandDesktop() },
            onEnterTerminal = { enterTerminal() },
            onLauncherDefaultSelect = { setLauncherDefaultFromMenuLabel(it) },
            onDesktopVulkanSelect = { setDesktopVulkanMode(it) },
            onDesktopOpenGLSelect = { setDesktopOpenGLMode(it) },
            onTerminalFontSelectLabel = { label ->
                val id = ShellFonts.options.find { it.label == label }?.id ?: ShellFonts.DEFAULT_ID
                persistTerminalFont(id)
            },
            onTerminalSessionStateChange = { terminalSessionState = it },
            onExitRequested = { showExitDialog = true },
            onRunWineDesktop = { runWineDesktopFromEnv() },
            onRestartRequested = { requestAppRestart() },
            onExecuteCommand = { cmd ->
                NativeBridge.writeInput(terminalSessionState.activeSessionId, "$cmd\n".toByteArray())
            },
            onMouseModeSelectLabel = { label ->
                persistMouseMode(if (label == "Tablet") MOUSE_MODE_TABLET else MOUSE_MODE_TOUCHPAD)
            },
            onResolutionPercentSelectLabel = { label ->
                val pct = label.removeSuffix("%").trim().toIntOrNull()
                if (pct != null) persistResolutionPercent(pct)
            },
            onScalePercentSelectLabel = { label ->
                val pct = label.removeSuffix("%").trim().toIntOrNull()
                if (pct != null) persistScalePercent(pct)
            },
            vulkanOptions = VULKAN_MODES,
            openGLOptions = when (desktopVulkanMode) {
                "TURNIP" -> listOf("LLVMPIPE", "ZINK", "VIRGL", "GL4ES")
                "VENUS"  -> listOf("LLVMPIPE", "ZINK", "VIRGL", "GL4ES")
                else     -> OPENGL_MODES
            },
            hasArchRootfs = hasContainer1,
            onContainerManagerClick = {
                scope.launch { drawerState.close() }
                showContainerManager = true
            }
        )
    },

    // ─── Debian Drawer (Container 2) ──────────────────────────
    debianContent = {
        DebianDrawerPage(
            // ── mandatory first three parameters ──
            debianX11ScriptEditorOpen = debianX11ScriptEditorOpen,
            onDebianX11ScriptEditorOpenChange = { debianX11ScriptEditorOpen = it },
            onEnterDebianX11Desktop = { enterDebianDesktop() },   // existing function

            prefs = prefs,
            drawerState = drawerState,
            scope = scope,
            terminalFontKey = terminalFontKey,
            terminalSessionState = terminalSessionState,
            launcherDefault = launcherDefault,
            desktopVulkanMode = desktopVulkanMode,
            desktopOpenGLMode = desktopOpenGLMode,
            mouseMode = mouseMode,
            resolutionPercent = resolutionPercent,
            scalePercent = scalePercent,
            waylandScriptEditorOpen = debianWaylandScriptEditorOpen,     
            onWaylandScriptEditorOpenChange = { debianWaylandScriptEditorOpen = it },
            onEnterDebianWaylandDesktop = { enterDebianWaylandDesktop() },
            onEnterTerminal = { enterTerminal() },
            onLauncherDefaultSelect = { setLauncherDefaultFromMenuLabel(it) },
            onDesktopVulkanSelect = { setDesktopVulkanMode(it) },
            onDesktopOpenGLSelect = { setDesktopOpenGLMode(it) },
            onTerminalFontSelectLabel = { label ->
                val id = ShellFonts.options.find { it.label == label }?.id ?: ShellFonts.DEFAULT_ID
                persistTerminalFont(id)
            },
            onTerminalSessionStateChange = { terminalSessionState = it },
            onMouseModeSelectLabel = { label ->
                persistMouseMode(if (label == "Tablet") MOUSE_MODE_TABLET else MOUSE_MODE_TOUCHPAD)
            },
            onResolutionPercentSelectLabel = { label ->
                val pct = label.removeSuffix("%").trim().toIntOrNull()
                if (pct != null) persistResolutionPercent(pct)
            },
            onScalePercentSelectLabel = { label ->
                val pct = label.removeSuffix("%").trim().toIntOrNull()
                if (pct != null) persistScalePercent(pct)
            },
            vulkanOptions = VULKAN_MODES,
            openGLOptions = when (desktopVulkanMode) {
                "TURNIP" -> listOf("LLVMPIPE", "ZINK", "VIRGL", "GL4ES")
                "VENUS"  -> listOf("LLVMPIPE", "ZINK", "VIRGL", "GL4ES")
                else     -> OPENGL_MODES
            },
            hasDebianRootfs = hasContainer2,
            onContainerManagerClick = {
                scope.launch { drawerState.close() }
                showContainerManager = true
            },
            onRequestKeyboard = { requestKeyboard() },
            onExitRequested = { showExitDialog = true },
            onRunWineDesktop = { runWineDesktopFromEnv() },
            onRestartRequested = { requestAppRestart() },
            onExecuteCommand = { cmd ->
                NativeBridge.writeInput(terminalSessionState.activeSessionId, "$cmd\n".toByteArray())
            }
        )
    },

    // ─── Wine Drawer (Container 3) ────────────────────────────
    androidContent = {
        WineDrawerPage(
            // ── mandatory first three parameters ──
            wineX11ScriptEditorOpen = wineX11ScriptEditorOpen,
            onWineX11ScriptEditorOpenChange = { wineX11ScriptEditorOpen = it },
            onEnterWineX11Desktop = { enterWineDesktop() },   // existing function

            prefs = prefs,
            drawerState = drawerState,
            scope = scope,
            terminalFontKey = terminalFontKey,
            terminalSessionState = terminalSessionState,
            launcherDefault = launcherDefault,
            desktopVulkanMode = desktopVulkanMode,
            desktopOpenGLMode = desktopOpenGLMode,
            mouseMode = mouseMode,
            resolutionPercent = resolutionPercent,
            scalePercent = scalePercent,
            waylandScriptEditorOpen = wineWaylandScriptEditorOpen,   
            onWaylandScriptEditorOpenChange = { wineWaylandScriptEditorOpen = it },
            onEnterWineWaylandDesktop = { enterWineWaylandDesktop() },
            onEnterTerminal = { enterTerminal() },
            onLauncherDefaultSelect = { setLauncherDefaultFromMenuLabel(it) },
            onDesktopVulkanSelect = { setDesktopVulkanMode(it) },
            onDesktopOpenGLSelect = { setDesktopOpenGLMode(it) },
            onTerminalFontSelectLabel = { label ->
                val id = ShellFonts.options.find { it.label == label }?.id ?: ShellFonts.DEFAULT_ID
                persistTerminalFont(id)
            },
            onTerminalSessionStateChange = { terminalSessionState = it },
            onMouseModeSelectLabel = { label ->
                persistMouseMode(if (label == "Tablet") MOUSE_MODE_TABLET else MOUSE_MODE_TOUCHPAD)
            },
            onResolutionPercentSelectLabel = { label ->
                val pct = label.removeSuffix("%").trim().toIntOrNull()
                if (pct != null) persistResolutionPercent(pct)
            },
            onScalePercentSelectLabel = { label ->
                val pct = label.removeSuffix("%").trim().toIntOrNull()
                if (pct != null) persistScalePercent(pct)
            },
            vulkanOptions = VULKAN_MODES,
            openGLOptions = when (desktopVulkanMode) {
                "TURNIP" -> listOf("LLVMPIPE", "ZINK", "VIRGL", "GL4ES")
                "VENUS"  -> listOf("LLVMPIPE", "ZINK", "VIRGL", "GL4ES")
                else     -> OPENGL_MODES
            },
            hasWineRootfs = hasContainer3,
            onContainerManagerClick = {
                scope.launch { drawerState.close() }
                showContainerManager = true
            },
            onRequestKeyboard = { requestKeyboard() },
            onExitRequested = { showExitDialog = true },
            onRunWineDesktop = { runWineDesktopFromEnv() },
            onRestartRequested = { requestAppRestart() },
            onExecuteCommand = { cmd ->
                NativeBridge.writeInput(terminalSessionState.activeSessionId, "$cmd\n".toByteArray())
            }
        )
    }
)
            
            
        },
        
        
        
        
    ) {
 
    val containerMask = NativeBridge.getInstalledContainersMask()
val activeSessionHasRootfs = when (terminalSessionState.activeSessionId) {
    TerminalSessionIds.ARCH_TERMINAL   -> (containerMask and 1) != 0
    TerminalSessionIds.DEBIAN_TERMINAL -> (containerMask and 2) != 0
    TerminalSessionIds.WINE_TERMINAL   -> (containerMask and 4) != 0
    else -> false
}   
   val isTerminalFront = !showWayland && !showX11 && !desktopLaunchBlackout
   
val onShellBackPressed: () -> Unit = {
    when {
        InputRouteState.lorieX11DisplayVisible -> enterTerminal()
        InputRouteState.waylandVisible -> enterTerminal()
        desktopLaunchBlackout -> {
            desktopLaunchBlackout = false
            waylandVisible = false
            pendingAutoShowWayland = false
            uiMode = UiMode.TERMINAL
        }
        else -> showExitDialog = true
    }
}
        Box(modifier = Modifier.fillMaxSize()) {
            

ShellScreen(
    terminalFontKey = terminalFontKey,
    activeSessionId = terminalSessionState.activeSessionId,
    terminalSessionIds = terminalSessionState.sessionIds,
    rendererSessionResetEpoch = rendererSessionResetEpoch,
    showKeyboardTrigger = if (showWayland) 0 else showKeyboardTrigger,
    activeSessionHasRootfs = activeSessionHasRootfs,
    isTerminalFront = isTerminalFront,   // <-- correct parameter name
    onKeyboardTriggerConsumed = { showKeyboardTrigger = 0 },
    onCloseCurrentSession = {
        NativeBridge.closeSession(terminalSessionState.activeSessionId)
        terminalSessionState = TerminalSessionController.closeCurrentSession(terminalSessionState)
    },
    onBackPressed = onShellBackPressed,
    onExitRequested = { showExitDialog = true },
    modifier = Modifier.fillMaxSize().zIndex(0f)
)

            if (desktopLaunchBlackout) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(1f)
            .background(Color.Black.copy(alpha = 0.45f))  // semi‑transparent
    )
}
            
            if (showWayland) {
                WaylandSurfaceView(
                    runtimeDir = waylandRuntimeDir,
                    mouseMode = mouseMode,
                    resolutionPercent = resolutionPercent,
                    scalePercent = scalePercent,
                    skipEglWaylandBind = false,
                    showKeyboardTrigger = showKeyboardTrigger,
                    keyboardWanted = keyboardWanted,
                    onKeyboardTriggerConsumed = { showKeyboardTrigger = 0 },
                    modifier = Modifier.fillMaxSize().zIndex(1.5f),
                )
            }
            if (showX11) {
                EmbeddedX11Surface(
                    visible = true,
                    mouseMode = x11MouseMode,
                    modifier = Modifier.fillMaxSize().zIndex(1.6f),
                )
            }
        }
        FloatingMenuOrb(
            prefs = prefs,
            onClick = {
                scope.launch {
                    if (drawerState.isOpen) drawerState.close() else drawerState.open()
                }
            },
            modifier = Modifier.fillMaxSize().graphicsLayer { clip = false }
        )
    }
}

