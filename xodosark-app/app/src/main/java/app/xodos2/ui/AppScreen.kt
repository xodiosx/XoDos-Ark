package app.xodos2.ui

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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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

private val VULKAN_MODES = listOf("LLVMPIPE", "VENUS", "TURNIP")
private val OPENGL_MODES = listOf("LLVMPIPE", "VIRGL", "ZINK", "GL4ES")

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

private fun x11ResolutionModeInternalForLabel(label: String): String = when (label) {
    X11_MODE_LABEL_NATIVE -> "native"
    X11_MODE_LABEL_SCALED -> "scaled"
    X11_MODE_LABEL_EXACT -> "exact"
    X11_MODE_LABEL_CUSTOM -> "custom"
    else -> "native"
}

private val X11_CUSTOM_RESOLUTION_PATTERN: Pattern = Pattern.compile("^\\s*(\\d{2,4})\\s*x\\s*(\\d{2,4})\\s*\$")

private enum class UiMode { TERMINAL, ARCH_WAYLAND_DESKTOP, DEBIAN_X11_DESKTOP }
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
    var installProgress by remember { mutableStateOf(0 to "Preparing...") }
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

var pendingContainerForBackup by remember { mutableStateOf<Int?>(null) }
var backupInProgress by remember { mutableStateOf(false) }
var backupProgress by remember { mutableStateOf(0 to "") }  // (pct, msg)



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

    // ----- distro selection state -----
    var availableDistros by remember { mutableStateOf<List<DistroDescriptor>>(emptyList()) }
    var distroFetchState by remember { mutableStateOf(DistroFetchState.LOADING) }
    var showDistroSelection by remember { mutableStateOf(false) }
    var showSlotPicker by remember { mutableStateOf(false) }
    var pendingDistro by remember { mutableStateOf<DistroDescriptor?>(null) }
    var pendingLocalUri by remember { mutableStateOf<Uri?>(null) }
    val setupAlreadyCompleted = prefs.getBoolean("setup_done", false)

    // ----- mode & drawer state -----
    var menuOpen by remember { mutableStateOf(false) }
    var uiMode by remember(startInTerminal) { mutableStateOf(UiMode.TERMINAL) }
    var waylandVisible by remember { mutableStateOf(false) }
    val showWayland = waylandVisible && uiMode != UiMode.TERMINAL
    val showX11 = uiMode == UiMode.DEBIAN_X11_DESKTOP
    var settingsOpen by remember { mutableStateOf(false) }
    var waylandScriptEditorOpen by remember { mutableStateOf(false) }
    var x11ScriptEditorOpen by remember { mutableStateOf(false) }
 var archX11ScriptEditorOpen by remember { mutableStateOf(false) }   
var wineScriptEditorOpen by remember { mutableStateOf(false) }   // ADD THIS LINE
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
            Toast.makeText(context, "Selected file no longer exists. Please pick a valid archive.", Toast.LENGTH_LONG).show()
        }
    }
}

    // ----- mode enter functions (unchanged) -----
    fun enterTerminal() {
        try { WaylandBridge.nativeSetWmMode(WaylandBridge.WM_MODE_NESTED) } catch (_: Throwable) {}
        uiMode = UiMode.TERMINAL
        waylandVisible = false
        PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.LEGACY_ARCH_X11_PTY, false)
        PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.ARCH_WAYLAND_DISPLAY, false)
        PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.DEBIAN_X11_DISPLAY, false)
        desktopLaunchBlackout = false
        menuOpen = false
        AppLogger.log("Switched to terminal mode")
    }
    
    fun requestKeyboard() {
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
    
       try {
        val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to open X11 desktop", Toast.LENGTH_SHORT).show()
    }
    AppLogger.log("Entered Arch X11 desktop")
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
    // Start the X11 server (shared with Debian) if not already running
    X11Runtime.ensureX11ServerProcessStarted(context)

    // Run the Wine desktop script using the Wine headless session
    DisplayOrchestrator.runWineX11DesktopStartupScript(
        context = context,
        prefs = prefs,
        headlessInjectHandler = headlessX11InjectHandler,
        hasWineRootfs = hasContainer3,
    )

    // Switch UI to X11 desktop mode (same mode as Debian)
    menuOpen = false
    desktopLaunchBlackout = false
    waylandVisible = false
    pendingAutoShowWayland = false
    PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.WINE_X11_DISPLAY, false)

 //   uiMode = UiMode.DEBIAN_X11_DESKTOP   // reuse the X11 mode
uiMode = UiMode.TERMINAL
    
       try {
        val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to open X11 desktop", Toast.LENGTH_SHORT).show()
    }
    AppLogger.log("Entered Wine X11 desktop")
}

fun openX11Desktop() {
    // 1. Ensure the X server is up (this starts X11ServerService if not already running)
    X11Runtime.ensureX11ServerProcessStarted(context)

        menuOpen = false
        desktopLaunchBlackout = false
        PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.DEBIAN_X11_DISPLAY, false)
        waylandVisible = false
        pendingAutoShowWayland = false

    
        AppLogger.log("Entered  X11 desktop MainActivity")
    //  Launch the full-screen Lorie activity
 uiMode = UiMode.TERMINAL
    
       try {
        val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to open X11 desktop", Toast.LENGTH_SHORT).show()
    }

    // 3. (Optional) If you still want to run the startup script automatically,
    //    you can inject it here just like the old desktop enter functions.
    //    For example:
    //    DisplayOrchestrator.runDebianX11DesktopStartupScript(
    //        context = context,
    //        prefs = prefs,
    //        headlessInjectHandler = headlessX11InjectHandler,
    //        hasDebianRootfs = hasContainer2,
    //    )
}


fun openX11Settings() {
    try {
        val intent = Intent(context, com.termux.x11.LoriePreferences::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to open X11 settings", Toast.LENGTH_SHORT).show()
    }
}

    fun enterDebianDesktop() {
        X11Runtime.ensureX11ServerProcessStarted(context)
        DisplayOrchestrator.runDebianX11DesktopStartupScript(
            context = context,
            prefs = prefs,
            headlessInjectHandler = headlessX11InjectHandler,
            hasDebianRootfs = hasDebianRootfs,
        )
        menuOpen = false
        desktopLaunchBlackout = false
        PtyOutputRelay.setSessionIoLoggingEnabled(TerminalSessionIds.DEBIAN_X11_DISPLAY, false)
        waylandVisible = false
        pendingAutoShowWayland = false
     //   uiMode = UiMode.DEBIAN_X11_DESKTOP
     
     uiMode = UiMode.TERMINAL
    
       try {
        val intent = Intent(context, com.termux.x11.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to open X11 desktop", Toast.LENGTH_SHORT).show()
    }
        AppLogger.log("Entered Debian X11 desktop")
    }

    fun cycleLauncherDefault() {
        launcherDefault = AppPrefs.cycleLauncherDefaultPref(launcherDefault)
        AppPrefs.writeLauncherDefault(prefs, launcherDefault)
    }

    fun setLauncherDefaultFromMenuLabel(menuLabel: String) {
        launcherDefault = AppPrefs.menuLabelToLauncherPref(menuLabel)
        AppPrefs.writeLauncherDefault(prefs, launcherDefault)
    }

    // ----- initialisation effects (unchanged) -----
    LaunchedEffect(startInTerminal) {
        if (startInTerminal) {
            uiMode = UiMode.TERMINAL
            pendingAutoShowWayland = false
            desktopLaunchBlackout = false
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
            turnipDownloadProgress = 0 to "Downloading gl4es driver…"
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
                turnipDownloadProgress = -1 to "Gl4es download failed: ${e.message}"
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
                turnipDownloadProgress = 80 to "Turnip already downloaded, extracting…"
            }
            val ok = DisplayOrchestrator.extractTurnipDriver(context, id, distro)
            if (ok) {
                // Extract gl4es using the shared method
                DisplayOrchestrator.extractDriverTarball(context, id, gl4esFile)
            }
            withContext(Dispatchers.Main) {
                turnipDownloadProgress = if (ok) 100 to "Done" else -1 to "Extraction failed"
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
                            turnipDownloadProgress = pct to "Downloading Turnip for container $id…"
                        }
                    }
                }
            }
            if (tmpFile.length() == 0L) throw Exception("Turnip download empty")
            if (!tmpFile.renameTo(destFile)) throw Exception("Failed to rename Turnip archive")

            withContext(Dispatchers.Main) { turnipDownloadProgress = 80 to "Extracting…" }
            val turnipOk = DisplayOrchestrator.extractTurnipDriver(context, id, distro)
            if (turnipOk) {
                // Extract gl4es into the same container
                DisplayOrchestrator.extractDriverTarball(context, id, gl4esFile)
            }
            withContext(Dispatchers.Main) {
                turnipDownloadProgress = if (turnipOk) 100 to "Done" else -1 to "Turnip extraction failed"
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                turnipDownloadProgress = -1 to "Download failed: ${e.message}"
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
    // ----- native init and container check -----
    LaunchedEffect(Unit) {
        AppLogger.log("Starting native init and asset sync")
        val r = NativeInstallCoordinator.initNativeAndSyncAssets(
            context = context,
            prefs = prefs,
            allowedVulkan = VULKAN_MODES,
            allowedOpenGL = OPENGL_MODES,
        )
        if (!r.ok) {
            errorMsg = "Failed to initialize native layer"
            AppLogger.log("Native init FAILED")
            return@LaunchedEffect
        }

        initialized = true
        refreshContainerState()
        refreshLegacyFlags()
        // Remove leftover Turnip .tmp files
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

        // If no container installed and the setup hasn't been acknowledged, show distro picker
      if (!anyRootfsInstalled && !setupAlreadyCompleted) {
    distroFetchState = DistroFetchState.LOADING
    availableDistros = withContext(Dispatchers.IO) {
        NativeInstallCoordinator.fetchAvailableDistros()
    }
    distroFetchState = if (availableDistros.isEmpty()) {
        DistroFetchState.ERROR
    } else {
        DistroFetchState.LOADED
    }
    showDistroSelection = true
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
            isStillPending = { pendingAutoShowWayland && uiMode == UiMode.ARCH_WAYLAND_DESKTOP && !waylandVisible },
            hasActiveClients = { WaylandBridge.nativeHasActiveClients() },
            onReady = {
                waylandVisible = true
                pendingAutoShowWayland = false
            },
        )
    }

    LaunchedEffect(anyRootfsInstalled, startInTerminal) {
        if (!anyRootfsInstalled) return@LaunchedEffect
        var waited = 0
        while (!NativeBridge.isSessionAlive(TerminalSessionIds.ARCH_TERMINAL) && waited < 256) {
            delay(32)
            waited++
        }
        if (!NativeBridge.isSessionAlive(TerminalSessionIds.ARCH_TERMINAL)) {
            desktopLaunchBlackout = false
            return@LaunchedEffect
        }
        desktopLaunchBlackout = false
        if (uiMode == UiMode.ARCH_WAYLAND_DESKTOP) return@LaunchedEffect
        uiMode = UiMode.TERMINAL
    }

    // ----- graphics helpers -----
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
    // Build the export snippet from the current preferences
    val envContent = DisplayOrchestrator.buildSystemGraphicsEnv(prefs)
    val snippet = envContent.lines()
        .filter { it.isNotBlank() }
        .joinToString(separator = "\n") { line ->
            val parts = line.split("=", limit = 2)
            if (parts.size == 2) "export ${parts[0]}=${parts[1]}" else ""
        } + "\n"
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
    // (Optional) also inject into headless display sessions if you want immediate effect there
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

    
    
    
    fun setDesktopVulkanMode(mode: String) {
        val prev = GraphicsModeController.Modes(desktopVulkanMode, desktopOpenGLMode)
        val next = GraphicsModeController.sanitize(
            GraphicsModeController.Modes(vulkan = mode, openGL = desktopOpenGLMode),
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
        if (next.vulkan == "TURNIP") {
        
        checkAndPromptTurnipDrivers()
    }
    
        AppLogger.log("Vulkan mode set to $mode")
    }


    fun setDesktopOpenGLMode(mode: String) {
        val prev = GraphicsModeController.Modes(desktopVulkanMode, desktopOpenGLMode)
        val next = GraphicsModeController.sanitize(
            GraphicsModeController.Modes(vulkan = desktopVulkanMode, openGL = mode),
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
        AppLogger.log("OpenGL mode set to $mode")
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

    if (!initialized) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
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


// ── Restart dialog after installation ───────────────────────────
if (installDone) {
    AlertDialog(
        onDismissRequest = { /* block dismiss */ },
        title = { Text("Setup complete") },
        text = { Text("Restart the app to apply the new container.") },
        confirmButton = {
            TextButton(onClick = {
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                if (intent != null) {
                    context.startActivity(intent)
                    (context as? Activity)?.finish()
                }
            }) {
                Text("Restart")
            }
        },
        dismissButton = {
            TextButton(onClick = { installDone = false }) {
                Text("Cancel")
            }
        }
    )
    return
}


if (showTurnipDriverDialog) {
    val isDownloading = turnipDownloadInProgress

    AlertDialog(
        onDismissRequest = {
            // Prevent dismiss while download is in progress
            if (!isDownloading) showTurnipDriverDialog = false
        },
        title = { Text("Turnip drivers needed") },
        text = {
            Column {
                if (!isDownloading) {
                    // Pre‑download summary
                    Text("Turnip Vulkan driver not found for containers:")
                    Spacer(Modifier.height(8.dp))
                    turnipMissingContainers.forEach { id ->
                        val distro = DisplayOrchestrator.getContainerDistroType(context, id) ?: "unknown"
                        Text("• Container $id ($distro)")
                    }
                    Text("\nDo you want to download and install them now?")
                } else {
                    // Download progress
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(16.dp))
                        Text(
                            turnipDownloadProgress.second,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { turnipDownloadProgress.first / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${turnipDownloadProgress.first}%",
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
                TextButton(onClick = {
                    // You can implement cancellation logic here if needed
                    // For now, just allow closing only after completion
                }) {
                    Text("Downloading…")
                }
            } else {
                TextButton(onClick = {
                    // Start download, do not close dialog
                    turnipDownloadInProgress = true
                    scope.launch {
                        downloadAndExtractTurnipDrivers(turnipMissingContainers)
                        // After completion, close dialog
                        turnipDownloadInProgress = false
                        showTurnipDriverDialog = false
                    }
                }) {
                    Text("Download & Install")
                }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = {
                    showTurnipDriverDialog = false
                }) {
                    Text("Cancel")
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
        title = { Text("Select installation slot") },
        text = {
            Column {
                val target = distro?.distroName ?: "archive"
                Text("Install \"$target\" to which container?")
                Spacer(modifier = Modifier.height(12.dp))
                for (id in 1..3) {
                    val occupied = when (id) {
                        1 -> hasContainer1
                        2 -> hasContainer2
                        3 -> hasContainer3
                        else -> false
                    }
                    val label = "Container $id" + if (occupied) " (occupied)" else " (empty)"
                    Button(
                        onClick = {
                            showSlotPicker = false

                                // Empty slot – install directly
                                if (distro != null) installIntoSlot(distro, id)
                                else if (localUri != null) extractLocalIntoSlot(localUri, id)
                                pendingDistro = null
                                pendingLocalUri = null
                            
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = if (occupied) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ) else ButtonDefaults.buttonColors()
                    ) {
                        Text(label)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                showSlotPicker = false
                pendingDistro = null
                pendingLocalUri = null
            }) { Text("Cancel") }
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
        title = { Text("Overwrite container $slot?") },
        text = { Text("This container already has a rootfs installed. All its files will be deleted and replaced. Make sure to backup if needed.") },
        confirmButton = {
            TextButton(onClick = {
                pendingOverwriteSlot = null
                confirmOverwriteContinuation?.resumeWith(Result.success(true))
                confirmOverwriteContinuation = null
            }) { Text("Overwrite") }
        },
        dismissButton = {
            TextButton(onClick = {
                pendingOverwriteSlot = null
                confirmOverwriteContinuation?.resumeWith(Result.success(false))
                confirmOverwriteContinuation = null
            }) { Text("Cancel") }
        }
    )
}
if (showContainerManager) {
    AlertDialog(
        onDismissRequest = { showContainerManager = false },
        title = { Text("Container Manager") },
        text = {
            Column {
                for (id in 1..3) {
                    val occupied = when (id) {
                        1 -> hasContainer1; 2 -> hasContainer2; 3 -> hasContainer3; else -> false
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(4.dp)
                    ) {
                        Text(
                            "Container $id" + if (occupied) " (installed)" else " (empty)",
                            modifier = Modifier.weight(1f)
                        )
                        // Install button
                        IconButton(onClick = {
                            showContainerManager = false
                            pendingContainerForInstall = id
                            showDistroSelection = true
                                if (availableDistros.isEmpty()) {
                                NativeInstallCoordinator.invalidateDistroCache() 
        distroFetchState = DistroFetchState.LOADING
        scope.launch {
            availableDistros = NativeInstallCoordinator.fetchAvailableDistros()
            distroFetchState = if (availableDistros.isEmpty()) DistroFetchState.ERROR else DistroFetchState.LOADED
        }
    }
                        }) {
                            Icon(
                                imageVector = Icons.Default.AddCircle,
                                contentDescription = "Install to container $id",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (occupied) {
                            // Delete button – shows confirmation instead of immediate delete
                            IconButton(onClick = {
                                showDeleteConfirmation = id
                            }) {
                                Icon(Icons.Default.Delete, "Delete")
                            }
                            // Backup button
                            IconButton(onClick = {
                                pendingContainerForBackup = id
                                backupFilePicker.launch("container${id}_backup.tar.xz")
                            }) {
                                Icon(Icons.Default.Save, "Backup")
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                // Clean cache button – shows confirmation instead of immediate clean
                TextButton(onClick = {
                    showCleanCacheConfirmation = true
                }) {
                    Text("Clean cache tarballs (*.tar.xz)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showContainerManager = false }) { Text("Close") }
        }
    )
}

// ── Delete container confirmation dialog ──────────────────────────
if (showDeleteConfirmation != null) {
    val containerId = showDeleteConfirmation!!
    AlertDialog(
        onDismissRequest = { showDeleteConfirmation = null },
        title = { Text("⚠️ Delete container $containerId?") },
        text = {
            Text("This will permanently remove the installed distro and all its files from container $containerId. This action cannot be undone.\n\nAre you sure?")
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    if (NativeInstallCoordinator.deleteContainerContents(context, containerId)) {
                        refreshContainerState()
                        refreshLegacyFlags()
                    }
                }
                showDeleteConfirmation = null
            }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { showDeleteConfirmation = null }) {
                Text("Cancel")
            }
        }
    )
}

// ── Clean cache confirmation dialog ───────────────────────────────
if (showCleanCacheConfirmation) {
    AlertDialog(
        onDismissRequest = { showCleanCacheConfirmation = false },
        title = { Text("⚠️ Clean downloaded archives?") },
        text = {
            Text("All distribution tarballs (*.tar.xz) stored in the app’s cache will be deleted. If you want to install a distro again later, you’ll have to re‑download it.\n\nDo you want to continue?")
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    NativeInstallCoordinator.cleanCacheTarballs(context)
                }
                showCleanCacheConfirmation = false
            }) {
                Text("Clean", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { showCleanCacheConfirmation = false }) {
                Text("Cancel")
            }
        }
    )
}
//───────────────────────────────
// ── Exit dialog ──────────────────────────────────────────────────
if (showExitDialog) {
    AlertDialog(
        onDismissRequest = { showExitDialog = false },
        title = { Text("Exit") },
        text = { Text("Are you sure you want to exit?") },
        confirmButton = {
            TextButton(onClick = {
                showExitDialog = false
               System.exit(0)
            }) { Text("Yes") }
        },
        dismissButton = {
            TextButton(onClick = { showExitDialog = false }) { Text("No") }
        }
    )
}
    // ── Distro selection screen ──────────────────────────────────
if (showDistroSelection) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            // ---- Header with inline Refresh Button ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Select a Linux distribution", style = MaterialTheme.typography.headlineSmall)
                
                IconButton(onClick = {
                    NativeInstallCoordinator.invalidateDistroCache() 
                    scope.launch {
                        distroFetchState = DistroFetchState.LOADING
                        availableDistros = withContext(Dispatchers.IO) {
                            NativeInstallCoordinator.fetchAvailableDistros()
                        }
                        distroFetchState = if (availableDistros.isEmpty()) DistroFetchState.ERROR else DistroFetchState.LOADED
                    }
                }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh list",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            // ---- Local file picker – ALWAYS visible ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        pickFile.launch(arrayOf("application/x-xz", "*/*"))
                    }
                    .padding(vertical = 12.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = "Select local file",
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Select local distro", fontWeight = FontWeight.Bold)
                    Text("Install from a local .tar.xz archive", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))

            // ---- Content depending on fetch state ----
            when {
                distroFetchState == DistroFetchState.LOADING -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                distroFetchState == DistroFetchState.ERROR && availableDistros.isEmpty() -> {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No distros found",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Check your internet connection and try again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        TextButton(onClick = {
                            NativeInstallCoordinator.invalidateDistroCache() 
                            scope.launch {
                                distroFetchState = DistroFetchState.LOADING
                                availableDistros = withContext(Dispatchers.IO) {
                                    NativeInstallCoordinator.fetchAvailableDistros()
                                }
                                distroFetchState = if (availableDistros.isEmpty()) DistroFetchState.ERROR else DistroFetchState.LOADED
                            }
                        }) {
                            Text("Retry")
                        }
                    }
                }

                else -> {
                    // Normal list of distros
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(availableDistros) { distro ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
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
                                    }
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // icon
                                val resourceName = distro.distroType.lowercase(Locale.ROOT).trim()
                                val iconResId = context.resources.getIdentifier(
                                    resourceName, "drawable", context.packageName
                                )
                                if (iconResId != 0) {
                                    Image(
                                        painter = painterResource(iconResId),
                                        contentDescription = distro.distroType,
                                        modifier = Modifier.size(36.dp)
                                    )
                                } else {
                                    val fallbackResId = context.resources.getIdentifier(
                                        "linux", "drawable", context.packageName
                                    )
                                    if (fallbackResId != 0) {
                                        Image(
                                            painter = painterResource(fallbackResId),
                                            contentDescription = "Linux Fallback",
                                            modifier = Modifier.size(36.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Folder,
                                            contentDescription = "Generic Distro",
                                            modifier = Modifier.size(36.dp),
                                            tint = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${distro.distroName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }} ${distro.version}",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(distro.archiveName, style = MaterialTheme.typography.bodySmall)
                                    // The size string will be rendered right here
                                    Text(distro.size, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    showDistroSelection = false
                    pendingContainerForInstall = null
                    prefs.edit().putBoolean("setup_done", true).apply()
                }
            ) {
                Text("Continue without installing")
            }
        }
    }
    return
}


    // ── Original main UI (drawer + terminal/desktop) ────────────
    AppDrawer(
        drawerState = drawerState,
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
                        openGLOptions = if (desktopVulkanMode == "TURNIP") listOf("ZINK", "GL4ES") else OPENGL_MODES,
                        hasArchRootfs = hasContainer1,     
                        onContainerManagerClick = {               
            scope.launch { drawerState.close() }
            showContainerManager = true
        }
                    )
                },
                debianContent = {
    DebianDrawerPage(
        prefs = prefs,
        drawerState = drawerState,
        scope = scope,
        terminalSessionState = terminalSessionState,
        onTerminalSessionStateChange = { terminalSessionState = it },
        x11ScriptEditorOpen = x11ScriptEditorOpen,
        onX11ScriptEditorOpenChange = { x11ScriptEditorOpen = it },
        onOpenX11Settings = { openX11Settings() },
        onEnterDebianDesktop = { enterDebianDesktop() },
        onEnterTerminal = { enterTerminal() },
        onExitDisplayModes = {
            uiMode = UiMode.TERMINAL
            waylandVisible = false
            pendingAutoShowWayland = false
        },
        hasDebianRootfs = hasContainer2,
        onContainerManagerClick = {
            scope.launch { drawerState.close() }
            showContainerManager = true
        }
    )
},

androidContent = {
    WineDrawerPage(
        prefs = prefs,
        drawerState = drawerState,
        scope = scope,
        terminalSessionState = terminalSessionState,
        onTerminalSessionStateChange = { terminalSessionState = it },
        wineScriptEditorOpen = wineScriptEditorOpen,   // you’ll need to add this state
        onWineScriptEditorOpenChange = { wineScriptEditorOpen = it },
        onEnterWineDesktop = { enterWineDesktop() },
        onEnterTerminal = { enterTerminal() },
        onExitDisplayModes = {
            uiMode = UiMode.TERMINAL
            waylandVisible = false
            pendingAutoShowWayland = false
        },
        hasWineRootfs = hasContainer3,
        onContainerManagerClick = {
            scope.launch { drawerState.close() }
            showContainerManager = true
        }
    )
},
                
                
                
                
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





