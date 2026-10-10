package com.resq.app.ui.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Active ResQ token set. Screens and components read this and never hardcode a
 * palette value, so Light / Dark / Emergency is a token swap, not a branch.
 */
val LocalResQColors = staticCompositionLocalOf { DarkResQColors }

/** Backwards-compatible alias used by a few call sites. */
val LocalResQThemeColors = LocalResQColors

/**
 * ResQ theme. Resolves the user's Light/Dark/System choice and, when a
 * [emergency] state is active, overrides it with the emergency atmosphere.
 */
@Composable
fun ResQTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    emergency: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        emergency -> EmergencyResQColors
        darkTheme -> DarkResQColors
        else -> LightResQColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            val lightBars = !darkTheme && !emergency
            controller.isAppearanceLightStatusBars = lightBars
            controller.isAppearanceLightNavigationBars = lightBars
        }
    }

    CompositionLocalProvider(LocalResQColors provides colors) {
        MaterialTheme(
            colorScheme = materialColorScheme(colors),
            typography = ResQTypography,
            shapes = ResQMaterialShapes,
            content = content,
        )
    }
}

/**
 * Full-screen atmosphere: the animated ground plus the soft top-right glow.
 * Placed inside [ResQTheme] so it animates between token sets when the
 * emergency state changes.
 */
@Composable
fun ResQBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalResQColors.current
    // 250ms crossfade of the ground and glow when the atmosphere changes.
    val bg by animateColorAsState(colors.bg, tween(250), label = "resqBg")
    val glow by animateColorAsState(colors.bgGlow, tween(250), label = "resqGlow")

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bg),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width, 0f)
            val radius = size.maxDimension * 0.95f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow, Color.Transparent),
                    center = center,
                    radius = radius,
                ),
                radius = radius,
                center = center,
            )
        }
        content()
    }
}

/**
 * Maps ResQ tokens onto Material 3 so stock components (Switch, ripple, text
 * defaults) stay consistent with the active atmosphere.
 */
private fun materialColorScheme(colors: ResQColors) =
    if (colors.isEmergency) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = colors.accent,
            onSecondary = colors.onAccent,
            tertiary = colors.accent,
            onTertiary = colors.onAccent,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.bg,
            onSurface = colors.text,
            surfaceVariant = colors.glass,
            onSurfaceVariant = colors.textMuted,
            surfaceContainer = colors.bg,
            surfaceContainerHigh = colors.glass,
            surfaceContainerHighest = colors.glass,
            error = colors.danger,
            onError = colors.onDanger,
            outline = colors.glassBorder,
            outlineVariant = colors.divider,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            secondary = colors.accent,
            onSecondary = colors.onAccent,
            tertiary = colors.accent,
            onTertiary = colors.onAccent,
            background = colors.bg,
            onBackground = colors.text,
            surface = colors.bg,
            onSurface = colors.text,
            surfaceVariant = colors.glass,
            onSurfaceVariant = colors.textMuted,
            surfaceContainer = colors.glass,
            surfaceContainerHigh = colors.glass,
            surfaceContainerHighest = colors.glass,
            error = colors.statusCritical,
            onError = Color.White,
            outline = colors.textMuted,
            outlineVariant = colors.divider,
        )
    }
