package app.xodos2.ui.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Properties

data class TermuxProperties(
    val backgroundColor: Int? = null,
    val foregroundColor: Int? = null,
    val cursorColor: Int? = null,
    val ansiColors: IntArray? = null,                 // -1 = not set (skip on apply)
    val cursorBlinkRate: Int? = null,
    val fontPath: String? = null,
    val extraKeys: String? = null,
    val backgroundImagePath: String? = null,
    val backgroundImageOverlayColor: Int? = null,
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
        Log.d(TAG, "Searching termux.properties for container $containerId:")
        for (p in searchPaths) Log.d(TAG, "  ${p.absolutePath} exists=${p.isFile}")

        val file = searchPaths.firstOrNull { it.isFile }
        if (file == null) {
            Log.w(TAG, "No termux.properties found for container $containerId")
            return TermuxProperties()
        }
        Log.i(TAG, "Loading from ${file.absolutePath}")

        val props = Properties()
        try {
            file.reader(Charsets.UTF_8).use { props.load(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read $file", e)
            return TermuxProperties(sourceFile = file)
        }

        // ── Background (optional background_opacity blends into alpha) ──
        var background = parseColor(props.getProperty("background"))
        val opacityRaw = props.getProperty("background_opacity")?.trim()?.toFloatOrNull()
        if (opacityRaw != null) {
            val alpha = (opacityRaw.coerceIn(0f, 1f) * 255).toInt() and 0xFF
            val rgb = (background ?: 0xFF000000.toInt()) and 0x00FFFFFF
            background = (alpha shl 24) or rgb
            Log.d(TAG, "background_opacity=$opacityRaw → background=#${Integer.toHexString(background)}")
        }

        val foreground = parseColor(props.getProperty("foreground"))
        val cursorColor = parseColor(props.getProperty("cursor"))

        // ── ANSI palette: use -1 for "not set" so we never blank default colors ──
        val ansi = IntArray(16) { -1 }
        var anyAnsi = false
        for (i in 0..15) {
            val c = parseColor(props.getProperty("color$i"))
            if (c != null) {
                ansi[i] = c
                anyAnsi = true
            }
        }

        // ── Blink rate (clamp bad values) ──
        val rawBlink = props.getProperty("terminal-cursor-blink-rate")?.trim()?.toIntOrNull()
        val blinkRate: Int? = when {
            rawBlink == null -> null
            rawBlink == 0 -> 0
            rawBlink in 100..2000 -> rawBlink
            rawBlink in 1..99 -> { Log.w(TAG, "blink $rawBlink too fast → 100"); 100 }
            rawBlink > 2000 -> { Log.w(TAG, "blink $rawBlink too slow → 2000"); 2000 }
            else -> null
        }

        // ── Extra keys (accept Termux relaxed syntax → normalized JSON) ──
        val rawExtra = props.getProperty("extra-keys")?.trim()?.takeIf { it.isNotEmpty() }
        val extraKeys = rawExtra?.let { normalizeExtraKeys(it) }

        // ── Font ──
        val fontFile = File(file.parentFile ?: File("/"), "font.ttf")
        val fontPath = if (fontFile.isFile) fontFile.absolutePath else null

        // ── Background image (relative path resolves against filesDir) ──
        val rawImagePath = props.getProperty("background_image")?.trim()?.takeIf { it.isNotEmpty() }
        val resolvedImagePath = rawImagePath?.let { p ->
            if (p.startsWith("/")) p else File(context.filesDir, p).absolutePath
        }?.takeIf { File(it).isFile }

        val overlayColor = parseColor(props.getProperty("background_image_overlay"))

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
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun normalizeExtraKeys(raw: String): String? {
        // Fast path — already valid JSON
        try {
            org.json.JSONArray(raw)
            return raw
        } catch (_: Exception) { /* fall through to normalise */ }

        var s = raw.trim()
        // Quote bare identifiers between [ , ] boundaries
        s = Regex("""([\[,]\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*[,\]])""").replace(s) { m ->
            "${m.groupValues[1]}\"${m.groupValues[2]}\"${m.groupValues[3]}"
        }
        // Quote object keys
        s = Regex("""([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*:)""").replace(s) { m ->
            "${m.groupValues[1]}\"${m.groupValues[2]}\"${m.groupValues[3]}"
        }
        // Single → double quotes
        s = s.replace('\'', '"')

        return try {
            org.json.JSONArray(s)
            s
        } catch (e: Exception) {
            Log.e(TAG, "extra-keys normalise failed: $raw", e)
            null
        }
    }

    fun apply(tv: com.termux.view.TerminalView, props: TermuxProperties): Boolean {
        val emulator = tv.mEmulator ?: return false
        val colors = emulator.mColors?.mCurrentColors ?: return false

        props.backgroundColor?.let { bg ->
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND] = bg
            // View stays fully transparent — the color is drawn by terminalFrame
            // in ShellScreen so the wallpaper / image can show through if alpha < 255
            tv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            Log.i(TAG, "Applied background=#${Integer.toHexString(bg)} alpha=${(bg ushr 24) and 0xFF}")
        }
        props.foregroundColor?.let { fg ->
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND] = fg
            Log.i(TAG, "Applied foreground=#${Integer.toHexString(fg)}")
        }
        props.cursorColor?.let { cur ->
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR] = cur
            Log.i(TAG, "Applied cursor=#${Integer.toHexString(cur)}")
        }
        props.ansiColors?.let { pal ->
            // Only override slots the user actually set (-1 = leave default alone)
            for (i in 0..15) if (pal[i] != -1) colors[i] = pal[i]
            Log.i(TAG, "Applied ANSI palette (partial)")
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
            } catch (e: Exception) {
                Log.e(TAG, "Font load failed", e)
            }
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
            # background_image_overlay=#80000000
            """.trimIndent()
        )
    }
}

object TermuxPropertiesWriter {

    /**
     * Escape a value so java.util.Properties.load() reads it back verbatim.
     * ORDER MATTERS: backslash first, then newline/tab/cr.
     *
     * Without this, a value like  {"macro": "kill -9 -1\n"}
     * would become  {"macro": "kill -9 -1<REAL NEWLINE>"}  on reload
     * → invalid JSON → extra keys silently fail to update.
     */
    private fun escapeForProperties(value: String): String =
        value
            .replace("\\", "\\\\")   // MUST be first
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