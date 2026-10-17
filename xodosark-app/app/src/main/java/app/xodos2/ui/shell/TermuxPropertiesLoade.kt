package app.xodos2.ui.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Properties

data class TermuxProperties(
    val backgroundColor: Int? = null,
    val foregroundColor: Int? = null,
    val cursorColor: Int? = null,
    val ansiColors: IntArray? = null,
    val cursorBlinkRate: Int? = null,
    val fontPath: String? = null,
    val extraKeys: String? = null,
    val sourceFile: File? = null
)

object TermuxPropertiesLoader {
    private const val TAG = "TermuxProps"

    fun candidates(context: Context, containerId: Int): List<File> = listOf(
        File(context.filesDir, "containers/$containerId/root/.termux/termux.properties"),
        File(context.filesDir, "home/.termux/termux.properties"),
        File(context.filesDir, "termux.properties")
    )

    fun defaultFile(context: Context, containerId: Int): File {
        val prootRoot = File(context.filesDir, "containers/$containerId/root")
        return if (prootRoot.isDirectory) {
            File(prootRoot, ".termux/termux.properties")
        } else {
            File(context.filesDir, "home/.termux/termux.properties")
        }
    }

    fun load(context: Context, containerId: Int): TermuxProperties {
        val candidates = candidates(context, containerId)
        Log.d(TAG, "Searching termux.properties for container $containerId:")
        candidates.forEach { Log.d(TAG, "  ${it.absolutePath} exists=${it.isFile}") }
        val file = candidates.firstOrNull { it.isFile }
        if (file == null) {
            Log.w(TAG, "No termux.properties found for container $containerId")
            return TermuxProperties()
        }
        Log.i(TAG, "Loading from ${file.absolutePath}")

        val props = Properties()
        try {
            file.inputStream().use { props.load(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read $file", e)
            return TermuxProperties(sourceFile = file)
        }

        fun parseColor(raw: String?): Int? {
            if (raw == null) return null
            val hex = raw.trim().removePrefix("#").removePrefix("0x").trim()
            return try {
                when (hex.length) {
                    6 -> 0xFF000000.toInt() or hex.toLong(16).toInt()
                    8 -> hex.toLong(16).toInt()
                    else -> null
                }
            } catch (_: NumberFormatException) { null }
        }

        var background = parseColor(props.getProperty("background"))
        val opacityRaw = props.getProperty("background_opacity")?.trim()?.toFloatOrNull()
        if (opacityRaw != null) {
            val alpha = (opacityRaw.coerceIn(0f, 1f) * 255).toInt() and 0xFF
            val rgb = (background ?: 0xFF000000.toInt()) and 0x00FFFFFF
            background = (alpha shl 24) or rgb
        }

        val foreground = parseColor(props.getProperty("foreground"))
        val cursor = parseColor(props.getProperty("cursor"))

        val ansi = IntArray(16)
        var anyAnsi = false
        for (i in 0..15) {
            parseColor(props.getProperty("color$i"))?.let { ansi[i] = it; anyAnsi = true }
        }

        val rawBlink = props.getProperty("terminal-cursor-blink-rate")?.trim()?.toIntOrNull()
        val blinkRate = rawBlink?.let {
            when {
                it == 0 -> 0
                it in 100..2000 -> it
                it in 1..99 -> { Log.w(TAG, "blink $it too fast → 100"); 100 }
                it > 2000 -> { Log.w(TAG, "blink $it too slow → 2000"); 2000 }
                else -> null
            }
        }

        val rawExtra = props.getProperty("extra-keys")?.trim()?.takeIf { it.isNotEmpty() }
        val extraKeys = rawExtra?.let { normalizeExtraKeys(it) }

        val fontFile = File(file.parentFile ?: File("/"), "font.ttf")
        val fontPath = fontFile.takeIf { it.isFile }?.absolutePath

        return TermuxProperties(
            backgroundColor = background,
            foregroundColor = foreground,
            cursorColor = cursor,
            ansiColors = if (anyAnsi) ansi else null,
            cursorBlinkRate = blinkRate,
            fontPath = fontPath,
            extraKeys = extraKeys,
            sourceFile = file
        )
    }

    private fun normalizeExtraKeys(raw: String): String? {
        try { org.json.JSONArray(raw); return raw } catch (_: Exception) {}
        var s = raw.trim()
        s = Regex("""([\[,]\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*[,\]])""").replace(s) {
            "${it.groupValues[1]}\"${it.groupValues[2]}\"${it.groupValues[3]}"
        }
        s = Regex("""([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*:)""").replace(s) {
            "${it.groupValues[1]}\"${it.groupValues[2]}\"${it.groupValues[3]}"
        }
        s = s.replace('\'', '"')
        return try { org.json.JSONArray(s); s }
        catch (e: Exception) { Log.e(TAG, "extra-keys normalise failed", e); null }
    }

    fun apply(tv: com.termux.view.TerminalView, props: TermuxProperties): Boolean {
        val emulator = tv.mEmulator ?: return false
        val colors = emulator.mColors?.mCurrentColors ?: return false

        props.backgroundColor?.let { bg ->
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND] = bg
            tv.setBackgroundColor(bg)
            Log.i(TAG, "Applied background=#${Integer.toHexString(bg)} alpha=${(bg ushr 24) and 0xFF}")
        }
        props.foregroundColor?.let {
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND] = it
            Log.i(TAG, "Applied foreground=#${Integer.toHexString(it)}")
        }
        props.cursorColor?.let {
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR] = it
            Log.i(TAG, "Applied cursor=#${Integer.toHexString(it)}")
        }
        props.ansiColors?.let { pal ->
            for (i in 0..15) colors[i] = pal[i]
            Log.i(TAG, "Applied ANSI palette")
        }
        props.cursorBlinkRate?.let { rate ->
            val ok = tv.setTerminalCursorBlinkerRate(rate)
            tv.setTerminalCursorBlinkerState(true, true)
            Log.i(TAG, "Applied blink-rate=$rate (accepted=$ok)")
        }
        props.fontPath?.let { path ->
            try {
                tv.setTypeface(android.graphics.Typeface.createFromFile(path))
                Log.i(TAG, "Applied font=$path")
            } catch (e: Exception) { Log.e(TAG, "Font load failed", e) }
        }
        tv.invalidate()
        return true
    }

    fun seedNativeDefault(context: Context) {
        val file = File(context.filesDir, "home/.termux/termux.properties")
        if (file.exists()) return
        file.parentFile?.mkdirs()
        file.writeText(
            """
            # XoDos-Ark terminal properties (native shell)
            # background=#4D000000
            # foreground=#E9D5FF
            # cursor=#FFD700
            # terminal-cursor-blink-rate=500
            """.trimIndent()
        )
    }
}

object TermuxPropertiesWriter {
    fun save(file: File, values: Map<String, String?>) {
        val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
        for ((key, value) in values) {
            val idx = lines.indexOfFirst { line ->
                val t = line.trim()
                !t.startsWith("#") && (t.startsWith("$key=") || t.startsWith("$key "))
            }
            if (value.isNullOrBlank()) {
                if (idx >= 0) lines.removeAt(idx)
            } else {
                val newLine = "$key = $value"
                if (idx >= 0) lines[idx] = newLine else lines.add(newLine)
            }
        }
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n"))
    }
}