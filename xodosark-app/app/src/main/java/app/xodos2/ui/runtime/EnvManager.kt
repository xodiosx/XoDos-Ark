package app.xodos2.ui.runtime

import android.content.Context
import java.io.File

object EnvManager {

    private const val XFEX_ASSET  = "xfex.sh"
    private const val THEME_PREFS = "wine_theme"
    private const val THEME_KEY   = "selected"

  val THEME_WHITELIST: List<String> = emptyList()

    val KNOWN_OPTIONS: Map<String, List<String>> = mapOf(
        "WINE_DISABLE_KERNEL_WRITEWATCH" to listOf("0", "1"),
        "WINEESYNC"                      to listOf("0", "1"),
        "WINEESYNC_TERMUX"               to listOf("0", "1"),
        "WINEFSYNC"                      to listOf("0", "1"),
        "WINEDEBUG"                      to listOf("-all", "+loaddll", "+relay", "+d3d", "+d3d12,+vkd3d"),
        "VKD3D_FEATURE_LEVEL"            to listOf("11_0", "12_0", "12_1"),
        "VKD3D_DEBUG"                    to listOf("", "none", "err", "warn", "fixme", "trace"),
        "DXVK_ASYNC"                     to listOf("0", "1"),
        "DXVK_HUD"                       to listOf("", "none", "fps", "fps,version,devinfo", "full"),
        "HODLL"                          to listOf(
    "libwow64fex.dll",
    "libarm64ecfex.dll",
    "wowbox64.dll",
),
"HODLL64"                        to listOf(
    "libwow64fex.dll",
    "libarm64ecfex.dll",
    "wowbox64.dll",
),
        "FEX_TSOENABLED"                 to listOf("0", "1"),
        "FEX_HALFBARRIERTSOENABLED"      to listOf("0", "1"),
        "FEX_VECTORTSOENABLED"           to listOf("0", "1"),
        "FEX_MEMCPYSETTSOENABLED"        to listOf("0", "1"),
        "FEX_X87REDUCEDPRECISION"        to listOf("0", "1"),
        "FEX_MULTIBLOCK"                 to listOf("0", "1"),
        "FEX_SMALLTSCSCALE"              to listOf("0", "1"),
        "FEX_HOSTFEATURES"               to listOf("on", "off"),
        "FEX_DYNAMICL1CACHE"             to listOf("0", "1"),
        "FEX_DISABLEL2CACHE"             to listOf("0", "1"),
        "FEX_VOLATILEMETADATA"           to listOf("0", "1"),
        "FEX_MONOHACKS"                  to listOf("0", "1"),
        "FEX_SMC_CHECKS"                 to listOf("none", "all"),
        "MESA_GL_VERSION_OVERRIDE" to listOf(
            "",
            "3.0FC", "3.0COMPAT",
            "3.1FC", "3.1COMPAT",
            "3.2FC", "3.2COMPAT",
            "3.3FC", "3.3COMPAT",
            "4.0FC", "4.0COMPAT",
            "4.1FC", "4.1COMPAT",
            "4.2FC", "4.2COMPAT",
            "4.3FC", "4.3COMPAT",
            "4.4FC", "4.4COMPAT",
            "4.5FC", "4.5COMPAT",
            "4.6FC", "4.6COMPAT",
        ),
        "MESA_GLSL_VERSION_OVERRIDE" to listOf(
            "", "130", "140", "150", "330",
            "400", "410", "420", "430", "440", "450", "460",
        ),
        "GALLIUM_DRIVER"                 to listOf("zink", "virpipe", "llvmpipe"),
        "MESA_LOADER_DRIVER_OVERRIDE"    to listOf("zink", "virpipe", "llvmpipe"),
        "MESA_VK_WSI_PRESENT_MODE"       to listOf("immediate", "mailbox", "fifo", "relaxed"),
        "MESA_VK_WSI_DEBUG"              to listOf("blit", "noblit"),
        "ZINK_DEBUG"                     to listOf("compact", "sync", "noreorder"),
        "ZINK_DESCRIPTORS"               to listOf("lazy", "eager"),
        "MESA_SHADER_CACHE"              to listOf("128MB", "256MB", "512MB", "1GB"),
        "MESA_SHADER_CACHE_DISABLE"      to listOf("true", "false"),
        "MESA_NO_ERROR"                  to listOf("0", "1"),
        "MESA_DEBUG"                     to listOf("0", "1"),
        "mesa_glthread"                  to listOf("true", "false"),
        "USE_HEAP"                       to listOf("0", "1"),
        "TU_DEBUG"                       to listOf("", "noconform", "gmem", "sysmem", "forcebin", "layout"),
        "PRIMARY_CORES"                  to listOf("0-1", "0-3", "0-5", "0-7", "0-8"),
        "SECONDARY_CORES"                to listOf("0-1", "0-3", "0-5", "0-7"),
        "adrenotool"                     to listOf("0", "1"),
    )

    val GLSL_PAIRS: Map<String, String> = mapOf(
        "3.0FC" to "130", "3.0COMPAT" to "130",
        "3.1FC" to "140", "3.1COMPAT" to "140",
        "3.2FC" to "150", "3.2COMPAT" to "150",
        "3.3FC" to "330", "3.3COMPAT" to "330",
        "4.0FC" to "400", "4.0COMPAT" to "400",
        "4.1FC" to "410", "4.1COMPAT" to "410",
        "4.2FC" to "420", "4.2COMPAT" to "420",
        "4.3FC" to "430", "4.3COMPAT" to "430",
        "4.4FC" to "440", "4.4COMPAT" to "440",
        "4.5FC" to "450", "4.5COMPAT" to "450",
        "4.6FC" to "460", "4.6COMPAT" to "460",
    )

    data class EnvVar(val name: String, val value: String, val isUnset: Boolean = false)

    fun envFile(context: Context): File  = File(context.filesDir, "usr/opt/env")
    fun env2File(context: Context): File = File(context.filesDir, "usr/opt/env2")
    fun xfexFile(context: Context): File = File(context.filesDir, "usr/bin/xfex")
    fun colorsDir(context: Context): File = File(context.filesDir, "usr/xod/colors")

    fun listThemes(context: Context): List<String> {
        val dir = colorsDir(context)
        if (!dir.isDirectory) {
            dir.mkdirs()
            return emptyList()
        }
        val onDisk = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".reg", ignoreCase = true) }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        return if (THEME_WHITELIST.isNotEmpty()) {
            THEME_WHITELIST.filter { it in onDisk }
        } else {
            onDisk.sorted()
        }
    }

    fun selectedTheme(context: Context): String? =
        context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
            .getString(THEME_KEY, null)

    fun setSelectedTheme(context: Context, themeFileName: String?) {
        context.getSharedPreferences(THEME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (themeFileName.isNullOrBlank()) remove(THEME_KEY)
                else putString(THEME_KEY, themeFileName)
            }
            .apply()
        refreshXfex(context)
    }

    fun refreshXfex(context: Context) {
        try {
            val out = xfexFile(context)
            out.parentFile?.mkdirs()

            val raw = context.assets.open(XFEX_ASSET)
                .bufferedReader()
                .use { it.readText() }

            val theme = selectedTheme(context)
            val finalContent = if (!theme.isNullOrBlank()) {
                injectThemeCommand(raw, theme)
            } else {
                raw
            }

            out.writeText(finalContent)
            out.setReadable(true, false)
            out.setWritable(true, false)
            out.setExecutable(true, false)
        } catch (e: Exception) {
            // asset missing or IO error - leave existing xfex alone
        }
    }

    private fun injectThemeCommand(content: String, themeFileName: String): String {
        val themeCmd    = "\$PREFIX/opt/winece/arm64-v8a/bin/wine regedit \$PREFIX/xod/colors/$themeFileName && sleep 2 &"
        val themeMarker = "regedit \$PREFIX/xod/colors/"

        val lines = content.lines().toMutableList()
        val existingIdx = lines.indexOfFirst { it.contains(themeMarker) }

        if (existingIdx >= 0) {
            lines[existingIdx] = themeCmd
        } else {
            val markerLine = "EXE_NAME=\$(basename \"\$PROGRAM\")"
            val markerIdx = lines.indexOfFirst { it.trim() == markerLine }
            if (markerIdx >= 0) {
                lines.add(markerIdx + 1, themeCmd)
            } else {
                lines.add(themeCmd)
            }
        }
        return lines.joinToString("\n")
    }

    private val DEFAULT_ENV_LINES: List<String> = listOf(
        "#!/data/data/app.xodos2/files/usr/bin/bash",
        "# =====================================================================",
        "#  /data/data/app.xodos2/files/usr/opt/env",
        "#  All environment variables used by the xfex Wine launcher.",
        "#  SOURCED by xfex BEFORE Wine starts.",
        "# =====================================================================",
        "",
        "# ---------- Base path ----------",
        "export PREFIX=/data/data/app.xodos2/files/usr",
        "",
        "# ---------- Source driver config at the beginning ----------",
        "[ -f \"\$PREFIX/opt/drv\" ] && . \"\$PREFIX/opt/drv\"",
        "",
        "# ---------- Colours ----------",
        "export GREEN='\\033[0;32m'",
        "export WHITE='\\033[0;37m'",
        "export RED='\\033[0;31m'",
        "",
        "# ---------- Other base paths ----------",
        "export HOME=/data/data/app.xodos2/files/home",
        "export TMPDIR=/data/data/app.xodos2/files/usr/tmp",
        "",
        "# ---------- Wine ----------",
        "export WINE_PATH=\"\$PREFIX/opt/winece/arm64-v8a/bin/\"",
        "export WINEPREFIX=\"\$HOME/.wine\"",
        "export WINE_DISABLE_KERNEL_WRITEWATCH=1",
        "export HODLL=libwow64fex.dll",
        "export HODLL64=libarm64ecfex.dll",
        "export VKD3D_FEATURE_LEVEL=12_0",
        "export TU_DEBUG=noconform",
        "",
        "# ---------- FEX ----------",
        "export FEX_TSOENABLED=0",
        "export FEX_HALFBARRIERTSOENABLED=0",
        "export FEX_VECTORTSOENABLED=0",
        "export FEX_MEMCPYSETTSOENABLED=0",
        "export FEX_X87REDUCEDPRECISION=1",
        "export FEX_MAXINST=5000",
        "export FEX_MULTIBLOCK=1",
        "export FEX_SMALLTSCSCALE=1",
        "export FEX_HOSTFEATURES=off",
        "export FEX_DYNAMICL1CACHE=0",
        "export FEX_DISABLEL2CACHE=0",
        "export FEX_VOLATILEMETADATA=1",
        "export FEX_MONOHACKS=1",
        "export FEX_SMC_CHECKS=none",
        "",
        "# ---------- Display / Resolution ----------",
        "export DISPLAY=:0",
        "export RESOLUTION=1280x1024",
        "",
        "# ---------- Cores ----------",
        "export PRIMARY_CORES=0-7",
        "export SECONDARY_CORES=0-1",
        "export CPU_TYPE=\$CPU",
        "",
        "# ---------- Mesa / Zink ----------",
        "export GALLIUM_DRIVER=zink",
        "export MESA_LOADER_DRIVER_OVERRIDE=zink",
        "export MESA_VK_WSI_PRESENT_MODE=mailbox",
        "export MESA_VK_WSI_DEBUG=blit",
        "export ZINK_DEBUG=compact",
        "export ZINK_DESCRIPTORS=lazy",
        "export MESA_SHADER_CACHE=512MB",
        "export MESA_SHADER_CACHE_DISABLE=false",
        "export MESA_NO_ERROR=1",
        "export MESA_DEBUG=0",
        "export mesa_glthread=true",
        "export USE_HEAP=1",
        "",
        "# ---------- OpenGL / GLSL overrides (empty = unset) ----------",
        "export MESA_GL_VERSION_OVERRIDE=",
        "export MESA_GLSL_VERSION_OVERRIDE=",
        "",
        "# ---------- Wine sync ----------",
        "export WINEESYNC=0",
        "export WINEESYNC_TERMUX=1",
        "",
        "# ---------- HUD / Debug ----------",
        "export DXVK_HUD=fps,version,devinfo",
        "export WINEDEBUG=-all",
        "export LOG_PATH=/sdcard/XoDos-fex_log",
        "",
        "# ---------- Audio ----------",
        "export PULSE_SERVER=tcp:127.0.0.1",
        "",
        "# ---------- Driver switch (0 = off, 1 = use adrenotools) ----------",
       
        "",
        "# ---------- PATH ----------",
        "export PATH=\"\$PATH:\$PREFIX/opt/winece/arm64-v8a/bin/\"",
    )

    private val DEFAULT_ENV_CONTENT = DEFAULT_ENV_LINES.joinToString("\n") + "\n"

    private fun ensureEnvFile(context: Context) {
        val f = envFile(context)
        if (f.isFile && f.length() > 0L) {
            val hasExport = f.readLines().any { it.trimStart().startsWith("export ") }
            if (hasExport) return
        }
        f.parentFile?.mkdirs()
        f.writeText(DEFAULT_ENV_CONTENT)
        refreshXfex(context)
    }

    private val RE_EXPORT = Regex("^\\s*export\\s+([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
    private val RE_UNSET  = Regex("^\\s*unset\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*$")

    private fun stripQuotes(s: String): String {
        if (s.length >= 2) {
            val first = s.first()
            val last  = s.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return s.substring(1, s.length - 1)
            }
        }
        return s
    }

    private fun formatValue(v: String): String =
        if (v.isEmpty() || v.any { it.isWhitespace() }) "\"$v\"" else v

    fun readEnv(context: Context): List<EnvVar> {
        ensureEnvFile(context)
        refreshXfex(context)
        return readFile(envFile(context))
    }

    fun readEnv2(context: Context): List<EnvVar> = readFile(env2File(context))

    private fun readFile(f: File): List<EnvVar> {
        if (!f.isFile) return emptyList()
        val out = mutableListOf<EnvVar>()
        for (line in f.readLines()) {
            val mExport = RE_EXPORT.matchEntire(line)
            if (mExport != null) {
                out.add(EnvVar(mExport.groupValues[1], stripQuotes(mExport.groupValues[2])))
                continue
            }
            val mUnset = RE_UNSET.matchEntire(line)
            if (mUnset != null) {
                out.add(EnvVar(mUnset.groupValues[1], "", isUnset = true))
            }
        }
        return out
    }

    fun updateVar(context: Context, name: String, newValue: String): Boolean {
        ensureEnvFile(context)
        val f = envFile(context)
        val lines = f.readLines().toMutableList()
        val formatted = formatValue(newValue)
        for (i in lines.indices) {
            val exportName = RE_EXPORT.matchEntire(lines[i])?.groupValues?.get(1)
            val unsetName  = RE_UNSET.matchEntire(lines[i])?.groupValues?.get(1)
            if (exportName == name || unsetName == name) {
                lines[i] = "export $name=$formatted"
                f.writeText(lines.joinToString("\n") + "\n")
                refreshXfex(context)
                return true
            }
        }
        return false
    }

    fun setEnv2Var(context: Context, name: String, value: String): Boolean {
        val f = env2File(context)
        val lines = if (f.isFile) f.readLines().toMutableList() else mutableListOf()
        val formatted = formatValue(value)
        var found = false
        for (i in lines.indices) {
            if (RE_EXPORT.matchEntire(lines[i])?.groupValues?.get(1) == name) {
                lines[i] = "export $name=$formatted"
                found = true
                break
            }
        }
        if (!found) lines.add("export $name=$formatted")
        f.parentFile?.mkdirs()
        f.writeText(lines.joinToString("\n") + "\n")
        refreshXfex(context)
        return true
    }

    fun removeEnv2Var(context: Context, name: String): Boolean {
        val f = env2File(context)
        if (!f.isFile) return false
        val kept = f.readLines().filter { line ->
            RE_EXPORT.matchEntire(line)?.groupValues?.get(1) != name
        }
        f.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        refreshXfex(context)
        return true
    }
}