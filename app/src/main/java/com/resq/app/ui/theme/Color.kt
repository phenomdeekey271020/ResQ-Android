package com.resq.app.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * ResQ design tokens - the single source of truth from DESIGN.md.
 *
 * Components read semantic tokens through [LocalResQColors] and never look at a
 * palette directly. Switching Light / Dark / Emergency swaps the token set only;
 * the component tree is identical in all three atmospheres.
 */

/**
 * Semantic token set for one atmosphere.
 *
 * Every field is a resolved Compose [Color] so components never parse strings or
 * branch on the theme. Emergency-only tokens ([danger], [onDanger]) are present
 * in every set so a single component signature serves all three themes.
 */
data class ResQColors(
    /** Window ground. */
    val bg: Color,
    /** Soft radial glow, top-right, 55% falloff. */
    val bgGlow: Color,
    /** Primary text. */
    val text: Color,
    /** Secondary / muted text. */
    val textMuted: Color,
    /** Glass surface fill. */
    val glass: Color,
    /** 1dp glass border. */
    val glassBorder: Color,
    /** Divider between rows. */
    val divider: Color,
    /** Primary fill: status dot, toggle-on, selected segment. */
    val accent: Color,
    /** Text / icon on [accent]. */
    val onAccent: Color,
    /** Emergency danger fill ("I Need Help Now"). */
    val danger: Color,
    /** Text on [danger]. */
    val onDanger: Color,
    /** Elevation shadow colour. */
    val shadow: Color,
    /** Live / healthy status dot. */
    val statusActive: Color,
    /** Caution status dot. */
    val statusCaution: Color,
    /** Critical status dot. */
    val statusCritical: Color,
    /** True while the emergency atmosphere is active (affordance for copy). */
    val isEmergency: Boolean = false,
)

// ---------------------------------------------------------------------------
// Light
// ---------------------------------------------------------------------------

val LightResQColors = ResQColors(
    bg = Color(0xFFEDF2F5),
    bgGlow = Color(0x616EC8BE),
    text = Color(0xFF0F1C24),
    textMuted = Color(0xFF46596A),
    glass = Color(0xA8FFFFFF),
    glassBorder = Color(0xF2FFFFFF),
    divider = Color(0x1A0F2832),
    accent = Color(0xFF0A7570),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFF5A0610),
    onDanger = Color(0xFFFFFFFF),
    shadow = Color(0x140F2832),
    statusActive = Color(0xFF0A7570),
    statusCaution = Color(0xFFB45309),
    statusCritical = Color(0xFFBE123C),
)

// ---------------------------------------------------------------------------
// Dark
// ---------------------------------------------------------------------------

val DarkResQColors = ResQColors(
    bg = Color(0xFF0B1418),
    bgGlow = Color(0x473CAAA0),
    text = Color(0xFFEAF2F5),
    textMuted = Color(0xFF9CB1BB),
    glass = Color(0x12FFFFFF),
    glassBorder = Color(0x26FFFFFF),
    divider = Color(0x1AFFFFFF),
    accent = Color(0xFF62D6CA),
    onAccent = Color(0xFF052320),
    danger = Color(0xFF5A0610),
    onDanger = Color(0xFFFFFFFF),
    shadow = Color(0x59000000),
    statusActive = Color(0xFF62D6CA),
    statusCaution = Color(0xFFF59E0B),
    statusCritical = Color(0xFFFB7185),
)

// ---------------------------------------------------------------------------
// Emergency
// ---------------------------------------------------------------------------

val EmergencyResQColors = ResQColors(
    bg = Color(0xFFA50E18),
    bgGlow = Color(0x73FF8278),
    text = Color(0xFFFFFFFF),
    textMuted = Color(0xEBFFFFFF),
    glass = Color(0x24FFFFFF),
    glassBorder = Color(0x52FFFFFF),
    divider = Color(0x40FFFFFF),
    accent = Color(0xFFFFFFFF),
    onAccent = Color(0xFF9A0C16),
    danger = Color(0xFF5A0610),
    onDanger = Color(0xFFFFFFFF),
    shadow = Color(0x403C0000),
    statusActive = Color(0xFFFFFFFF),
    statusCaution = Color(0xFFFFFFFF),
    statusCritical = Color(0xFFFFFFFF),
    isEmergency = true,
)
