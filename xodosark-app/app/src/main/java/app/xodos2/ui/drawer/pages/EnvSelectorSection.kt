package app.xodos2.ui.drawer.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.xodos2.R
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.runtime.EnvManager

@Composable
fun EnvSelectorSection(
    title: String? = null,
    enabled: Boolean = true
) {
    val context = LocalContext.current
    val sectionTitle = title ?: stringResource(R.string.app_envsel_title)

    var envVars  by remember { mutableStateOf(EnvManager.readEnv(context)) }
    var env2Vars by remember { mutableStateOf(EnvManager.readEnv2(context)) }
    var status   by remember { mutableStateOf<String?>(null) }

    var showAddDialog by remember { mutableStateOf(false) }
    var newName  by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var nameError by remember { mutableStateOf("") }

    fun refresh() {
        envVars  = EnvManager.readEnv(context)
        env2Vars = EnvManager.readEnv2(context)
    }

    DrawerExpandableSection(title = sectionTitle, defaultExpanded = false) {

        envVars.forEach { envVar ->
            EnvVarRow(
                envVar  = envVar,
                enabled = enabled,
                onSave  = { newVal ->
                    val ok = EnvManager.updateVar(context, envVar.name, newVal)

                    if (envVar.name == "MESA_GL_VERSION_OVERRIDE") {
                        val paired = EnvManager.GLSL_PAIRS[newVal]
                        if (paired != null) {
                            EnvManager.updateVar(context, "MESA_GLSL_VERSION_OVERRIDE", paired)
                        }
                    }

                    status = if (ok)
                        context.getString(R.string.app_envsel_updated, envVar.name)
                    else
                        context.getString(R.string.app_envsel_update_failed, envVar.name)
                    refresh()
                }
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 8.dp),
            color = Color.White.copy(alpha = 0.1f)
        )

        Text(
            text = stringResource(R.string.app_envsel_custom_header),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
        )

        if (env2Vars.isEmpty()) {
            Text(
                text = stringResource(R.string.app_envsel_no_custom),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        env2Vars.forEach { envVar ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = envVar.name + "=" + envVar.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                androidx.compose.material3.IconButton(
                    onClick = {
                        EnvManager.removeEnv2Var(context, envVar.name)
                        refresh()
                    },
                    modifier = Modifier.width(32.dp).height(32.dp)
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.app_envsel_delete),
                        tint = Color(0xFFFF6B6B),
                        modifier = Modifier.width(18.dp).height(18.dp)
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.app_envsel_add_custom),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { showAddDialog = true }
                .padding(vertical = 12.dp, horizontal = 4.dp)
        )

        val st = status
        if (st != null) {
            Text(
                text = st,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.app_envsel_add_dialog_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it; nameError = "" },
                        label = { Text(stringResource(R.string.app_envsel_label_name)) },
                        singleLine = true,
                        isError = nameError.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (nameError.isNotEmpty()) {
                        Text(
                            text = nameError,
                            color = Color(0xFFFF6B6B),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newValue,
                        onValueChange = { newValue = it },
                        label = { Text(stringResource(R.string.app_envsel_label_value)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = newName.trim()
                    if (!name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
                        nameError = context.getString(R.string.app_envsel_invalid_name)
                    } else {
                        EnvManager.setEnv2Var(context, name, newValue)
                        refresh()
                        status = context.getString(R.string.app_envsel_updated, name)
                        newName = ""
                        newValue = ""
                        showAddDialog = false
                    }
                }) {
                    Text(stringResource(R.string.app_envsel_add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.app_envsel_cancel))
                }
            }
        )
    }
}

@Composable
private fun EnvVarRow(
    envVar: EnvManager.EnvVar,
    enabled: Boolean,
    onSave: (String) -> Unit
) {
    val options  = EnvManager.KNOWN_OPTIONS[envVar.name]
    var expanded by remember { mutableStateOf(false) }
    var textValue by remember(envVar.value) { mutableStateOf(envVar.value) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = envVar.name,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.8f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))

        if (options != null) {
            Box {
                TextButton(
                    onClick = { expanded = true },
                    enabled = enabled,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    val label = if (envVar.value.isEmpty())
                        stringResource(R.string.app_envsel_unset)
                    else
                        envVar.value
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC3B6F9),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    options.forEach { opt ->
                        val itemLabel = if (opt.isEmpty())
                            stringResource(R.string.app_envsel_unset)
                        else
                            opt
                        DropdownMenuItem(
                            text = { Text(itemLabel) },
                            onClick = {
                                expanded = false
                                onSave(opt)
                            }
                        )
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(160.dp),
                enabled = enabled
            )
            Spacer(Modifier.width(4.dp))
            TextButton(
                onClick = { onSave(textValue) },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_envsel_set),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}