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
    val backgroundImagePath: String? = null,
    val backgroundImageOverlayColor: Int? = null,
    /** "x11", "wayland", or null (off). */
    val backgroundDisplay: String? = null,
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
        val searchPaths = candidates(context, containerId)
        for (p in searchPaths) Log.d(TAG, "  ${p.absolutePath} exists=${p.isFile}")

        val file = searchPaths.firstOrNull { it.isFile } ?: run {
            Log.w(TAG, "No termux.properties found for container $containerId")
            return TermuxProperties()
        }

        val props = Properties()
        try {
            file.reader(Charsets.UTF_8).use { props.load(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read $file", e)
            return TermuxProperties(sourceFile = file)
        }

        var background = parseColor(props.getProperty("background"))
        val opacityRaw = props.getProperty("background_opacity")?.trim()?.toFloatOrNull()
        if (opacityRaw != null) {
            val alpha = (opacityRaw.coerceIn(0f, 1f) * 255).toInt() and 0xFF
            val rgb = (background ?: 0xFF000000.toInt()) and 0x00FFFFFF
            background = (alpha shl 24) or rgb
        }

        val foreground = parseColor(props.getProperty("foreground"))
        val cursorColor = parseColor(props.getProperty("cursor"))

        val ansi = IntArray(16) { -1 }
        var anyAnsi = false
        for (i in 0..15) {
            val c = parseColor(props.getProperty("color$i"))
            if (c != null) { ansi[i] = c; anyAnsi = true }
        }

        val rawBlink = props.getProperty("terminal-cursor-blink-rate")?.trim()?.toIntOrNull()
        val blinkRate: Int? = when {
            rawBlink == null -> null
            rawBlink == 0 -> 0
            rawBlink in 100..2000 -> rawBlink
            rawBlink in 1..99 -> { Log.w(TAG, "blink too fast → 100"); 100 }
            rawBlink > 2000 -> { Log.w(TAG, "blink too slow → 2000"); 2000 }
            else -> null
        }

        val rawExtra = props.getProperty("extra-keys")?.trim()?.takeIf { it.isNotEmpty() }
        val extraKeys = rawExtra?.let { normalizeExtraKeys(it) }

        val fontFile = File(file.parentFile ?: File("/"), "font.ttf")
        val fontPath = if (fontFile.isFile) fontFile.absolutePath else null

        val rawImagePath = props.getProperty("background_image")?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedImagePath = rawImagePath?.let { p ->
            if (p.startsWith("/")) p else File(context.filesDir, p).absolutePath
        }?.takeIf { File(it).isFile }

        val overlayColor = parseColor(props.getProperty("background_image_overlay"))

        // ── Background display (x11 / wayland) ──
        val bgDisplay = props.getProperty("background_display")
            ?.trim()?.lowercase()
            ?.takeIf { it == "x11" || it == "wayland" }

        return TermuxProperties(
            backgroundColor = background,
            foregroundColor = foreground,
            cursorColor = cursorColor,
            ansiColors = if (anyAnsi) ansi else null,
            cursorBlinkRate = blinkRate,
            fontPath = fontPath,
            extraKeys = extraKeys,
            backgroundImagePath = resolvedImagePath,
            backgroundImageOverlayColor = overlayColor,
            backgroundDisplay = bgDisplay,
            sourceFile = file
        )
    }

    private fun parseColor(raw: String?): Int? {
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

    private fun normalizeExtraKeys(raw: String): String? {
        try { org.json.JSONArray(raw); return raw } catch (_: Exception) { }
        var s = raw.trim()
        s = Regex("""([\[,]\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*[,\]])""").replace(s) { m ->
            "${m.groupValues[1]}\"${m.groupValues[2]}\"${m.groupValues[3]}"
        }
        s = Regex("""([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*:)""").replace(s) { m ->
            "${m.groupValues[1]}\"${m.groupValues[2]}\"${m.groupValues[3]}"
        }
        s = s.replace('\'', '"')
        return try { org.json.JSONArray(s); s } catch (e: Exception) {
            Log.e(TAG, "extra-keys normalise failed: $raw", e); null
        }
    }

    fun apply(tv: com.termux.view.TerminalView, props: TermuxProperties): Boolean {
        val emulator = tv.mEmulator ?: return false
        val colors = emulator.mColors?.mCurrentColors ?: return false

        props.backgroundColor?.let { bg ->
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND] = bg
            tv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        props.foregroundColor?.let { fg ->
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND] = fg
        }
        props.cursorColor?.let { cur ->
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR] = cur
        }
        props.ansiColors?.let { pal ->
            for (i in 0..15) if (pal[i] != -1) colors[i] = pal[i]
        }
        props.cursorBlinkRate?.let { rate ->
            val ok = tv.setTerminalCursorBlinkerRate(rate)
            tv.setTerminalCursorBlinkerState(true, true)
        }
        props.fontPath?.let { path ->
            try { tv.setTypeface(android.graphics.Typeface.createFromFile(path)) }
            catch (e: Exception) { Log.e(TAG, "Font load failed", e) }
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
            # background_image=terminal_background.png
            # background_display=wayland
            """.trimIndent()
        )
    }
}

object TermuxPropertiesWriter {

    private fun escapeForProperties(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    fun save(file: File, values: Map<String, String?>) {
        val lines: MutableList<String> =
            if (file.exists()) file.readLines().toMutableList() else mutableListOf()

        for ((key, value) in values) {
            val idx = lines.indexOfFirst { line ->
                val t = line.trim()
                !t.startsWith("#") && (t.startsWith("$key=") || t.startsWith("$key "))
            }
            if (value.isNullOrBlank()) {
                if (idx >= 0) lines.removeAt(idx)
            } else {
                val newLine = "$key = ${escapeForProperties(value)}"
                if (idx >= 0) lines[idx] = newLine else lines.add(newLine)
            }
        }

        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
    }
}