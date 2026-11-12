package app.xodos2.ui.drawer.pages

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.xodos2.R
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.runtime.IsoManager
import kotlinx.coroutines.launch

@Composable
fun IsoSelectorSection() {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()

    var showDialog  by remember { mutableStateOf(false) }
    var mountedPath by remember { mutableStateOf(IsoManager.getMountedIsoDosPath(context)) }
    var busy        by remember { mutableStateOf(false) }
    var status      by remember { mutableStateOf<String?>(null) }

    fun refresh() { mountedPath = IsoManager.getMountedIsoDosPath(context) }

    val pickIso = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            IsoManager.mountIso(context, uri) { msg -> status = msg }
            busy = false
            refresh()
        }
    }

    DrawerExpandableSection(
        title = stringResource(R.string.app_isosel_title),
        defaultExpanded = false
    ) {
        Text(
            text = mountedPath ?: stringResource(R.string.app_isosel_no_iso),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )

        Spacer(Modifier.height(6.dp))

        Button(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        ) {
            Text(stringResource(R.string.app_isosel_manage))
        }

        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { if (!busy) showDialog = false },
            title = { Text(stringResource(R.string.app_isosel_dialog_title)) },
            text = {
                Column {
                    Text(
                        text = mountedPath ?: stringResource(R.string.app_isosel_no_iso),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                    )
                    status?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                        )
                    }
                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = {
                            pickIso.launch(arrayOf("application/x-iso9660-image", "*/*"))
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.app_isosel_mount)) }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            IsoManager.launchWfm()
                            status = context.getString(R.string.app_isosel_wfm_launched)
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.app_isosel_explore)) }

                    Spacer(Modifier.height(8.dp))

                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                IsoManager.unmountIso(context) { msg -> status = msg }
                                busy = false
                                refresh()
                            }
                        },
                        enabled = !busy && mountedPath != null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF6B6B).copy(alpha = 0.2f),
                            contentColor   = Color(0xFFFF8B8B)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.app_isosel_unmount)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }, enabled = !busy) {
                    Text(stringResource(R.string.app_isosel_close))
                }
            }
        )
    }
}