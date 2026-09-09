package app.xodos2.ui.runtime

import android.content.SharedPreferences
import android.util.Log
import app.xodos2.NativeBridge
import java.io.File

/**
 * Desktop graphics mode selection persisted in prefs and applied to native runtime.
 *
 * Contract:
 * - Values are sanitized against the provided allowed lists.
 * - When either Vulkan=VENUS or OpenGL=VIRGL is selected, the virgl host is started if possible.
 * - Callers should reset/recreate PTY sessions after a mode change (env is fixed at spawn time).
 */
object GraphicsModeController {

    private const val KEY_VULKAN = "desktop_vulkan_mode"
    private const val KEY_OPENGL = "desktop_opengl_mode"

    // The library that breaks the VirGL/Venus host. We temporarily rename it.
    private val LIB_ANDROID_PATH = "/data/data/app.xodos2/files/usr/lib/libandroid.so"
    private val LIB_ANDROID_BACKUP_PATH = "$LIB_ANDROID_PATH.bk"

    data class Modes(
        val vulkan: String,
        val openGL: String,
    )

    fun loadFromPrefs(
        prefs: SharedPreferences,
        allowedVulkan: List<String>,
        allowedOpenGL: List<String>,
        defaultVulkan: String = "LLVMPIPE",
        defaultOpenGL: String = "LLVMPIPE",
    ): Modes {
        val vkRaw = prefs.getString(KEY_VULKAN, defaultVulkan) ?: defaultVulkan
        val glRaw = prefs.getString(KEY_OPENGL, defaultOpenGL) ?: defaultOpenGL

        // Ensure the library is in its normal state before any further processing.
        // This is the only place we restore, and it runs every time preferences are loaded.
        restoreLibAndroidIfNeeded()

        return sanitize(Modes(vkRaw, glRaw), allowedVulkan, allowedOpenGL, defaultVulkan, defaultOpenGL)
    }

    fun persist(
        prefs: SharedPreferences,
        modes: Modes,
    ) {
        prefs.edit()
            .putString(KEY_VULKAN, modes.vulkan)
            .putString(KEY_OPENGL, modes.openGL)
            .apply()
    }

    fun sanitize(
        modes: Modes,
        allowedVulkan: List<String>,
        allowedOpenGL: List<String>,
        defaultVulkan: String = "LLVMPIPE",
        defaultOpenGL: String = "LLVMPIPE",
    ): Modes {
        val vk = if (modes.vulkan in allowedVulkan) modes.vulkan else defaultVulkan
        val gl = if (modes.openGL in allowedOpenGL) modes.openGL else defaultOpenGL
        return Modes(vulkan = vk, openGL = gl)
    }

    /**
     * Persists [modes] and updates virgl host state as needed.
     *
     * @return true if the mode actually changed compared to [previous].
     */
    fun applyAndMaybeToggleVirglHost(
        prefs: SharedPreferences,
        previous: Modes,
        modes: Modes,
    ): Boolean {
        val changed = previous != modes
        persist(prefs, modes)

        try {
            val useVenus = modes.vulkan == "VENUS"
            val useAngle = modes.openGL == "VIRGL"

            if (useVenus || useAngle) {
                // Disable the problematic library before starting the host
                disableLibAndroid()
                val mask = (if (useVenus) 1 else 0) or (if (useAngle) 2 else 0)
                NativeBridge.startVirglServers(mask)
            } else {
                NativeBridge.stopVirglHost()
                // Do NOT restore here – restoration happens in loadFromPrefs.
            }
        } catch (t: Throwable) {
            Log.e("GraphicsMode", "Error toggling virgl host", t)
        }

        return changed
    }

    /**
     * Restore the library if it was previously renamed.
     * Safe to call at any time (e.g., from loadFromPrefs).
     */
    private fun restoreLibAndroidIfNeeded() {
        val backup = File(LIB_ANDROID_BACKUP_PATH)
        val original = File(LIB_ANDROID_PATH)

        if (backup.exists() && !original.exists()) {
            val ok = backup.renameTo(original)
            if (!ok) {
                Log.e("GraphicsMode", "Failed to restore libandroid.so from backup")
            } else {
                Log.i("GraphicsMode", "Restored libandroid.so from .bk")
            }
        }
    }

    private fun disableLibAndroid() {
        val original = File(LIB_ANDROID_PATH)
        val backup = File(LIB_ANDROID_BACKUP_PATH)

        if (original.exists() && !backup.exists()) {
            val ok = original.renameTo(backup)
            if (!ok) {
                Log.e("GraphicsMode", "Failed to rename libandroid.so -> .bk")
            } else {
                Log.i("GraphicsMode", "Renamed libandroid.so to .bk")
            }
        }
    }
}