package app.xodos2.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.xodos2.ui.glass.GlassButton
import app.xodos2.ui.glassDialogStyle

@Composable
fun ColorPickerDialog(
    title: String,
    initialHex: String,
    onDismiss: () -> Unit,
    onColorSelected: (hex: String) -> Unit
) {
    var hexInput by remember(initialHex) {
        mutableStateOf(normalizeHexInput(initialHex).take(6))
    }
    var alpha by remember(initialHex) {
        mutableIntStateOf(parseAlpha(initialHex))
    }
    var error by remember { mutableStateOf<String?>(null) }

    val rgb = remember(hexInput) { parseRgbHex(hexInput) }
    val preview = rgb?.let { (alpha shl 24) or it }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ComposeColor.Transparent,
        modifier = Modifier.glassDialogStyle().fillMaxWidth(0.92f),
        title = {
            Text(title, color = ComposeColor.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .background(
                            preview?.let { ComposeColor(it) } ?: ComposeColor.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .border(
                            1.dp,
                            ComposeColor.White.copy(alpha = 0.3f),
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    Text(
                        preview?.let {
                            "Alpha ${(alpha * 100 / 255)}%  •  #${Integer.toHexString(it).uppercase()}"
                        } ?: "Enter a valid color",
                        color = ComposeColor.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(
                                ComposeColor.Black.copy(alpha = 0.5f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = hexInput,
                    onValueChange = { v ->
                        hexInput = v.uppercase().filter { it in "0123456789ABCDEF" }.take(6)
                        error = null
                    },
                    label = {
                        Text("HEX (RRGGBB)", color = ComposeColor.White.copy(alpha = 0.7f))
                    },
                    prefix = { Text("#", color = ComposeColor.White.copy(alpha = 0.5f)) },
                    singleLine = true,
                    isError = rgb == null && hexInput.isNotEmpty(),
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

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Alpha",
                        color = ComposeColor.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${(alpha * 100 / 255)}%  (${alpha})",
                        color = ComposeColor(0xFFC3B6F9),
                        fontSize = 12.sp
                    )
                }
                Slider(
                    value = alpha.toFloat(),
                    onValueChange = { alpha = it.toInt().coerceIn(0, 255) },
                    valueRange = 0f..255f,
                    colors = SliderDefaults.colors(
                        thumbColor = ComposeColor(0xFFC3B6F9),
                        activeTrackColor = ComposeColor(0xFFC3B6F9),
                        inactiveTrackColor = ComposeColor.White.copy(alpha = 0.15f)
                    )
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    "Presets",
                    color = ComposeColor.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))

                for (row in 0 until PRESET_COLORS.size / 8) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (col in 0 until 8) {
                            val idx = row * 8 + col
                            val rgbInt = PRESET_COLORS[idx]
                            val isSelected = rgb == rgbInt
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .background(
                                        ComposeColor(0xFF000000.toInt() or rgbInt),
                                        RoundedCornerShape(6.dp)
                                    )
                                    .border(
                                        width = if (isSelected) 2.dp else 1.dp,
                                        color = if (isSelected) ComposeColor(0xFFC3B6F9)
                                                else ComposeColor.White.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        hexInput = String.format("%06X", rgbInt)
                                        error = null
                                    }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, color = ComposeColor(0xFFFF6B6B), fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            GlassButton(onClick = {
                val parsed = rgb
                if (parsed == null) {
                    error = "Enter 6 hex digits (0-9 A-F)"
                } else {
                    val finalHex = String.format("#%02X%06X", alpha, parsed)
                    onColorSelected(finalHex)
                }
            }) {
                Text("Apply", color = ComposeColor(0xFFC3B6F9), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            GlassButton(onClick = onDismiss) {
                Text("Cancel", color = ComposeColor.White.copy(alpha = 0.8f))
            }
        }
    )
}

private fun normalizeHexInput(raw: String): String {
    return raw.trim()
        .removePrefix("#")
        .removePrefix("0x")
        .uppercase()
        .filter { it in "0123456789ABCDEF" }
}

private fun parseRgbHex(hex: String): Int? {
    return try {
        if (hex.length != 6) null else hex.toLong(16).toInt() and 0xFFFFFF
    } catch (_: Exception) { null }
}

private fun parseAlpha(hex: String): Int {
    return try {
        val clean = hex.trim().removePrefix("#").removePrefix("0x")
        when (clean.length) {
            8 -> clean.substring(0, 2).toInt(16)
            6 -> 255
            else -> 255
        }
    } catch (_: Exception) { 255 }
}

private val PRESET_COLORS = intArrayOf(
    0x000000, 0x800000, 0x008000, 0x808000,
    0x000080, 0x800080, 0x008080, 0xC0C0C0,
    0x808080, 0xFF0000, 0x00FF00, 0xFFFF00,
    0x0000FF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
    0xE9D5FF, 0xC3B6F9, 0x7C3AED, 0xFFD700,
    0x94A3B8, 0x1A1A2E, 0x0B0F19, 0x10B981
)