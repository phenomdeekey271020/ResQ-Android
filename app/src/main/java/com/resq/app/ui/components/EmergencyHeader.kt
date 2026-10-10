package com.resq.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.resq.app.model.SafetyState
import com.resq.app.ui.theme.LocalResQColors
import com.resq.app.ui.theme.ResQSpacing
import com.resq.app.ui.theme.ResQTextStyles

/**
 * Emergency header: status pill + title + one-line subtitle. Used by Emergency
 * Response (6.3) and Location Unavailable (6.4) so the emergency atmosphere -
 * and the state - stays obvious across both screens.
 */
@Composable
fun EmergencyHeader(
    badge: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val c = LocalResQColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusPill(
            label = badge,
            state = SafetyState.CRITICAL,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = title,
            style = ResQTextStyles.headlineLg,
            fontWeight = FontWeight.Bold,
            color = c.text,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = ResQTextStyles.bodyLg,
                color = c.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The in-app back control of the emergency screens (DESIGN 6.3 / 6.4).
 *
 * There is exactly one handler behind it - the same [onBack] the system Back
 * gesture uses - so both paths perform the identical, deterministic action for
 * the current incident state. The icon sits in a full-width row with a 48dp
 * minimum touch target so the first tap always lands.
 */
@Composable
fun EmergencyBackArrow(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArrowBackIcon(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clickable(onClick = onBack)
                .padding(ResQSpacing.xs),
            contentDescription = "Back",
        )
    }
}

/** Large emergency stat: value + label with a trailing chevron. */
@Composable
fun EmergencyStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val c = LocalResQColors.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ResQSpacing.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = value,
                style = ResQTextStyles.displayLg,
                fontWeight = FontWeight.Bold,
                color = c.text,
            )
            Text(
                text = label,
                style = ResQTextStyles.caption,
                color = c.textMuted,
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        ChevronRight(color = c.textMuted)
    }
}
