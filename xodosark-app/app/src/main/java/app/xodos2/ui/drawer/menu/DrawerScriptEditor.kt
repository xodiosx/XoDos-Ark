package app.xodos2.ui.drawer.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import app.xodos2.ui.glass.GlassButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DrawerScriptEditor(
    title: String,
    initialText: String,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(initialText) }
    LaunchedEffect(initialText) { text = initialText }

    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = 0.92f),
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        minLines = 5,
        singleLine = false,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        GlassButton(onClick = { text = initialText }) { Text("Reset", color = Color.White.copy(alpha = 0.8f)) }
        GlassButton(onClick = { onSave(text.trimEnd()) }) { Text("Save", color = Color(0xFFC3B6F9), fontWeight = FontWeight.Bold) }
    }
}

