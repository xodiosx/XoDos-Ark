package app.xodos2.ui.x11

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.xodos2.X11Runtime
import com.termux.x11.EmbeddedX11Controller
import com.termux.x11.LorieView

@Composable
fun EmbeddedX11Surface(
    modifier: Modifier = Modifier,
    visible: Boolean,
    mouseMode: EmbeddedX11Controller.MouseMode,
    onConnectionStateChanged: (connected: Boolean) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val hostActivity = context as? Activity
    val controller = remember { EmbeddedX11Controller() }

    LaunchedEffect(visible) {
        if (visible) X11Runtime.ensureX11ServerProcessStarted(context)
    }

    // Only dispose when the composable leaves the tree, not on every visible toggle.
    DisposableEffect(Unit) {
        onDispose { controller.detach() }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            android.widget.FrameLayout(ctx).apply {
                setBackgroundColor(0xFF000000.toInt())
                visibility = android.view.View.GONE
                addView(
                    LorieView(ctx, null),
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        update = { view ->
            val lv = (view as? android.view.ViewGroup)?.getChildAt(0) as? LorieView ?: return@AndroidView
            view.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE

            if (visible) {
                if (!controller.isAttached) {
                    hostActivity?.let { controller.attach(it, view, lv) }
                }
                controller.setMouseMode(mouseMode)
            } else {
                if (controller.isAttached) {
                    controller.detach()
                    onConnectionStateChanged(false)
                }
            }
        },
    )
}