package com.resq.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.resq.app.ui.theme.LocalResQColors
import com.resq.app.ui.theme.ResQShapes

/**
 * Circular status ring - the Protection Home centre piece.
 *
 * DESIGN.md: 176dp glass circle with a 14dp outer halo in `bgGlow`. The alert
 * countdown variant is 200dp. When [progress] is supplied the ring is drawn as
 * an arc (countdown sweep).
 */
@Composable
fun StatusRing(
    modifier: Modifier = Modifier,
    ringSize: Dp = 176.dp,
    strokeWidth: Dp = 8.dp,
    ringColor: Color = LocalResQColors.current.accent,
    progress: Float? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val c = LocalResQColors.current
    Box(
        modifier = modifier.size(ringSize),
        contentAlignment = Alignment.Center,
    ) {
        // Soft outer halo.
        Canvas(modifier = Modifier.matchParentSize()) {
            val radius = this.size.minDimension / 2f
            drawCircle(color = c.bgGlow.copy(alpha = 0.9f), radius = radius)
        }

        // Glass disc.
        Box(
            modifier = Modifier
                .size(ringSize)
                .clip(ResQShapes.round)
                .background(c.glass),
        )

        // Track + ring / countdown sweep.
        Canvas(modifier = Modifier.matchParentSize()) {
            val stroke = strokeWidth.toPx()
            val radius = this.size.minDimension / 2f - stroke / 2f
            drawCircle(
                color = c.divider,
                radius = radius,
                style = Stroke(width = stroke),
            )
            if (progress == null) {
                drawCircle(
                    color = ringColor,
                    radius = radius,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            } else {
                val arcSize = Size(radius * 2f, radius * 2f)
                val topLeft = androidx.compose.ui.geometry.Offset(
                    (this.size.width - arcSize.width) / 2f,
                    (this.size.height - arcSize.height) / 2f,
                )
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }

        content()
    }
}
