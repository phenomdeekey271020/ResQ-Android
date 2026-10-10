package com.resq.app.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shape vocabulary from DESIGN.md:
 *
 *   - glass cards 28dp
 *   - pills 20dp
 *   - buttons fully rounded (height / 2) -> [pill] for tabular buttons
 *   - status ring / dots circular -> [round]
 */
object ResQShapes {
    val card = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(20.dp)
    val round: Shape = CircleShape
}

val ResQMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = ResQShapes.card,
    extraLarge = RoundedCornerShape(36.dp),
)
