package app.xodos2.ui.runtime

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.util.Log
import app.xodos2.NativeBridge
import app.xodos2.TerminalSessionIds
import app.xodos2.WaylandBridge
import app.xodos2.ui.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object DisplayOrchestrator {
    private const val HEADLESS_X11_INJECT_DELAY_MS = 400L
    private const val X11_SOCKET_WAIT_POLL_MS = 120L
    private const val X11_SOCKET_WAIT_MAX_POLLS = 120 // ~14s

    data class WaylandEnvState(
        val hiddenInjectedKey: String,
    )

    fun prepareWaylandRuntimeAndStartServer(context: Context, waylandRuntimeDir: String): Boolean {
        val keymapTarget = File(waylandRuntimeDir, "keymap_us.xkb")
        if (!keymapTarget.exists()) {
            try {
                context.assets.open("keymap_us.xkb").use { input ->
                    keymapTarget.outputStream().use { out ->
                        input.copyTo(out)
                    }
                }
            } catch (_: Throwable) {
                return false
            }
        }
        return try {
            WaylandBridge.nativeStartServer(waylandRuntimeDir)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun ensureArchWaylandDisplaySession() {
        if (!NativeBridge.isSessionAlive(TerminalSessionIds.ARCH_WAYLAND_DISPLAY)) {
            NativeBridge.spawnSession(TerminalSessionIds.ARCH_WAYLAND_DISPLAY, 24, 80)
        }
    }

    fun ensureDebianX11DisplaySession(hasDebianRootfs: Boolean): Boolean {
        if (!hasDebianRootfs) return false
        if (NativeBridge.isSessionAlive(TerminalSessionIds.DEBIAN_X11_DISPLAY)) return true
        return NativeBridge.spawnSessionInRootfs(
            TerminalSessionIds.DEBIAN_X11_DISPLAY,
            24, 80,
            TerminalSessionIds.rootfsKindForNativeId(TerminalSessionIds.DEBIAN_X11_DISPLAY),
        )
    }

    fun runWineX11DesktopStartupScript(
        context: Context,
        prefs: SharedPreferences,
        headlessInjectHandler: Handler,
        hasWineRootfs: Boolean,
    ) {
        if (!hasWineRootfs) return
        if (!NativeBridge.isSessionAlive(TerminalSessionIds.WINE_X11_DISPLAY)) {
            if (!NativeBridge.spawnSessionInRootfs(
                    TerminalSessionIds.WINE_X11_DISPLAY,
                    24, 80,
                    TerminalSessionIds.rootfsKindForNativeId(TerminalSessionIds.WINE_X11_DISPLAY),
                )
            ) return
        }
        val targetId = TerminalSessionIds.WINE_X11_DISPLAY
        val user = (prefs.getString("wine_x11_startup_script", "") ?: "").trim()
        val graphicsEnv = buildSystemGraphicsEnv(prefs)
val payload = buildString {
    graphicsEnv.lines()
        .filter { it.isNotBlank() }
        .forEach { line ->
            append(line)          // line already contains "export ..." or "unset ..."
            append('\n')
        }
    append(AppPrefs.buildDebianX11ImplicitEnvSnippet())
    if (user.isNotEmpty()) {
        append(user)
        if (!user.endsWith('\n')) append('\n')
    }
}
        if (payload.isEmpty()) return
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val x0 = File(context.filesDir, "usr/tmp/.X11-unix/X0")
        var polls = 0
        val inject = {
            headlessInjectHandler.postDelayed(
                { NativeBridge.writeInput(targetId, bytes) },
                HEADLESS_X11_INJECT_DELAY_MS,
            )
        }
        val waiter = object : Runnable {
            override fun run() {
                polls += 1
                if (x0.exists() || polls >= X11_SOCKET_WAIT_MAX_POLLS) {
                    inject()
                    return
                }
                headlessInjectHandler.postDelayed(this, X11_SOCKET_WAIT_POLL_MS)
            }
        }
        headlessInjectHandler.post(waiter)
    }

    fun ensureArchX11DisplaySession(): Boolean {
        if (NativeBridge.isSessionAlive(TerminalSessionIds.ARCH_X11_DISPLAY)) return true
        return NativeBridge.spawnSessionInRootfs(
            TerminalSessionIds.ARCH_X11_DISPLAY,
            24, 80,
            TerminalSessionIds.rootfsKindForNativeId(TerminalSessionIds.ARCH_X11_DISPLAY),
        )
    }

    fun runArchX11DesktopStartupScript(
        context: Context,
        prefs: SharedPreferences,
        headlessInjectHandler: Handler,
        hasArchRootfs: Boolean,
    ) {
        if (!hasArchRootfs) return
        if (!ensureArchX11DisplaySession()) return
        val targetId = TerminalSessionIds.ARCH_X11_DISPLAY
        val user = AppPrefs.readArchX11DesktopStartupScript(prefs).trim()
        val graphicsEnv = buildSystemGraphicsEnv(prefs)
val payload = buildString {
    graphicsEnv.lines()
        .filter { it.isNotBlank() }
        .forEach { line ->
            append(line)          // line already contains "export ..." or "unset ..."
            append('\n')
        }
    append(AppPrefs.buildDebianX11ImplicitEnvSnippet())
    if (user.isNotEmpty()) {
        append(user)
        if (!user.endsWith('\n')) append('\n')
    }
}
        if (payload.isEmpty()) return
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val x0 = File(context.filesDir, "usr/tmp/.X11-unix/X0")
        var polls = 0
        val inject = {
            headlessInjectHandler.postDelayed(
                { NativeBridge.writeInput(targetId, bytes) },
                HEADLESS_X11_INJECT_DELAY_MS,
            )
        }
        val waiter = object : Runnable {
            override fun run() {
                polls += 1
                if (x0.exists() || polls >= X11_SOCKET_WAIT_MAX_POLLS) {
                    inject()
                    return
                }
                headlessInjectHandler.postDelayed(this, X11_SOCKET_WAIT_POLL_MS)
            }
        }
        headlessInjectHandler.post(waiter)
    }

    fun runDebianX11DesktopStartupScript(
        context: Context,
        prefs: SharedPreferences,
        headlessInjectHandler: Handler,
        hasDebianRootfs: Boolean,
    ) {
        if (!ensureDebianX11DisplaySession(hasDebianRootfs)) return
        val targetId = TerminalSessionIds.DEBIAN_X11_DISPLAY
        val user = AppPrefs.readDebianDesktopStartupScript(prefs).trim()
        val graphicsEnv = buildSystemGraphicsEnv(prefs)
val payload = buildString {
    graphicsEnv.lines()
        .filter { it.isNotBlank() }
        .forEach { line ->
            append(line)          // line already contains "export ..." or "unset ..."
            append('\n')
        }
    append(AppPrefs.buildDebianX11ImplicitEnvSnippet())
    if (user.isNotEmpty()) {
        append(user)
        if (!user.endsWith('\n')) append('\n')
    }
}
        if (payload.isEmpty()) return
        val bytes = payload.toByteArray(Charsets.UTF_8)
        val x0 = File(context.filesDir, "usr/tmp/.X11-unix/X0")
        var polls = 0
        val inject = {
            headlessInjectHandler.postDelayed(
                { NativeBridge.writeInput(targetId, bytes) },
                HEADLESS_X11_INJECT_DELAY_MS,
            )
        }
        val waiter = object : Runnable {
            override fun run() {
                polls += 1
                if (x0.exists() || polls >= X11_SOCKET_WAIT_MAX_POLLS) {
                    inject()
                    return
                }
                headlessInjectHandler.postDelayed(this, X11_SOCKET_WAIT_POLL_MS)
            }
        }
        headlessInjectHandler.post(waiter)
    }

    fun buildWaylandAndGraphicsEnvSnippet(socketName: String, vulkanMode: String, openGLMode: String): String {
        val b = StringBuilder()
        b.append("export WAYLAND_DISPLAY=").append(socketName).append("\n")
        when (openGLMode) {
            "VIRGL" -> {
                b.append("unset VK_ICD_FILENAMES MESA_VK_WSI_PRESENT_MODE MESA_LOADER_DRIVER_OVERRIDE VKD3D_FEATURE_LEVEL VK_DRIVER_FILES VN_DEBUG || true\n")
                b.append("export GALLIUM_DRIVER=virpipe\n")
                b.append("export MESA_LOADER_DRIVER_OVERRIDE=virpipe\n")
                b.append("export LIBGL_ALWAYS_SOFTWARE=0\n")
                b.append("export VTEST_SOCKET_NAME=/run/xodos2-virgl/vtest.sock\n")
                b.append("export VTEST_RENDERER_SOCKET_NAME=/run/xodos2-virgl/vtest.sock\n")
            }
            "ZINK" -> {
                b.append("export VKD3D_FEATURE_LEVEL=12_0\n")
                b.append("export MESA_LOADER_DRIVER_OVERRIDE=zink\n")
                b.append("export MESA_VK_WSI_PRESENT_MODE=mailbox\n")
                b.append("export MESA_LOADER_DRIVER_OVERRIDE=zink\n")
                b.append("export LIBGL_ALWAYS_SOFTWARE=0\n")
            }
            "GL4ES" -> {
                b.append("export VKD3D_FEATURE_LEVEL=12_0\n")
                b.append("export MESA_LOADER_DRIVER_OVERRIDE=zink\n")
                b.append("export MESA_VK_WSI_PRESENT_MODE=mailbox\n")
                b.append("export MESA_LOADER_DRIVER_OVERRIDE=zink\n")
                b.append("export LIBGL_ALWAYS_SOFTWARE=0\n")
                b.append("export LD_LIBRARY_PATH=/usr/lib/aarch64-linux-gnu/gl4es:\$LD_LIBRARY_PATH\n")
            }
            else -> {
                b.append("unset VK_ICD_FILENAMES MESA_VK_WSI_PRESENT_MODE MESA_LOADER_DRIVER_OVERRIDE VKD3D_FEATURE_LEVEL VK_DRIVER_FILES VN_DEBUG || true\n")             
                b.append("export GALLIUM_DRIVER=llvmpipe\n")
                b.append("export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe\n")
                b.append("export LIBGL_ALWAYS_SOFTWARE=1\n")
            }
        }
        when (vulkanMode) {
            "VENUS" -> {
                
                b.append("export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/virtio_icd.json\n")
                b.append("export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/virtio_icd.json\n")
                b.append("export VN_DEBUG=vtest\n")
                b.append("export VTEST_SOCKET_NAME=/run/xodos2-virgl/venus.sock\n")
                b.append("export VTEST_RENDERER_SOCKET_NAME=/run/xodos2-virgl/venus.sock\n")
            }
            "TURNIP" -> {
                b.append("export VKD3D_FEATURE_LEVEL=12_0\n")
                b.append("export MESA_LOADER_DRIVER_OVERRIDE=zink\n")
                b.append("export MESA_VK_WSI_PRESENT_MODE=mailbox\n")
                //b.append("export GALLIUM_DRIVER=zink\n")
                b.append("export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json\n")
                b.append("export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json\n")
                b.append("export TU_DEBUG=noconform\n")
            }
            else -> {
                b.append("unset VK_ICD_FILENAMES MESA_VK_WSI_PRESENT_MODE MESA_LOADER_DRIVER_OVERRIDE VKD3D_FEATURE_LEVEL VK_DRIVER_FILES VN_DEBUG || true\n")           
                b.append("export GALLIUM_DRIVER=llvmpipe\n")
                b.append("unset VK_ICD_FILENAMES MESA_VK_WSI_PRESENT_MODE MESA_LOADER_DRIVER_OVERRIDE VKD3D_FEATURE_LEVEL VK_DRIVER_FILES VN_DEBUG || true\n")
            }
        }
        return b.toString()
    }

    fun buildSystemGraphicsEnv(prefs: SharedPreferences): String {
        val vulkan = prefs.getString("desktop_vulkan_mode", "LLVMPIPE") ?: "LLVMPIPE"
        val openGL = prefs.getString("desktop_opengl_mode", "LLVMPIPE") ?: "LLVMPIPE"
        val sb = StringBuilder()
        sb.append("export DISPLAY=:0\n")
        when (openGL) {
            "VIRGL" -> {
                sb.append("unset VK_ICD_FILENAMES MESA_VK_WSI_PRESENT_MODE MESA_LOADER_DRIVER_OVERRIDE VKD3D_FEATURE_LEVEL VK_DRIVER_FILES VN_DEBUG || true\n")
                sb.append("export GALLIUM_DRIVER=virpipe\n")
                sb.append("export MESA_LOADER_DRIVER_OVERRIDE=virpipe\n")
                sb.append("export LIBGL_ALWAYS_SOFTWARE=0\n")
                sb.append("export VTEST_SOCKET_NAME=/run/xodos2-virgl/vtest.sock\n")
                sb.append("export VTEST_RENDERER_SOCKET_NAME=/run/xodos2-virgl/vtest.sock\n")
            }
            "ZINK" -> {
                sb.append("export VKD3D_FEATURE_LEVEL=12_0\n")
                sb.append("export MESA_LOADER_DRIVER_OVERRIDE=zink\n")               
                sb.append("export GALLIUM_DRIVER=zink\n")
                sb.append("export MESA_LOADER_DRIVER_OVERRIDE=zink\n")
                sb.append("export LIBGL_ALWAYS_SOFTWARE=0\n")
            }
            "GL4ES" -> {
                sb.append("export GALLIUM_DRIVER=zink\n")
                sb.append("export MESA_LOADER_DRIVER_OVERRIDE=zink\n")
                sb.append("export LIBGL_ALWAYS_SOFTWARE=0\n")
                sb.append("export LD_LIBRARY_PATH=/usr/lib/aarch64-linux-gnu/gl4es:\$LD_LIBRARY_PATH\n")
            }
            else -> {
                sb.append("unset VK_ICD_FILENAMES MESA_VK_WSI_PRESENT_MODE MESA_LOADER_DRIVER_OVERRIDE VKD3D_FEATURE_LEVEL VK_DRIVER_FILES VN_DEBUG || true\n")
                sb.append("export GALLIUM_DRIVER=llvmpipe\n")
                sb.append("export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe\n")
                sb.append("export LIBGL_ALWAYS_SOFTWARE=1\n")
                
            }
        }
        when (vulkan) {
            "VENUS" -> {
                sb.append("export MESA_VK_WSI_PRESENT_MODE=mailbox\n")
                sb.append("export TU_DEBUG=noconform\n")
               // sb.append("export GALLIUM_DRIVER=zink\n")
                sb.append("export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/virtio_icd.json\n")
                sb.append("export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/virtio_icd.json\n")
                sb.append("export VN_DEBUG=vtest\n")
                sb.append("export VTEST_SOCKET_NAME=/run/xodos2-virgl/venus.sock\n")
                sb.append("export VTEST_RENDERER_SOCKET_NAME=/run/xodos2-virgl/venus.sock\n")
            }
            "TURNIP" -> {
                sb.append("export MESA_VK_WSI_PRESENT_MODE=mailbox\n")
                sb.append("export TU_DEBUG=noconform\n")             
              //  sb.append("export GALLIUM_DRIVER=zink\n")
                sb.append("export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json\n")
                sb.append("export VK_DRIVER_FILES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json\n")
                sb.append("export TU_DEBUG=noconform\n")
            }
            else -> {
                sb.append("unset VK_ICD_FILENAMES MESA_VK_WSI_PRESENT_MODE VK_DRIVER_FILES VN_DEBUG || true\n")
                sb.append("export VK_ICD_FILENAMES=/dev/null\n")
            }
        }
        return sb.toString()
    }

    fun updateContainersSystemEnvironment(context: Context, prefs: SharedPreferences) {
        val envContent = buildSystemGraphicsEnv(prefs)
        for (id in 1..3) {
            val containerDir = NativeInstallCoordinator.containerPath(context, id)
            if (!containerDir.isDirectory) continue
            val etcDir = File(containerDir, "etc")
            etcDir.mkdirs()
            val envFile = File(etcDir, "environment")
            envFile.writeText(envContent)
        }
    }

    // ─── Turnip driver helpers ──────────────────────────────────

    fun getContainerDistroType(context: Context, containerId: Int): String? {
        val prefs = context.getSharedPreferences("xodos2_containers", Context.MODE_PRIVATE)
        return prefs.getString("container_distro_$containerId", null)?.lowercase()
    }

    fun turnipAssetPattern(distroType: String): String {
    // Normalise distro type
    val t = distroType.lowercase()
    return when {
        // Arch family
        t == "archlinux" -> "debian_trixie"
        t == "artix"     -> "debian_trixie"
        t == "manjaro"   -> "debian_trixie"   // treat as Arch if no dedicated asset

        // Debian family – everything goes to the same `debian` driver
        t == "debian"       -> "debian_trixie"
        t == "ubuntu"       -> "debian_trixie"
        t == "trisquel"     -> "debian_trixie"
        t == "deepin"       -> "debian_trixie"
        t == "kali"         -> "debian_trixie"  
        t == "raspbian"     -> "debian_trixie"

        // RPM family 
        t == "fedora"       -> "fedora_43"
        t == "almalinux"    -> "fedora_43"
        t == "rocky"        -> "fedora_43"

        // Alpine
        t == "alpine"       -> "debian_trixie"

        // Void
        t == "void"         -> "void"

        // Fallback 
        else -> "debian_trixie"   
    }
}

    fun hasTurnipTarball(context: Context, distroType: String): Boolean {
        val pattern = turnipAssetPattern(distroType)
        val driversDir = File(context.filesDir, "drivers")
        if (!driversDir.exists()) return false
        val files = driversDir.listFiles { f ->
            f.name.startsWith("turnip_") &&
            f.name.contains(pattern) &&
            f.name.endsWith(".tar.gz") &&
            !f.name.endsWith(".tmp")
        }
        return files != null && files.isNotEmpty()
    }

    /**
     * Extracts a Turnip driver tarball (.tar.gz) into the container rootfs.
     */
/**
 * Extracts a Turnip driver tarball (.tar.gz) into the container rootfs.
 */
suspend fun extractTurnipDriver(context: Context, containerId: Int, distroType: String): Boolean =
    withContext(Dispatchers.IO) {
        val pattern = turnipAssetPattern(distroType)
        val driversDir = File(context.filesDir, "drivers")
        val tarball = driversDir.listFiles { f ->
            f.name.startsWith("turnip_") && f.name.contains(pattern) && f.name.endsWith(".tar.gz")
        }?.firstOrNull() ?: return@withContext false

        val rootfs = NativeInstallCoordinator.containerPath(context, containerId)
        if (!rootfs.isDirectory) return@withContext false

        // Use the same environment that works in the terminal
        val env = mutableMapOf<String, String>()
        env["PATH"] = "/data/data/app.xodos2/files/usr/bin:${System.getenv("PATH") ?: "/system/bin"}"
        env["LD_LIBRARY_PATH"] = "/data/data/app.xodos2/files/usr/lib:${System.getenv("LD_LIBRARY_PATH") ?: ""}"

        val tarFlag = when {
            tarball.name.endsWith(".tar.gz") -> "z"
            tarball.name.endsWith(".tar.xz") -> "J"
            tarball.name.endsWith(".tar") -> ""
            else -> return@withContext false
        }

        val tarExe = File(context.filesDir, "usr/bin/tar")
        val cmd = arrayOf(
            tarExe.absolutePath,
            "-x${tarFlag}f", tarball.absolutePath,
            "-C", rootfs.absolutePath,
            "--exclude=system", "--exclude=apex", "--exclude=data",
            "--exclude=sdcard", "--exclude=storage"
        )
        val pb = ProcessBuilder(*cmd)
            .directory(rootfs)
            .redirectErrorStream(true)
        pb.environment().putAll(env)

        val process = pb.start()
        val exitCode = process.waitFor()

        if (exitCode == 0) {
            val marker = File(rootfs, "etc/.xodos2_turnip_driver_installed")
            marker.parentFile?.mkdirs()
            marker.createNewFile()
            true
        } else false
    }

/**
 * Generic extraction of a driver tarball into a container rootfs.
 * Handles .tar.gz and .tar.xz automatically.
 */
fun extractDriverTarball(context: Context, containerId: Int, tarball: File) {
    val rootfs = NativeInstallCoordinator.containerPath(context, containerId)
    if (!rootfs.isDirectory || !tarball.exists()) return

    val env = mutableMapOf<String, String>()
    env["PATH"] = "/data/data/app.xodos2/files/usr/bin:${System.getenv("PATH") ?: "/system/bin"}"
    env["LD_LIBRARY_PATH"] = "/data/data/app.xodos2/files/usr/lib:${System.getenv("LD_LIBRARY_PATH") ?: ""}"

    val tarFlag = when {
        tarball.name.endsWith(".tar.gz") -> "z"
        tarball.name.endsWith(".tar.xz") -> "J"
        tarball.name.endsWith(".tar") -> ""
        else -> return
    }

    val tarExe = File(context.filesDir, "usr/bin/tar")
    val cmd = arrayOf(
        tarExe.absolutePath,
        "-x${tarFlag}f", tarball.absolutePath,
        "-C", rootfs.absolutePath,
        "--exclude=system", "--exclude=apex", "--exclude=data",
        "--exclude=sdcard", "--exclude=storage"
    )
    try {
        val pb = ProcessBuilder(*cmd)
            .directory(rootfs)
            .redirectErrorStream(true)
        pb.environment().putAll(env)
        val process = pb.start()
        process.waitFor()
    } catch (e: Exception) {
        Log.e("DisplayOrchestrator", "Failed to extract ${tarball.name}", e)
    }
}
    fun isTurnipDriverInstalled(context: Context, containerId: Int): Boolean {
        val rootfs = NativeInstallCoordinator.containerPath(context, containerId)
        val marker = File(rootfs, "etc/.xodos2_turnip_driver_installed")
        return marker.exists()
    }

    fun runArchWaylandStartupScriptIfNeeded(
        prefs: SharedPreferences,
        desktopSocketName: String,
        vulkanMode: String,
        openGLMode: String,
        currentHiddenInjectedKey: String,
    ): WaylandEnvState {
        val hasClients = try {
            WaylandBridge.nativeHasActiveClients()
        } catch (_: Throwable) {
            false
        }
        val hiddenKey = "$desktopSocketName|$vulkanMode|$openGLMode"
        ensureArchWaylandDisplaySession()
        if (currentHiddenInjectedKey != hiddenKey) {
            NativeBridge.writeInput(
                TerminalSessionIds.ARCH_WAYLAND_DISPLAY,
                buildWaylandAndGraphicsEnvSnippet(desktopSocketName, vulkanMode, openGLMode)
                    .toByteArray(Charsets.UTF_8)
            )
        }
        if (!hasClients) {
            val script = prefs.getString("desktop_startup_script", "")?.trim()
            if (!script.isNullOrEmpty()) {
                ensureArchWaylandDisplaySession()
                NativeBridge.writeInput(
                    TerminalSessionIds.ARCH_WAYLAND_DISPLAY,
                    (script + "\n").toByteArray(Charsets.UTF_8)
                )
            }
        }
        return WaylandEnvState(hiddenInjectedKey = hiddenKey)
    }
}