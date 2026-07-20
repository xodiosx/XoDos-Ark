package app.xodos2.ui.drawer

import android.os.Build
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

@Composable
fun AppDrawer(
    drawerState: DrawerState,
    modifier: Modifier = Modifier,
    drawerWidth: Dp = 320.dp,
    drawerShape: Shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
    drawerBackgroundColor: Color = Color(0xFA0E0A1A),
    isBackgroundBlurred: Boolean = false,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            modifier = modifier,
            drawerState = drawerState,
            // Disable edge-swipe to open, but keep swipe-to-dismiss once open.
            gesturesEnabled = drawerState.isOpen,
            scrimColor = Color(0x4007040E), // customized elegant transparent dark-violet tinted scrim
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Surface(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(drawerWidth)
                            .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                        shape = drawerShape,
                        color = Color.Transparent,
                        tonalElevation = 0.dp,
                        shadowElevation = 16.dp,
                    ) {
                        val baseSheetModifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0xC20E0A24), // gorgeous deep translucent violet-slate
                                        Color(0xDC0B0F1E)  // slightly denser deep black-slate
                                    )
                                ),
                                shape = drawerShape
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.18f),
                                        Color.White.copy(alpha = 0.04f)
                                    )
                                ),
                                shape = drawerShape
                            )
                        Box(modifier = baseSheetModifier) {
                            drawerContent()
                        }
                    }
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                    content()
                }
            }
        }
    }
}

