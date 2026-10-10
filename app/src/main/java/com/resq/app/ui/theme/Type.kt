package com.resq.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.resq.app.R

val ResQFontFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semi_bold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/*
 * The complete ResQ type scale from DESIGN.md - Inter throughout, with the
 * specified weights, line heights and tracking. Mobile display variants are
 * used below the 600dp compact breakpoint.
 */
object ResQTextStyles {
    val displayLg = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        lineHeight = 64.sp,
        letterSpacing = (-1.12).sp,
    )
    val displayLgMobile = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        letterSpacing = (-0.8).sp,
    )
    val displayMd = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 52.sp,
        letterSpacing = (-0.66).sp,
    )
    val displayMdMobile = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.48).sp,
    )
    val headlineLg = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.32).sp,
    )
    val headlineMd = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.28).sp,
    )
    val headlineSm = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
    )
    val titleLg = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
    )
    val titleMd = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.18.sp,
    )
    val titleSm = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.16.sp,
    )
    val bodyLg = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.16.sp,
    )
    val bodyMd = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.21.sp,
    )
    val bodySm = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.24.sp,
    )
    val labelLg = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.28.sp,
    )
    val labelMd = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.36.sp,
    )
    val labelSm = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.4.sp,
    )

    // ---- DESIGN.md component type roles ----

    /** Alert countdown: 84/84, 700. */
    val countdown = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 84.sp,
        lineHeight = 84.sp,
        letterSpacing = (-1.5).sp,
    )

    /** Resource distance stat: 56/56, 700. */
    val distance = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        lineHeight = 56.sp,
        letterSpacing = (-1).sp,
    )

    /** Large primary button label: 20/600. */
    val buttonLarge = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.2.sp,
    )

    /** Standard button label: 18/600. */
    val button = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.2.sp,
    )

    /** List row label: 17/400. */
    val row = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    )

    /** List row value / name: 17/600. */
    val rowStrong = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
    )

    /** Caption: 14/20. */
    val caption = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
    )

    /** Section label: 13/600 uppercase, +0.8 tracking. */
    val sectionLabel = TextStyle(
        fontFamily = ResQFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.8.sp,
    )
}

/**
 * Tabular lining figures for countdowns, speed and G-force metrics so live
 * values never jitter optically (DESIGN.md > Typography).
 */
fun TextStyle.tabularFigures(): TextStyle = copy(fontFeatureSettings = "tnum")

val ResQTypography = Typography(
    displayLarge = ResQTextStyles.displayLg,
    displayMedium = ResQTextStyles.displayMd,
    displaySmall = ResQTextStyles.displayMdMobile,
    headlineLarge = ResQTextStyles.headlineLg,
    headlineMedium = ResQTextStyles.headlineMd,
    headlineSmall = ResQTextStyles.headlineSm,
    titleLarge = ResQTextStyles.titleLg,
    titleMedium = ResQTextStyles.titleMd,
    titleSmall = ResQTextStyles.titleSm,
    bodyLarge = ResQTextStyles.bodyLg,
    bodyMedium = ResQTextStyles.bodyMd,
    bodySmall = ResQTextStyles.bodySm,
    labelLarge = ResQTextStyles.labelLg,
    labelMedium = ResQTextStyles.labelMd,
    labelSmall = ResQTextStyles.labelSm,
)
