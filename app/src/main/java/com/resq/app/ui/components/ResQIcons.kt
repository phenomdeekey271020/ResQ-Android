package com.resq.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.resq.app.ui.theme.LocalResQColors

/**
 * ResQ icon set. Single-colour, 24dp, Material outline geometry. Icons are
 * drawn from path data so no resource table or extra dependency is required.
 */
@Composable
fun ResQIcon(
    pathData: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalResQColors.current.text,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    val path = remember(pathData) { PathParser().parsePathString(pathData).toPath() }
    val semanticsModifier = if (contentDescription != null) {
        Modifier.semantics { this.contentDescription = contentDescription }
    } else {
        Modifier
    }
    Canvas(modifier = modifier.size(size).then(semanticsModifier)) {
        val factor = this.size.minDimension / 24f
        scale(factor, pivot = Offset.Zero) {
            drawPath(path = path, color = tint)
        }
    }
}

// Material 24dp path data.
private const val PathShieldCheck =
    "M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16l-4-4 " +
        "1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z"
private const val PathChevronRight = "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"
private const val PathArrowBack = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"
private const val PathPhone =
    "M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 " +
        "1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 " +
        "0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"
private const val PathNavigation = "M12 2L4.5 20.29l.71.71L12 18l6.79 3 .71-.71z"
private const val PathLocation =
    "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 " +
        "0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z"
private const val PathMotion =
    "M13.49 5.48c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM9.89 19.38l1-4.4 2.1 2v6h2v-7.5" +
        "l-2.1-2 .6-3c1.3 1.5 3.3 2.5 5.5 2.5v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1" +
        "-.3 0-.5.1-.8.1l-5.2 2.2v4.7h2v-3.4l1.8-.7-1.6 8.1-4.9-1-.4 2 7 1.4z"

/** Brand mark: shield with a check. */
@Composable
fun ShieldCheckIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalResQColors.current.accent,
    size: Dp = 76.dp,
    contentDescription: String? = null,
) = ResQIcon(PathShieldCheck, modifier, tint, size, contentDescription)

@Composable
fun ChevronRight(
    modifier: Modifier = Modifier,
    color: Color = LocalResQColors.current.text,
    size: Dp = 20.dp,
) = ResQIcon(PathChevronRight, modifier, color, size)

@Composable
fun ArrowBackIcon(
    modifier: Modifier = Modifier,
    color: Color = LocalResQColors.current.text,
    size: Dp = 22.dp,
    contentDescription: String? = null,
) = ResQIcon(PathArrowBack, modifier, color, size, contentDescription)

@Composable
fun PhoneIcon(
    modifier: Modifier = Modifier,
    color: Color = LocalResQColors.current.text,
    size: Dp = 22.dp,
) = ResQIcon(PathPhone, modifier, color, size)

@Composable
fun NavigationIcon(
    modifier: Modifier = Modifier,
    color: Color = LocalResQColors.current.text,
    size: Dp = 22.dp,
) = ResQIcon(PathNavigation, modifier, color, size)

@Composable
fun LocationIcon(
    modifier: Modifier = Modifier,
    color: Color = LocalResQColors.current.text,
    size: Dp = 22.dp,
) = ResQIcon(PathLocation, modifier, color, size)

@Composable
fun MotionIcon(
    modifier: Modifier = Modifier,
    color: Color = LocalResQColors.current.text,
    size: Dp = 22.dp,
) = ResQIcon(PathMotion, modifier, color, size)
