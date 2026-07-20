package app.xodos2.ui.glass

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val FloatingGlassCornerDp = 20.dp
val FloatingGlassRimDp = 1.dp
const val FloatingGlassRimAlpha = 0.55f

val GlassDialogScreenInsetDp = 8.dp

val GlassDialogWidthStandardDp = 400.dp
val GlassDialogWidthPickerDp = 280.dp

const val FloatingOverlayScrimAlpha = 0.22f

fun floatingOverlayScrimColor(): Color = Color.Black.copy(alpha = FloatingOverlayScrimAlpha)

internal val FloatingGlassBlurDp = 26.dp

private const val GlassHighlightApi31 = 0.120f
private const val GlassFillApi31 = 0.070f
private const val GlassHighlightLegacy = 0.144f
private const val GlassFillLegacy = 0.081f

fun glassBlurModifier(): Modifier = Modifier

fun floatingGlassBrush(): Brush =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = GlassHighlightApi31),
                Color.White.copy(alpha = GlassFillApi31)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = GlassHighlightLegacy),
                Color.White.copy(alpha = GlassFillLegacy)
            )
        )
    }

@Composable
fun GlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.03f)
                    )
                )
            )
            .border(
                width = 1.2.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.50f),
                        Color.White.copy(alpha = 0.08f)
                    )
                ),
                shape = RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            content()
        }
    }
}
