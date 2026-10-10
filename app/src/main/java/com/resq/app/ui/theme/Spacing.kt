package com.resq.app.ui.theme

import androidx.compose.ui.unit.dp

/*
 * Spacing scale from DESIGN.md: 8dp base grid, 24dp screen side padding,
 * 16dp between blocks, 12dp between stacked buttons.
 *
 * Safe-area padding is applied with WindowInsets in the screens, not hardcoded.
 */
object ResQSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp

    /** Screen side padding per DESIGN.md. */
    val gutter = 24.dp

    /** Gap between stacked blocks. */
    val block = 16.dp

    /** Gap between stacked buttons. */
    val buttonGap = 12.dp

    /** Glass row-group inner padding. */
    val rowGroupVertical = 6.dp
    val rowGroupHorizontal = 20.dp

    val margin = 24.dp

    /** Kept for compatibility with the compact/tablet helpers. */
    val marginTablet = 32.dp
    val marginDesktop = 48.dp
    val gutterTablet = 24.dp
}
