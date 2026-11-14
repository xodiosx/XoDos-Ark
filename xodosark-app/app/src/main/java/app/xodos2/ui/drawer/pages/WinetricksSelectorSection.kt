package app.xodos2.ui.drawer.pages

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.xodos2.R
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.glassDialogStyle
import app.xodos2.ui.runtime.WinetricksManager
import app.xodos2.ui.runtime.WinetricksVerb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WinetricksSelectorSection(
    title: String? = null,
    enabled: Boolean = true,
    onExecuteCommand: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sectionTitle = title ?: stringResource(R.string.app_winetricks_section_title)

    // ── State ───────────────────────────────────────────────────
    var installChecked by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    var verbs by remember { mutableStateOf<List<WinetricksVerb>>(emptyList()) }
    var lastUpdatedMs by remember { mutableStateOf(0L) }
    var isUpdating by remember { mutableStateOf(false) }
    var updateError by remember { mutableStateOf<String?>(null) }

    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }

    // ── Initial load (one-shot) ─────────────────────────────────
    LaunchedEffect(Unit) {
        val (ok, loaded, updated) = withContext(Dispatchers.IO) {
            val ok = WinetricksManager.ensureInstalled(context)
            if (ok) {
                Triple(true, WinetricksManager.loadVerbs(context), WinetricksManager.lastUpdated(context))
            } else {
                @Suppress("UNCHECKED_CAST")
                Triple(false, emptyList<WinetricksVerb>(), 0L)
            }
        }
        installChecked = true
        installError = if (ok) null else context.getString(R.string.app_winetricks_source_missing)
        verbs = loaded
        lastUpdatedMs = updated
    }

    // ── Update driver (triggered by isUpdating=true) ────────────
    LaunchedEffect(isUpdating) {
        if (!isUpdating) return@LaunchedEffect
        val result = WinetricksManager.updateVerbs(context)
        result.fold(
            onSuccess = { newVerbs ->
                verbs = newVerbs
                lastUpdatedMs = WinetricksManager.lastUpdated(context)
                updateError = null
                Toast.makeText(
                    context,
                    context.getString(R.string.app_winetricks_update_success, newVerbs.size),
                    Toast.LENGTH_SHORT
                ).show()
            },
            onFailure = { e ->
                updateError = e.message ?: "update failed"
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.app_winetricks_update_failed,
                        e.message ?: "unknown error"
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
        isUpdating = false
    }

    // ── Drawer entry ────────────────────────────────────────────
    DrawerExpandableSection(title = sectionTitle, defaultExpanded = false) {
        when {
            !installChecked -> Text(
                stringResource(R.string.app_winetricks_preparing),
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )

            installError != null -> {
                Text(
                    installError!!,
                    color = Color(0xFFFF6B6B),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
                Text(
                    stringResource(R.string.app_winetricks_source_missing_hint),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            else -> {
                Text(
                    stringResource(R.string.app_winetricks_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )

                Button(
                    onClick = { showDialog = true },
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.app_winetricks_open_button))
                }
            }
        }
    }

    if (!showDialog) return

    // ── Derived state for dialog ────────────────────────────────
    val filtered = remember(verbs, search, category) {
        verbs.filter { v ->
            (category == null || v.category == category) &&
            (search.isBlank() ||
                v.name.contains(search, ignoreCase = true) ||
                v.description.contains(search, ignoreCase = true))
        }
    }
    val categories = remember(verbs) {
        verbs.map { it.category }.distinct().filter { it.isNotBlank() }.sorted()
    }

    // ── Dialog ──────────────────────────────────────────────────
    AlertDialog(
        onDismissRequest = { if (!isUpdating) showDialog = false },
        containerColor = Color.Transparent,
        modifier = Modifier.glassDialogStyle(),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.app_winetricks_dialog_title),
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { if (!isUpdating) isUpdating = true },
                    enabled = !isUpdating
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.app_winetricks_refresh_desc),
                        tint = if (isUpdating) Color.White.copy(alpha = 0.3f) else Color(0xFFC3B6F9)
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)) {
                if (isUpdating) {
                    // ── Blocking progress view ─────────────────
                    Column(
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = Color(0xFFC3B6F9),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            stringResource(R.string.app_winetricks_fetching),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.app_winetricks_fetching_hint),
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                } else {
                    // ── Search ─────────────────────────────────
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = {
                            Text(
                                stringResource(R.string.app_winetricks_search_hint),
                                color = Color.White.copy(alpha = 0.4f)
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.6f)
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFC3B6F9),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            cursorColor = Color(0xFFC3B6F9)
                        )
                    )
                    Spacer(Modifier.height(6.dp))

                    // ── Category / count / last updated ────────
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        var catExpanded by remember { mutableStateOf(false) }
                        Box {
                            TextButton(
                                onClick = { catExpanded = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                val selectedLabel = if (category == null)
                                    stringResource(R.string.app_winetricks_category_all)
                                else
                                    categoryLabel(category!!)
                                Text(
                                    stringResource(
                                        R.string.app_winetricks_category_label,
                                        selectedLabel
                                    ),
                                    color = Color(0xFFC3B6F9)
                                )
                            }
                            DropdownMenu(
                                expanded = catExpanded,
                                onDismissRequest = { catExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                R.string.app_winetricks_category_all_with_count,
                                                verbs.size
                                            )
                                        )
                                    },
                                    onClick = { category = null; catExpanded = false }
                                )
                                categories.forEach { c ->
                                    val n = verbs.count { it.category == c }
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                stringResource(
                                                    R.string.app_winetricks_category_with_count,
                                                    categoryLabel(c),
                                                    n
                                                )
                                            )
                                        },
                                        onClick = { category = c; catExpanded = false }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            stringResource(
                                R.string.app_winetricks_count,
                                filtered.size,
                                verbs.size
                            ),
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // ── Last updated / error line ──────────────
                    val statusLine = updateError ?: lastUpdatedLabel(lastUpdatedMs)
                    Text(
                        statusLine,
                        color = if (updateError != null) Color(0xFFFF6B6B)
                                else Color.White.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )

                    Spacer(Modifier.height(4.dp))

                    // ── List ───────────────────────────────────
                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(200.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.app_winetricks_no_matches),
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(filtered, key = { "${it.category}/${it.name}" }) { verb ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onExecuteCommand("WINETRICKS_GUI=none winetricks ${verb.name}")
                                            showDialog = false
                                        }
                                        .padding(vertical = 8.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        verb.name,
                                        color = Color.White,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (verb.description.isNotBlank()) {
                                        Text(
                                            verb.description,
                                            color = Color.White.copy(alpha = 0.6f),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (!isUpdating) showDialog = false },
                enabled = !isUpdating
            ) {
                Text(
                    stringResource(
                        if (isUpdating) R.string.app_winetricks_please_wait
                        else R.string.app_winetricks_close
                    ),
                    color = Color(0xFFC3B6F9),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}

/* ─── Localized category label ──────────────────────────────── */
@Composable
private fun categoryLabel(raw: String): String = when (raw) {
    "apps"       -> stringResource(R.string.app_winetricks_cat_apps)
    "benchmarks" -> stringResource(R.string.app_winetricks_cat_benchmarks)
    "dlls"       -> stringResource(R.string.app_winetricks_cat_dlls)
    "fonts"      -> stringResource(R.string.app_winetricks_cat_fonts)
    "settings"   -> stringResource(R.string.app_winetricks_cat_settings)
    else         -> raw   // future categories fall back to the raw English name
}

/* ─── Localized "last updated N ago" label ──────────────────── */
@Composable
private fun lastUpdatedLabel(ms: Long): String {
    if (ms <= 0L) return stringResource(R.string.app_winetricks_built_in_list)
    val delta = System.currentTimeMillis() - ms
    return when {
        delta < 60_000L     -> stringResource(R.string.app_winetricks_updated_just_now)
        delta < 3_600_000L  -> stringResource(R.string.app_winetricks_updated_minutes_ago, delta / 60_000L)
        delta < 86_400_000L -> stringResource(R.string.app_winetricks_updated_hours_ago, delta / 3_600_000L)
        else                -> stringResource(R.string.app_winetricks_updated_days_ago, delta / 86_400_000L)
    }
}