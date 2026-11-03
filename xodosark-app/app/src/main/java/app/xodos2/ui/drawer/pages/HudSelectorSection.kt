package app.xodos2.ui.drawer.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.xodos2.ui.drawer.menu.DrawerExpandableSection
import app.xodos2.ui.runtime.HudManager
import androidx.compose.ui.res.stringResource
import app.xodos2.R

@Composable
fun HudSelectorSection(
    title: String = "HUD",
    enabled: Boolean = true
) {
    val context = LocalContext.current

    // Load persisted mode; UI state mirrors it
    var mode by remember { mutableStateOf(HudManager.currentMode(context)) }
    var status by remember { mutableStateOf<String?>(null) }

    DrawerExpandableSection(title = title, defaultExpanded = false) {

        fun choose(next: HudManager.Mode) {
            mode = next
            val ok = HudManager.applyMode(context, next)
            status = if (ok) {
                when (next) {
                    HudManager.Mode.NONE     -> "HUD disabled"
                    HudManager.Mode.DXVK     -> "DXVK HUD enabled"
                    HudManager.Mode.MANGOHUD -> "MangoHud enabled"
                }
            } else {
                "Failed to write hud file"
            }
        }

        // ── DXVK HUD ─────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) {
                    // Toggle: if already DXVK, turning it off → NONE.
                    // Otherwise switch to DXVK (auto-unchecks MangoHud).
                    choose(
                        if (mode == HudManager.Mode.DXVK) HudManager.Mode.NONE
                        else HudManager.Mode.DXVK
                    )
                }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = mode == HudManager.Mode.DXVK,
                onCheckedChange = null,   // handled by Row clickable
                enabled = enabled
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.appscreen_dxvk_hud),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // ── MangoHud ─────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) {
                    choose(
                        if (mode == HudManager.Mode.MANGOHUD) HudManager.Mode.NONE
                        else HudManager.Mode.MANGOHUD
                    )
                }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = mode == HudManager.Mode.MANGOHUD,
                onCheckedChange = null,
                enabled = enabled
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.appscreen_mangohud),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        status?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}