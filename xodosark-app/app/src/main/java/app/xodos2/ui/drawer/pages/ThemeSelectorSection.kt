package app.xodos2.ui.drawer.pages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xodos2.R
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.runtime.EnvManager

@Composable
fun ThemeSelectorSection(
    title: String? = null,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val sectionTitle = title ?: stringResource(R.string.app_themesel_title)

    var themes   by remember { mutableStateOf(EnvManager.listThemes(context)) }
    var selected by remember { mutableStateOf(EnvManager.selectedTheme(context)) }
    var expanded by remember { mutableStateOf(false) }
    var status   by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        themes   = EnvManager.listThemes(context)
        selected = EnvManager.selectedTheme(context)
    }

    DrawerExpandableSection(title = sectionTitle, defaultExpanded = false) {

        if (themes.isEmpty()) {
            Text(
                text = stringResource(R.string.app_themesel_no_themes),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.app_themesel_current),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
                Box {
                    TextButton(
                        onClick = { expanded = true },
                        enabled = enabled,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = selected ?: stringResource(R.string.app_themesel_default),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFC3B6F9),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.app_themesel_default)) },
                            onClick = {
                                expanded = false
                                EnvManager.setSelectedTheme(context, null)
                                refresh()
                                status = context.getString(R.string.app_themesel_cleared)
                            }
                        )
                        themes.forEach { theme ->
                            DropdownMenuItem(
                                text = { Text(theme) },
                                onClick = {
                                    expanded = false
                                    EnvManager.setSelectedTheme(context, theme)
                                    refresh()
                                    status = context.getString(R.string.app_themesel_updated, theme)
                                }
                            )
                        }
                    }
                }
            }
        }

        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}