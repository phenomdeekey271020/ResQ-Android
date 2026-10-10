package com.resq.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.resq.app.model.GpsStatus
import com.resq.app.model.ProtectionReadiness
import com.resq.app.model.ProtectionState
import com.resq.app.model.ResQUiState
import com.resq.app.model.SafetyState
import com.resq.app.model.SensorStatus
import com.resq.app.ui.components.GlassRow
import com.resq.app.ui.components.GlassRowGroup
import com.resq.app.ui.components.ResQSecondaryButton
import com.resq.app.ui.components.ResQTextButton
import com.resq.app.ui.components.RowDivider
import com.resq.app.ui.components.SectionLabel
import com.resq.app.ui.components.ShieldCheckIcon
import com.resq.app.ui.components.StatusRing
import com.resq.app.ui.theme.LocalResQColors
import com.resq.app.ui.theme.ResQSpacing
import com.resq.app.ui.theme.ResQTextStyles

/**
 * Protection Home. DESIGN.md 6.1: ResQ wordmark + Settings text button, a 176dp
 * status ring with the shield-check mark, "You're protected" with one calm
 * sentence, one glass card with the two sensor rows, and the Pause Protection
 * secondary action. Pausing asks for confirmation and shows a muted paused
 * state in the same layout.
 */
@Composable
fun ProtectionHomeScreen(
    uiState: ResQUiState,
    onToggleProtection: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalResQColors.current
    val active = uiState.protection == ProtectionState.ACTIVE
    var confirmPause by remember { mutableStateOf(false) }

    val ringColor = if (active) colors.accent else colors.textMuted

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
    ) {
        // Top row: wordmark + Settings.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "ResQ",
                style = ResQTextStyles.buttonLarge,
                fontWeight = FontWeight.Bold,
                color = colors.text,
            )
            ResQTextButton(text = "Settings", onClick = onOpenSettings)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = ResQSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ResQSpacing.lg),
        ) {
            StatusRing(
                ringSize = 176.dp,
                ringColor = ringColor,
            ) {
                ShieldCheckIcon(tint = ringColor, size = 76.dp)
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm),
            ) {
                Text(
                    text = if (active) "You're protected" else "Protection paused",
                    style = ResQTextStyles.headlineLg,
                    fontWeight = FontWeight.Bold,
                    color = colors.text,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = if (active) {
                        "ResQ is watching for a crash in the background."
                    } else {
                        "ResQ is not monitoring motion right now."
                    },
                    style = ResQTextStyles.bodyLg,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = ResQSpacing.md),
                )
            }

            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(
                    label = "Motion sensors",
                    value = if (uiState.telemetry.sensors == SensorStatus.MONITORING) "Active" else "Paused",
                    valueColor = if (uiState.telemetry.sensors == SensorStatus.MONITORING) colors.accent else colors.textMuted,
                    showDot = uiState.telemetry.sensors == SensorStatus.MONITORING,
                )
                RowDivider()
                GlassRow(
                    label = "Location",
                    value = when (uiState.telemetry.gps) {
                        GpsStatus.LOCKED -> "Precise"
                        GpsStatus.STANDBY -> "Standby"
                        GpsStatus.ACQUIRING -> "Locating"
                        GpsStatus.UNAVAILABLE -> "Unavailable"
                    },
                    valueColor = if (uiState.telemetry.gps == GpsStatus.LOCKED) colors.accent else colors.textMuted,
                    showDot = uiState.telemetry.gps == GpsStatus.LOCKED,
                )
            }

            // Persistent, understandable readiness warning: it stays on Home
            // (and after a skipped contact setup) so the gap in the escalation
            // chain is never hidden, while the emergency screen and 112 stay
            // fully available either way.
            when (uiState.protectionReadiness) {
                ProtectionReadiness.CONTACT_SETUP_INCOMPLETE -> Text(
                    text = "Emergency contacts aren't set up, so automatic escalation is " +
                        "unavailable. Add up to 3 trusted contacts in Settings. Calling 112 " +
                        "never needs them.",
                    style = ResQTextStyles.caption,
                    color = colors.statusCaution,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ResQSpacing.md),
                )

                ProtectionReadiness.CONSENT_DISABLED -> Text(
                    text = "Trusted contacts are saved, but automatic contact calling is off. " +
                        "Enable it in Settings for the full escalation workflow.",
                    style = ResQTextStyles.caption,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ResQSpacing.md),
                )

                ProtectionReadiness.READY -> Unit
            }

            Spacer(modifier = Modifier.height(ResQSpacing.sm))
        }

        // Pinned bottom action.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = ResQSpacing.md),
            verticalArrangement = Arrangement.spacedBy(ResQSpacing.buttonGap),
        ) {
            ResQSecondaryButton(
                text = if (active) "Pause Protection" else "Resume Protection",
                onClick = { if (active) confirmPause = true else onToggleProtection() },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ResQTextButton(text = "Developer / Test mode", onClick = onOpenDiagnostics)
            }
            Text(
                text = "ResQ v1.0 · prototype build",
                style = ResQTextStyles.caption,
                color = colors.textMuted,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }

    if (confirmPause) {
        AlertDialog(
            onDismissRequest = { confirmPause = false },
            containerColor = colors.bg,
            titleContentColor = colors.text,
            textContentColor = colors.textMuted,
            title = { Text("Pause protection?") },
            text = { Text("ResQ will stop watching for a crash until you resume.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmPause = false
                        onToggleProtection()
                    },
                ) {
                    Text("Pause", color = colors.statusCritical)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPause = false }) {
                    Text("Keep protecting", color = colors.accent)
                }
            },
        )
    }
}
