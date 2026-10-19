package app.xodos2.ui.shell

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xodos2.ui.glass.GlassButton
import app.xodos2.ui.glassDialogStyle
import org.json.JSONArray
import java.io.File

private val EDITOR_DEFAULT_EXTRA_KEYS = """
[
  ["ESC", "/", "-", "HOME", "UP", "END", "PASTE"],
  ["TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN"]
]
""".trimIndent()

private data class ColorField(
    val label: String,
    val key: String,
    val value: String,
    val onChange: (String) -> Unit
)

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
    var extraKeys by remember { mutableStateOf(initial.extraKeys ?: EDITOR_DEFAULT_EXTRA_KEYS) }
    var ansi by remember {
        mutableStateOf(
            (0..15).map { i ->
                val v = initial.ansiColors?.getOrNull(i) ?: -1
                if (v == -1) "" else intToHex(v)
            }
        )
    }
    var backgroundImagePath by remember { mutableStateOf(initial.backgroundImagePath ?: "") }
    var editingColor by remember { mutableStateOf<ColorField?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Image picker — auto-sets background alpha to 59 (~23%) so it acts as a
    // subtle tint over the image for readability. The user can override the
    // alpha later in the Colors tab (e.g. set to 0 for a pure image).
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
                val ext = when (mime) {
                    "image/gif"  -> "gif"
                    "image/webp" -> "webp"
                    "image/jpeg" -> "jpg"
                    "image/png"  -> "png"
                    "image/apng" -> "apng"
                    else -> uri.lastPathSegment.orEmpty()
                        .substringAfterLast('.', "img").lowercase()
                }
                listOf("png", "gif", "webp", "jpg", "apng", "img").forEach { e ->
                    File(context.filesDir, "terminal_background.$e").delete()
                }
                val dest = File(context.filesDir, "terminal_background.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                backgroundImagePath = dest.absolutePath
                // Auto-tint: 0x3B = 59/255 ≈ 23 % opacity
                bg = withAlpha(bg, 0x3B)
            } catch (e: Exception) {
                error = "Failed to copy image: ${e.message}"
            }
        }
    }

    val allColorFields = remember(bg, fg, cursor, ansi) {
        listOf(
            ColorField("Background", "background", bg) { bg = it },
            ColorField("Foreground", "foreground", fg) { fg = it },
            ColorField("Cursor",     "cursor",     cursor) { cursor = it },
            ColorField("color0",  "color0",  ansi[0])  { ansi = ansi.toMutableList().also { l -> l[0]  = it } },
            ColorField("color1",  "color1",  ansi[1])  { ansi = ansi.toMutableList().also { l -> l[1]  = it } },
            ColorField("color2",  "color2",  ansi[2])  { ansi = ansi.toMutableList().also { l -> l[2]  = it } },
            ColorField("color3",  "color3",  ansi[3])  { ansi = ansi.toMutableList().also { l -> l[3]  = it } },
            ColorField("color4",  "color4",  ansi[4])  { ansi = ansi.toMutableList().also { l -> l[4]  = it } },
            ColorField("color5",  "color5",  ansi[5])  { ansi = ansi.toMutableList().also { l -> l[5]  = it } },
            ColorField("color6",  "color6",  ansi[6])  { ansi = ansi.toMutableList().also { l -> l[6]  = it } },
            ColorField("color7",  "color7",  ansi[7])  { ansi = ansi.toMutableList().also { l -> l[7]  = it } },
            ColorField("color8",  "color8",  ansi[8])  { ansi = ansi.toMutableList().also { l -> l[8]  = it } },
            ColorField("color9",  "color9",  ansi[9])  { ansi = ansi.toMutableList().also { l -> l[9]  = it } },
            ColorField("color10", "color10", ansi[10]) { ansi = ansi.toMutableList().also { l -> l[10] = it } },
            ColorField("color11", "color11", ansi[11]) { ansi = ansi.toMutableList().also { l -> l[11] = it } },
            ColorField("color12", "color12", ansi[12]) { ansi = ansi.toMutableList().also { l -> l[12] = it } },
            ColorField("color13", "color13", ansi[13]) { ansi = ansi.toMutableList().also { l -> l[13] = it } },
            ColorField("color14", "color14", ansi[14]) { ansi = ansi.toMutableList().also { l -> l[14] = it } },
            ColorField("color15", "color15", ansi[15]) { ansi = ansi.toMutableList().also { l -> l[15] = it } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ComposeColor.Transparent,
        modifier = Modifier
            .glassDialogStyle()
            .fillMaxWidth(0.95f)
            .heightIn(max = 760.dp),
        title = {
            Text(
                "Terminal Properties",
                color = ComposeColor.White,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    EditorTabButton("Colors",   selectedTab == 0) { selectedTab = 0 }
                    EditorTabButton("Keys",     selectedTab == 1) { selectedTab = 1 }
                    EditorTabButton("Image",    selectedTab == 2) { selectedTab = 2 }
                    EditorTabButton("Advanced", selectedTab == 3) { selectedTab = 3 }
                }
                Spacer(Modifier.height(12.dp))

                when (selectedTab) {
                    0 -> ColorsTabContent(allColorFields) { editingColor = it }
                    1 -> ExtraKeysTab(
                        value = extraKeys,
                        onChange = { extraKeys = it; error = null },
                        onReset = { extraKeys = EDITOR_DEFAULT_EXTRA_KEYS; error = null }
                    )
                    2 -> ImageTab(
                        imagePath = backgroundImagePath,
                        onPickImage = { imagePicker.launch(arrayOf("image/*")) },
                        onRemoveImage = {
                            backgroundImagePath = ""
                            listOf("png", "gif", "webp", "jpg", "apng", "img").forEach { e ->
                                File(context.filesDir, "terminal_background.$e").delete()
                            }
                        }
                    )
                    else -> AdvancedTab(blink, { blink = it }, targetFile.absolutePath)
                }

                error?.let { msg ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        msg,
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
                    values["cursor"]     = cursor.takeIf { it.isNotBlank() }
                    values["terminal-cursor-blink-rate"] = blink.takeIf { it.isNotBlank() }
                    values["extra-keys"] = extraKeys.takeIf { it.isNotBlank() }
                    ansi.forEachIndexed { i, v -> values["color$i"] = v.takeIf { it.isNotBlank() } }
                    values["background_image"] = backgroundImagePath.takeIf { it.isNotBlank() }

                    // Overlay removed — the background color's alpha now does
                    // the tinting over the image. Clean up any legacy value.
                    values["background_image_overlay"] = null

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

    editingColor?.let { field ->
        ColorPickerDialog(
            title = field.label,
            initialHex = field.value.ifBlank { "#FF000000" },
            onDismiss = { editingColor = null },
            onColorSelected = { hex ->
                field.onChange(hex)
                editingColor = null
            }
        )
    }
}

@Composable
private fun EditorTabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 2.dp)
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
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ColorsTabContent(fields: List<ColorField>, onEdit: (ColorField) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
    ) {
        for (field in fields) {
            ColorRow(field) { onEdit(field) }
        }
    }
}

@Composable
private fun ColorRow(field: ColorField, onClick: () -> Unit) {
    val preview = remember(field.value) { parseColor(field.value) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() }
            .background(ComposeColor.White.copy(alpha = 0.03f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    preview?.let { ComposeColor(it) } ?: ComposeColor.Transparent,
                    RoundedCornerShape(8.dp)
                )
                .border(1.dp, ComposeColor.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                field.label,
                color = ComposeColor.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (field.value.isBlank()) "not set" else field.value,
                color = ComposeColor.White.copy(alpha = 0.55f),
                fontSize = 11.sp
            )
        }
        Text(
            "Edit",
            color = ComposeColor(0xFFC3B6F9),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ExtraKeysTab(
    value: String,
    onChange: (String) -> Unit,
    onReset: () -> Unit
) {
    var jsonValid by remember(value) {
        mutableStateOf(
            try { JSONArray(value); true } catch (_: Exception) { false }
        )
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassButton(onClick = onReset) {
                Text(
                    "Reset to Default",
                    color = ComposeColor(0xFFFF6B6B),
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                if (jsonValid) "✓ Valid" else "✗ Invalid",
                color = if (jsonValid) ComposeColor(0xFF10B981) else ComposeColor(0xFFFF6B6B),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState())
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    onChange(it)
                    jsonValid = try { JSONArray(it); true } catch (_: Exception) { false }
                },
                modifier = Modifier.fillMaxWidth().height(280.dp),
                isError = !jsonValid,
                colors = editorTextFieldColors()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Strings or {\"key\":\"…\",\"macro\":\"…\",\"display\":\"…\",\"meta\":\"CTRL\"}. " +
                        "Must be valid 2D JSON. Use \\n for newline, not a real line break.",
                color = ComposeColor.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Default layout:",
                color = ComposeColor.White.copy(alpha = 0.55f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                EDITOR_DEFAULT_EXTRA_KEYS,
                color = ComposeColor(0xFFC3B6F9),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun ImageTab(
    imagePath: String,
    onPickImage: () -> Unit,
    onRemoveImage: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Background image", color = ComposeColor.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        if (imagePath.isNotBlank() && File(imagePath).exists()) {
            Text(imagePath, color = ComposeColor.White.copy(alpha = 0.6f), fontSize = 11.sp)
        } else {
            Text(
                "No image selected",
                color = ComposeColor.White.copy(alpha = 0.6f),
                fontSize = 13.sp
            )
        }
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(onClick = onPickImage) {
                Text(
                    "Choose Image",
                    color = ComposeColor(0xFFC3B6F9),
                    fontWeight = FontWeight.Bold
                )
            }
            if (imagePath.isNotBlank()) {
                GlassButton(onClick = onRemoveImage) {
                    Text("Remove", color = ComposeColor(0xFFFF6B6B))
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Text(
            "Supported formats",
            color = ComposeColor.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "PNG, JPG — static images\n" +
            "GIF — animated (all Android versions)\n" +
            "WebP, APNG — animated (Android 9+)",
            color = ComposeColor.White.copy(alpha = 0.6f),
            fontSize = 11.sp
        )

        Spacer(Modifier.height(20.dp))

        Text(
            "About the tint",
            color = ComposeColor.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Picking an image automatically sets the background color's alpha " +
            "to 59 (~23 %) so it acts as a subtle tint for readability.\n\n" +
            "Change it in the Colors tab — set alpha to 0 for a pure, untinted image, " +
            "or raise it for a darker overlay.",
            color = ComposeColor.White.copy(alpha = 0.6f),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun AdvancedTab(blink: String, onBlink: (String) -> Unit, filePath: String) {
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
            colors = editorTextFieldColors()
        )
        Spacer(Modifier.height(16.dp))
        Text("Config file", color = ComposeColor.White, fontWeight = FontWeight.SemiBold)
        Text(filePath, color = ComposeColor(0xFFC3B6F9), fontSize = 11.sp)
        Spacer(Modifier.height(16.dp))
        Text(
            "Colors use #AARRGGBB. First two hex digits are alpha (00 = transparent, FF = opaque).\n" +
                    "Setting only foreground/cursor will NOT clear the ANSI palette — bash colors stay intact.",
            color = ComposeColor.White.copy(alpha = 0.55f),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun editorTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = ComposeColor.White,
    unfocusedTextColor = ComposeColor.White,
    focusedBorderColor = ComposeColor(0xFFC3B6F9),
    unfocusedBorderColor = ComposeColor.White.copy(alpha = 0.2f),
    cursorColor = ComposeColor(0xFFC3B6F9),
    focusedContainerColor = ComposeColor.Black.copy(alpha = 0.2f),
    unfocusedContainerColor = ComposeColor.Black.copy(alpha = 0.2f)
)

private fun intToHex(v: Int): String = "#%08X".format(v)

private fun parseColor(raw: String): Int? {
    val hex = raw.trim().removePrefix("#").removePrefix("0x")
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

/** Replaces the alpha byte of [currentHex] with [newAlpha] (0–255), keeping RGB. */
private fun withAlpha(currentHex: String, newAlpha: Int): String {
    val clean = currentHex.trim().removePrefix("#").removePrefix("0x").uppercase()
    val rgb = when (clean.length) {
        8 -> clean.substring(2)   // strip existing AA
        6 -> clean
        else -> "000000"          // default black if empty
    }
    return "#%02X%s".format(newAlpha.coerceIn(0, 255), rgb)
}