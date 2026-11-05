package app.xodos2.ui.runtime

import android.content.Context
import java.io.File

/**
 * Reads / edits $PREFIX/opt/env (dropdown values, in place) and
 * $PREFIX/opt/env2 (custom vars, append / delete).
 *
 * Behaves like `sed -i 's|^export VAR=.*|export VAR=NEW|' env`:
 * only replaces the value on the matching export / unset line.
 */
object EnvManager {

    /** Dropdown options for known variables. Anything else → free-text field. */
    val KNOWN_OPTIONS: Map<String, List<String>> = mapOf(
        "WINE_DISABLE_KERNEL_WRITEWATCH" to listOf("0", "1"),
        "VKD3D_FEATURE_LEVEL"            to listOf("11_0", "12_0", "12_1"),
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
        "WINEESYNC"                      to listOf("0", "1"),
        "WINEESYNC_TERMUX"               to listOf("0", "1"),
        "WINEDEBUG"                      to listOf("-all", "+loaddll", "+relay"),
        "adrenotool"                     to listOf("0", "1"),
    )

    data class EnvVar(val name: String, val value: String, val isUnset: Boolean = false)

    fun envFile(context: Context): File  = File(context.filesDir, "usr/opt/env")
    fun env2File(context: Context): File = File(context.filesDir, "usr/opt/env2")

    private val RE_EXPORT = Regex("""^\s*export\s+([A-Za-z_][A-Za-z0-9_]*)=(.*)$""")
    private val RE_UNSET  = Regex("""^\s*unset\s+([A-Za-z_][A-Za-z0-9_]*)\s*$""")

    private fun stripQuotes(s: String): String {
        if (s.length >= 2) {
            val f = s.first(); val l = s.last()
            if ((f == '"' && l == '"') || (f == '\'' && l == '\'')) return s.substring(1, s.length - 1)
        }
        return s
    }

    private fun formatValue(v: String): String =
        if (v.isEmpty() || v.any { it.isWhitespace() }) "\"$v\"" else v

    fun readEnv(context: Context): List<EnvVar>  = readFile(envFile(context))
    fun readEnv2(context: Context): List<EnvVar> = readFile(env2File(context))

    private fun readFile(f: File): List<EnvVar> {
        if (!f.isFile) return emptyList()
        val out = mutableListOf<EnvVar>()
        f.readLines().forEach { line ->
            RE_EXPORT.matchEntire(line)?.let { m ->
                out.add(EnvVar(m.groupValues[1], stripQuotes(m.groupValues[2])))
            } ?: RE_UNSET.matchEntire(line)?.let { m ->
                out.add(EnvVar(m.groupValues[1], "", isUnset = true))
            }
        }
        return out
    }

    /** Replace only the value on an existing export/unset line. */
    fun updateVar(context: Context, name: String, newValue: String): Boolean {
        val f = envFile(context)
        if (!f.isFile) return false
        val lines = f.readLines().toMutableList()
        val formatted = formatValue(newValue)
        for (i in lines.indices) {
            if (RE_EXPORT.matchEntire(lines[i])?.groupValues?.get(1) == name ||
                RE_UNSET.matchEntire(lines[i])?.groupValues?.get(1) == name
            ) {
                lines[i] = "export $name=$formatted"
                f.writeText(lines.joinToString("\n") + "\n")
                return true
            }
        }
        return false
    }

    /** Add or replace a custom variable in env2. */
    fun setEnv2Var(context: Context, name: String, value: String): Boolean {
        val f = env2File(context)
        val lines = if (f.isFile) f.readLines().toMutableList() else mutableListOf()
        val formatted = formatValue(value)
        var found = false
        for (i in lines.indices) {
            if (RE_EXPORT.matchEntire(lines[i])?.groupValues?.get(1) == name) {
                lines[i] = "export $name=$formatted"; found = true; break
            }
        }
        if (!found) lines.add("export $name=$formatted")
        f.parentFile?.mkdirs()
        f.writeText(lines.joinToString("\n") + "\n")
        return true
    }

    /** Remove a variable from env2. */
    fun removeEnv2Var(context: Context, name: String): Boolean {
        val f = env2File(context)
        if (!f.isFile) return false
        val kept = f.readLines().filterNot { RE_EXPORT.matchEntire(it)?.groupValues?.get(1) == name }
        f.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n") + "\n")
        return true
    }
}