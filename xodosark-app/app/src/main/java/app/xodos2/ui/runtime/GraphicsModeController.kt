package app.xodos2.ui.runtime

import android.content.SharedPreferences
import app.xodos2.NativeBridge

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
    private const val KEY_VORTEK = "desktop_vortek_mode"

    data class Modes(
        val vulkan: String,
        val openGL: String,
        val vortek: String = "Disabled",
    )

    fun loadFromPrefs(
        prefs: SharedPreferences,
        allowedVulkan: List<String>,
        allowedOpenGL: List<String>,
        allowedVortek: List<String> = listOf("Disabled", "VORTEK_AUTO", "VORTEK_OPTIMIZED", "VORTEK_COMPAT", "VORTEK_PASSTHROUGH"),
        defaultVulkan: String = "LLVMPIPE",
        defaultOpenGL: String = "LLVMPIPE",
        defaultVortek: String = "Disabled",
    ): Modes {
        val vkRaw = prefs.getString(KEY_VULKAN, defaultVulkan) ?: defaultVulkan
        val glRaw = prefs.getString(KEY_OPENGL, defaultOpenGL) ?: defaultOpenGL
        val vtRaw = prefs.getString(KEY_VORTEK, defaultVortek) ?: defaultVortek
        return sanitize(Modes(vkRaw, glRaw, vtRaw), allowedVulkan, allowedOpenGL, allowedVortek, defaultVulkan, defaultOpenGL, defaultVortek)
    }

    fun persist(
        prefs: SharedPreferences,
        modes: Modes,
    ) {
        prefs.edit()
            .putString(KEY_VULKAN, modes.vulkan)
            .putString(KEY_OPENGL, modes.openGL)
            .putString(KEY_VORTEK, modes.vortek)
            .apply()
    }

    fun sanitize(
        modes: Modes,
        allowedVulkan: List<String>,
        allowedOpenGL: List<String>,
        allowedVortek: List<String> = listOf("Disabled", "VORTEK_AUTO", "VORTEK_OPTIMIZED", "VORTEK_COMPAT", "VORTEK_PASSTHROUGH"),
        defaultVulkan: String = "LLVMPIPE",
        defaultOpenGL: String = "LLVMPIPE",
        defaultVortek: String = "Disabled",
    ): Modes {
        val vk = if (modes.vulkan in allowedVulkan) modes.vulkan else defaultVulkan
        val gl = if (modes.openGL in allowedOpenGL) modes.openGL else defaultOpenGL
        val vt = if (modes.vortek in allowedVortek) modes.vortek else defaultVortek
        return Modes(vulkan = vk, openGL = gl, vortek = vt)
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
            val mask = (if (useVenus) 1 else 0) or (if (useAngle) 2 else 0)
            NativeBridge.startVirglServers(mask)
        } else {
            NativeBridge.stopVirglHost()
        }
    } catch (_: Throwable) { }

    return changed
}
    
    
}

