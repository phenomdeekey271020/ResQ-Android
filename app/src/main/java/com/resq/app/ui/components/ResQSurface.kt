package com.resq.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.resq.app.ui.theme.LocalResQColors
import com.resq.app.ui.theme.ResQShapes
import com.resq.app.ui.theme.ResQSpacing

/**
 * The one glass recipe from DESIGN.md:
 *
 *   fill `glass`, 1dp `glassBorder`, shadow, inner padding.
 *
 * Nothing else in the app draws glass. Screens place containers on top of this;
 * they never stack glass on glass.
 */
@Composable
fun ResQSurface(
    modifier: Modifier = Modifier,
    shape: Shape = ResQShapes.card,
    elevation: androidx.compose.ui.unit.Dp = 8.dp,
    padding: PaddingValues = PaddingValues(
        horizontal = ResQSpacing.rowGroupHorizontal,
        vertical = ResQSpacing.lg,
    ),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalResQColors.current
    Column(
        modifier = modifier
            .shadow(elevation, shape, clip = false, ambientColor = colors.shadow, spotColor = colors.shadow)
            .clip(shape)
            .background(colors.glass)
            .border(1.dp, colors.glassBorder, shape)
            .padding(padding),
        content = content,
    )
}
