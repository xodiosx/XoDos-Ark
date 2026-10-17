package app.xodos2.ui.shell

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Properties

/**
 * Parsed subset of Termux's ~/.termux/termux.properties that we actually use.
 * This mirrors the properties Termux's app layer reads; TerminalView itself
 * never parses this file, so we do it here.
 */
data class TermuxProperties(
    val backgroundColor: Int? = null,
    val foregroundColor: Int? = null,
    val cursorColor: Int? = null,
    val ansiColors: IntArray? = null,          // 16 entries: color0..color15
    val cursorBlinkRate: Int? = null,
    val fontPath: String? = null,              // resolved path to font.ttf
    val extraKeys: String? = null,             // raw JSON 2D array (optional)
    val marginHorizontal: Int? = null,
    val marginVertical: Int? = null,
    val sourceFile: File? = null
)

object TermuxPropertiesLoader {
    private const val TAG = "TermuxProps"

    /**
     * Load from <container>/root/.termux/termux.properties first,
     * falling back to <filesDir>/termux.properties.
     */
    fun load(context: Context, containerId: Int): TermuxProperties {
        val candidates = listOf(
            File(context.filesDir, "containers/$containerId/root/.termux/termux.properties"),
            File(context.filesDir, "termux.properties")
        )
        val file = candidates.firstOrNull { it.isFile } ?: return TermuxProperties()

        val props = Properties()
        try {
            file.inputStream().use { props.load(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read $file", e)
            return TermuxProperties(sourceFile = file)
        }

        fun color(key: String): Int? = props.getProperty(key)?.let { parseColor(it) }
        fun intProp(key: String): Int? = props.getProperty(key)?.trim()?.toIntOrNull()

        // ANSI palette: only apply if the user set at least one colorN
        val ansi = IntArray(16) { i -> color("color$i") ?: -1 }
        val hasAnyAnsi = ansi.any { it != -1 }

        // Font file lives alongside termux.properties
        val fontFile = File(file.parentFile ?: file.parentFile ?: return TermuxProperties(sourceFile = file), "font.ttf")

        return TermuxProperties(
            backgroundColor = color("background"),
            foregroundColor = color("foreground"),
            cursorColor = color("cursor"),
            ansiColors = if (hasAnyAnsi) ansi.map { if (it == -1) 0 else it }.toIntArray() else null,
            cursorBlinkRate = intProp("terminal-cursor-blink-rate"),
            fontPath = fontFile.takeIf { it.isFile }?.absolutePath,
            extraKeys = props.getProperty("extra-keys")?.trim()?.takeIf { it.isNotEmpty() },
            marginHorizontal = intProp("terminal-margin-horizontal"),
            marginVertical = intProp("terminal-margin-vertical"),
            sourceFile = file
        )
    }

    private fun parseColor(raw: String): Int? {
        val hex = raw.trim().removePrefix("#").trim()
        return try {
            when (hex.length) {
                6 -> 0xFF000000.toInt() or hex.toLong(16).toInt()
                8 -> hex.toLong(16).toInt()
                else -> null
            }
        } catch (_: NumberFormatException) { null }
    }

    /**
     * Ensures a property is present and NOT commented-out in the given file.
     * If the line exists but is commented (`# background=...`), it is uncommented.
     * If it does not exist at all, it is appended.
     *
     * Call this during installation if you want to force-enable defaults.
     */
    fun ensurePropertyEnabled(file: File, key: String, defaultLine: String) {
        try {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText("# Created by XoDos-Ark\n$defaultLine\n")
                return
            }
            val lines = file.readLines().toMutableList()
            if (lines.any { it.trim().startsWith("$key=") }) return  // already active

            val idx = lines.indexOfFirst {
                val t = it.trim()
                t == "# $defaultLine" || t == "#$defaultLine"
            }
            if (idx >= 0) lines[idx] = defaultLine else lines.add(defaultLine)
            file.writeText(lines.joinToString("\n"))
        } catch (e: Exception) {
            Log.w(TAG, "ensurePropertyEnabled failed for $key", e)
        }
    }

    /**
     * Applies a [TermuxProperties] object to an already-attached TerminalView/emulator.
     * Safe to call multiple times; only properties that are non-null are applied.
     */
    fun apply(
        tv: com.termux.view.TerminalView,
        props: TermuxProperties,
        defaultTextSizePx: Int = 0
    ) {
        val emulator = tv.mEmulator ?: return
        val colors = emulator.mColors?.mCurrentColors ?: return

        props.backgroundColor?.let { bg ->
            // Paints the view; default-background cells are transparent so the view bg shows through.
            tv.setBackgroundColor(bg)
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_BACKGROUND] = bg
        }
        props.foregroundColor?.let {
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_FOREGROUND] = it
        }
        props.cursorColor?.let {
            colors[com.termux.terminal.TextStyle.COLOR_INDEX_CURSOR] = it
        }
        props.ansiColors?.let { pal ->
            for (i in 0..15) colors[i] = pal[i]
        }
        props.cursorBlinkRate?.let { rate ->
            try {
                tv.setTerminalCursorBlinkerRate(rate)
                tv.setTerminalCursorBlinkerState(true, true)
            } catch (_: Exception) {}
        }
        props.fontPath?.let { path ->
            try {
                tv.setTypeface(android.graphics.Typeface.createFromFile(path))
            } catch (_: Exception) {}
        }
        tv.invalidate()
    }

    /**
     * Seed a starter termux.properties for a freshly installed container.
     * Call this after install if you want users to have something to edit.
     */
    fun seedDefaults(context: Context, containerId: Int) {
        val dir = File(context.filesDir, "containers/$containerId/root/.termux")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "termux.properties")
        if (!file.exists()) {
            file.writeText(
                """
                # XoDos-Ark terminal properties
                # background=#0A0A12
                # foreground=#E9D5FF
                # cursor=#FFD700
                # terminal-cursor-blink-rate=500
                # terminal-margin-horizontal=3
                # terminal-margin-vertical=0
                """.trimIndent()
            )
        }
    }
}