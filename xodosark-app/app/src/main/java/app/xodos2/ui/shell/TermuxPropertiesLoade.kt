package app.xodos2.ui.shell

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xodos2.ui.glass.GlassButton
import app.xodos2.ui.glassDialogStyle
import org.json.JSONArray

private const val DEFAULT_EXTRA_KEYS_JSON_EDITOR = """
[
  ["ESC", "/", "-", "HOME", "UP", "END", "PASTE"],
  ["TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN"]
]
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxPropertiesEditorDialog(
    context: Context,
    containerId: Int,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val initial = remember(containerId) { TermuxPropertiesLoader.load(context, containerId) }
    val targetFile = remember(containerId) {
        initial.sourceFile ?: TermuxPropertiesLoader.defaultFile(context, containerId)
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var bg by remember { mutableStateOf(initial.backgroundColor?.let { intToHex(it) } ?: "") }
    var fg by remember { mutableStateOf(initial.foregroundColor?.let { intToHex(it) } ?: "") }
    var cursor by remember { mutableStateOf(initial.cursorColor?.let { intToHex(it) } ?: "") }
    var blink by remember { mutableStateOf(initial.cursorBlinkRate?.toString() ?: "500") }
    var extraKeys by remember { mutableStateOf(initial.extraKeys ?: DEFAULT_EXTRA_KEYS_JSON_EDITOR) }
    var ansi by remember {
        mutableStateOf(
            (0..15).map { i ->
                initial.ansiColors?.getOrNull(i)?.let { intToHex(it) } ?: ""
            }
        )
    }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ComposeColor.Transparent,
        modifier = Modifier
            .glassDialogStyle()
            .fillMaxWidth(0.95f)
            .heightIn(max = 720.dp),
        title = {
            Text(
                "Terminal Properties",
                color = ComposeColor.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Simple tab bar without Material3 experimental API
                Row(modifier = Modifier.fillMaxWidth()) {
                    TabButton("Colors", selectedTab == 0) { selectedTab = 0 }
                    TabButton("Extra Keys", selectedTab == 1) { selectedTab = 1 }
                    TabButton("Advanced", selectedTab == 2) { selectedTab = 2 }
                }
                Spacer(Modifier.height(12.dp))

                when (selectedTab) {
                    0 -> ColorsTab(
                        bg = bg, onBg = { bg = it },
                        fg = fg, onFg = { fg = it },
                        cursor = cursor, onCursor = { cursor = it },
                        ansi = ansi, onAnsi = { ansi = it }
                    )
                    1 -> ExtraKeysTab(extraKeys) { extraKeys = it; error = null }
                    2 -> AdvancedTab(
                        blink = blink, onBlink = { blink = it },
                        fontPath = initial.fontPath,
                        filePath = targetFile.absolutePath
                    )
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = ComposeColor(0xFFFF6B6B),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            GlassButton(onClick = {
                try {
                    JSONArray(extraKeys)

                    val values = mutableMapOf<String, String?>()
                    values["background"] = bg.takeIf { it.isNotBlank() }
                    values["foreground"] = fg.takeIf { it.isNotBlank() }
                    values["cursor"] = cursor.takeIf { it.isNotBlank() }
                    values["terminal-cursor-blink-rate"] = blink.takeIf { it.isNotBlank() }
                    values["extra-keys"] = extraKeys.takeIf { it.isNotBlank() }
                    ansi.forEachIndexed { i, v -> values["color$i"] = v.takeIf { it.isNotBlank() } }

                    TermuxPropertiesWriter.save(targetFile, values)
                    onSaved()
                } catch (e: Exception) {
                    error = "Invalid input: ${e.message}"
                }
            }) {
                Text("Save", color = ComposeColor(0xFFC3B6F9), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            GlassButton(onClick = onDismiss) {
                Text("Cancel", color = ComposeColor.White.copy(alpha = 0.8f))
            }
        }
    )
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .background(
                color = if (selected) ComposeColor(0xFF7C3AED).copy(alpha = 0.4f)
                        else ComposeColor.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        Text(
            label,
            color = if (selected) ComposeColor.White else ComposeColor.White.copy(alpha = 0.6f),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ColorsTab(
    bg: String, onBg: (String) -> Unit,
    fg: String, onFg: (String) -> Unit,
    cursor: String, onCursor: (String) -> Unit,
    ansi: List<String>, onAnsi: (List<String>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState())
    ) {
        ColorRow("Background", bg, onBg)
        ColorRow("Foreground", fg, onFg)
        ColorRow("Cursor", cursor, onCursor)
        Spacer(Modifier.height(12.dp))
        Text("ANSI Palette", color = ComposeColor.White, fontWeight = FontWeight.SemiBold)
        for (i in 0..15) {
            ColorRow("color$i", ansi[i]) { newVal ->
                onAnsi(ansi.toMutableList().also { it[i] = newVal })
            }
        }
    }
}

@Composable
private fun ColorRow(label: String, value: String, onChange: (String) -> Unit) {
    val preview = remember(value) { parseColorPreview(value) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, color = ComposeColor.White.copy(alpha = 0.7f)) },
        placeholder = {
            Text("#RRGGBB or #AARRGGBB", color = ComposeColor.White.copy(alpha = 0.3f))
        },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = ComposeColor.White,
            unfocusedTextColor = ComposeColor.White,
            focusedBorderColor = ComposeColor(0xFFC3B6F9),
            unfocusedBorderColor = ComposeColor.White.copy(alpha = 0.2f),
            cursorColor = ComposeColor(0xFFC3B6F9),
            focusedContainerColor = ComposeColor.Black.copy(alpha = 0.2f),
            unfocusedContainerColor = ComposeColor.Black.copy(alpha = 0.2f)
        ),
        trailingIcon = {
            if (preview != null) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(ComposeColor(preview), RoundedCornerShape(4.dp))
                        .border(
                            1.dp,
                            ComposeColor.White.copy(alpha = 0.4f),
                            RoundedCornerShape(4.dp)
                        )
                )
            }
        }
    )
}

@Composable
private fun ExtraKeysTab(value: String, onChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = ComposeColor.White,
                unfocusedTextColor = ComposeColor.White,
                focusedBorderColor = ComposeColor(0xFFC3B6F9),
                unfocusedBorderColor = ComposeColor.White.copy(alpha = 0.2f),
                cursorColor = ComposeColor(0xFFC3B6F9),
                focusedContainerColor = ComposeColor.Black.copy(alpha = 0.2f),
                unfocusedContainerColor = ComposeColor.Black.copy(alpha = 0.2f)
            )
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Strings or {\"key\":\"…\",\"macro\":\"…\",\"display\":\"…\"}. Must be valid 2D JSON.",
            color = ComposeColor.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun AdvancedTab(
    blink: String, onBlink: (String) -> Unit,
    fontPath: String?,
    filePath: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState())
    ) {
        OutlinedTextField(
            value = blink,
            onValueChange = onBlink,
            label = {
                Text(
                    "Cursor blink rate (0 or 100–2000)",
                    color = ComposeColor.White.copy(alpha = 0.7f)
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = ComposeColor.White,
                unfocusedTextColor = ComposeColor.White,
                focusedBorderColor = ComposeColor(0xFFC3B6F9),
                unfocusedBorderColor = ComposeColor.White.copy(alpha = 0.2f),
                cursorColor = ComposeColor(0xFFC3B6F9),
                focusedContainerColor = ComposeColor.Black.copy(alpha = 0.2f),
                unfocusedContainerColor = ComposeColor.Black.copy(alpha = 0.2f)
            )
        )
        Spacer(Modifier.height(16.dp))
        Text("Font", color = ComposeColor.White, fontWeight = FontWeight.SemiBold)
        Text(
            fontPath ?: "default (place font.ttf next to termux.properties)",
            color = ComposeColor.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))
        Text("Config file", color = ComposeColor.White, fontWeight = FontWeight.SemiBold)
        Text(filePath, color = ComposeColor(0xFFC3B6F9), fontSize = 11.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "Tip: background=#AARRGGBB — first two hex digits are alpha " +
                    "(e.g. #4D000000 = 30 % black).",
            color = ComposeColor.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun intToHex(v: Int): String = "#%08X".format(v)

private fun parseColorPreview(raw: String): Int? {
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