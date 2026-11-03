package app.xodos2.ui.util

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@Composable
fun FitText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    maxFontSize: TextUnit = style.fontSize,
    minFontSize: TextUnit = 9.sp,
    step: Float = 0.5f,
) {
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val maxW = with(density) { maxWidth.roundToPx() }

        val fitted = remember(text, maxW, maxFontSize, minFontSize, style, fontWeight) {
            var s = maxFontSize.value
            while (s >= minFontSize.value) {
                val r = measurer.measure(
                    text = text,
                    style = style.copy(fontSize = s.sp, fontWeight = fontWeight),
                    maxLines = 1,
                    softWrap = false,
                    constraints = Constraints(maxWidth = maxW),
                )
                if (!r.hasVisualOverflow) return@remember s.sp
                s -= step
            }
            minFontSize
        }

        Text(
            text = text,
            style = style.copy(fontSize = fitted, fontWeight = fontWeight),
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}