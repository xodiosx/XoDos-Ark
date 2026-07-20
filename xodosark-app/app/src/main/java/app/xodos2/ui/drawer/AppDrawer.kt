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
import androidx.compose.ui.draw.blur
import app.xodos2.ui.glass.glassBlurModifier

@Composable
fun AppDrawer(
    drawerState: DrawerState,
    modifier: Modifier = Modifier,
    drawerWidth: Dp = 320.dp,
    drawerShape: Shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
    drawerBackgroundColor: Color = Color(0xFA0E0A1A),
    isBackgroundBlurred: Boolean = false,
    drawerContent: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val animatedSheetBlur by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (isBackgroundBlurred) 14.dp else 0.dp,
        animationSpec = androidx.compose.animation.core.spring(
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
        ),
        label = "drawerSheetBlur"
    )

    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        // Disable edge-swipe to open, but keep swipe-to-dismiss once open.
        gesturesEnabled = drawerState.isOpen,
        scrimColor = Color(0x4007040E), // customized elegant transparent dark-violet tinted scrim
        drawerContent = {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(drawerWidth)
                    .padding(end = 8.dp, top = 8.dp, bottom = 8.dp),
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
                val sheetModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && animatedSheetBlur > 0.dp) {
                    baseSheetModifier.blur(animatedSheetBlur)
                } else {
                    baseSheetModifier
                }
                Box(modifier = sheetModifier) {
                    drawerContent()
                }
            }
        },
    ) {
        val isOpeningOrOpen = drawerState.targetValue == DrawerValue.Open
        val animatedBlur by androidx.compose.animation.core.animateDpAsState(
            targetValue = if (isOpeningOrOpen || isBackgroundBlurred) 14.dp else 0.dp,
            animationSpec = androidx.compose.animation.core.spring(
                stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow
            ),
            label = "drawerBlur"
        )
        val contentModifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && animatedBlur > 0.dp) {
            Modifier
                .fillMaxSize()
                .blur(animatedBlur)
        } else {
            Modifier.fillMaxSize()
        }
        Box(modifier = contentModifier, contentAlignment = Alignment.TopStart) {
            content()
        }
    }
}
