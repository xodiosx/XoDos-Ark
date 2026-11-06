package app.xodos2.ui.drawer.pages

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.xodos2.R

@Composable
fun DrawerFooterActions(
    onCloseDrawer: () -> Unit,
    onExitRequested: () -> Unit
) {
    val context = LocalContext.current

    Text(
        text = stringResource(R.string.appscreen_help),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onCloseDrawer()
                try {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://xodos-ark.vercel.app")
                        )
                    )
                } catch (_: Exception) {}
            }
            .padding(vertical = 12.dp, horizontal = 12.dp)
    )

    Text(
        text = stringResource(R.string.appscreen_exit),
        style = MaterialTheme.typography.bodyLarge,
        color = Color(0xFFFF6B6B),
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onCloseDrawer()
                onExitRequested()
            }
            .padding(vertical = 12.dp, horizontal = 12.dp)
    )
}