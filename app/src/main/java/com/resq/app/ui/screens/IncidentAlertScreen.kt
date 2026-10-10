package com.resq.app.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.resq.app.model.DetectionConfig
import com.resq.app.model.IncidentState
import com.resq.app.model.SafetyState
import com.resq.app.ui.components.ResQDangerButton
import com.resq.app.ui.components.ResQPrimaryButton
import com.resq.app.ui.components.StatusPill
import com.resq.app.ui.components.StatusRing
import com.resq.app.ui.theme.LocalResQColors
import com.resq.app.ui.theme.ResQSpacing
import com.resq.app.ui.theme.ResQTextStyles
import com.resq.app.ui.theme.tabularFigures

/**
 * Possible Incident Alert. DESIGN.md 6.2: emergency atmosphere, pill, "Are you
 * okay?", a 200dp ring with the 84sp countdown and "seconds", one explanation
 * sentence, then the two stacked actions. The countdown length is the real
 * window from the detection state machine.
 */
@Composable
fun IncidentAlertScreen(
    incident: IncidentState,
    confirmationSecondsRemaining: Int?,
    onUserOk: () -> Unit,
    onRequestHelp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalResQColors.current
    val possible = incident as? IncidentState.PossibleImpact
    val confirmation = incident as? IncidentState.Confirmation
    // The unconfirmed window uses the same ring as the first countdown, so the two
    // stages of "nobody answered" read the same way on screen. The possible-impact
    // total comes from the incident state itself (the configured window), never
    // from the per-level default, so a user-selected 30/45/60 s countdown drains
    // the ring evenly instead of pinning it full until the last 10 seconds.
    val total = possible?.totalSeconds
        ?: if (confirmation != null) DetectionConfig().confirmationWindowSeconds else 0
    val remaining = possible?.secondsRemaining ?: confirmationSecondsRemaining ?: 0
    val progress by animateFloatAsState(
        targetValue = if (total > 0) remaining.toFloat() / total.toFloat() else 0f,
        animationSpec = tween(durationMillis = 900),
        label = "countdownProgress",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
    ) {
        StatusPill(
            label = if (confirmation != null) {
                "No response detected"
            } else {
                "Possible accident detected"
            },
            state = SafetyState.CRITICAL,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = ResQSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ResQSpacing.lg),
        ) {
            Text(
                text = "Are you okay?",
                style = ResQTextStyles.displayLg,
                fontWeight = FontWeight.Bold,
                color = colors.text,
                textAlign = TextAlign.Center,
            )

            if (possible != null || confirmation != null) {
                StatusRing(
                    ringSize = 200.dp,
                    strokeWidth = 10.dp,
                    ringColor = colors.accent,
                    progress = progress,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = remaining.toString(),
                            style = ResQTextStyles.countdown.tabularFigures(),
                            color = colors.text,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "seconds",
                            style = ResQTextStyles.caption,
                            color = colors.textMuted,
                        )
                    }
                }
            }

            Text(
                text = when {
                    possible != null ->
                        "If you don't respond, ResQ moves to the emergency confirmation step."
                    confirmation != null && remaining > 0 ->
                        "No answer yet. ResQ starts your emergency response automatically " +
                            "when this reaches zero."
                    else -> "No response received. Choose how to continue."
                },
                style = ResQTextStyles.bodyLg,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = ResQSpacing.sm),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = ResQSpacing.md),
            verticalArrangement = Arrangement.spacedBy(ResQSpacing.buttonGap),
        ) {
            // Primary first in focus order.
            ResQPrimaryButton(
                text = "I'm OK — Cancel Alert",
                onClick = onUserOk,
                modifier = Modifier.fillMaxWidth(),
            )
            ResQDangerButton(
                text = "I Need Help Now",
                onClick = onRequestHelp,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Nothing has been sent to anyone yet — ResQ has not called, messaged or " +
                    "dispatched anything.",
                style = ResQTextStyles.caption,
                color = colors.textMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Text(
                // Truthful about the audible alert: it plays when the device can
                // play it, and the notification remains when it cannot.
                text = "ResQ also speaks this alert aloud when device audio is available.",
                style = ResQTextStyles.caption,
                color = colors.textMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}
