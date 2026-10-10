package com.resq.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.resq.app.model.ResQUiState
import com.resq.app.model.SafetyState
import com.resq.app.model.label
import com.resq.app.ui.components.ConfidenceBar
import com.resq.app.ui.components.GlassRow
import com.resq.app.ui.components.GlassRowGroup
import com.resq.app.ui.components.ResQSecondaryButton
import com.resq.app.ui.components.ResQTextButton
import com.resq.app.ui.components.RowDivider
import com.resq.app.ui.components.SectionLabel
import com.resq.app.ui.components.StatusPill
import com.resq.app.ui.theme.LocalResQColors
import com.resq.app.ui.theme.ResQSpacing
import com.resq.app.ui.theme.ResQTextStyles

/**
 * Developer / Test mode. DESIGN.md 6.7: a Debug pill, a status card (sensors,
 * location fix and accuracy, detection state, prototype confidence), simulation
 * controls, and a technical card. Technical but clean - no terminal styling.
 *
 * Every control maps onto the existing simulation behaviour and changes local UI
 * state only; nothing here contacts a real service.
 */
@Composable
fun DiagnosticsScreen(
    uiState: ResQUiState,
    onBack: () -> Unit,
    onSimulateHardBraking: () -> Unit,
    onSimulatePossibleCollision: () -> Unit,
    onSimulateHighConfidence: () -> Unit,
    onSimulateUserOk: () -> Unit,
    onSimulateNoResponse: () -> Unit,
    onResetSimulation: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalResQColors.current
    val sensors = uiState.sensors
    val detection = uiState.detection

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
        verticalArrangement = Arrangement.spacedBy(ResQSpacing.lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ResQTextButton(text = "Back", onClick = onBack)
            StatusPill(label = "Debug", state = SafetyState.CAUTION, dot = false)
        }

        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.xs)) {
            Text(
                text = "Developer / Test mode",
                style = ResQTextStyles.headlineMd,
                fontWeight = FontWeight.Bold,
                color = colors.text,
            )
            Text(
                text = "Live hardware, detection state and simulation controls.",
                style = ResQTextStyles.caption,
                color = colors.textMuted,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Status")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(
                    label = "Accelerometer",
                    value = sensors.accelerometer.availability.label,
                    valueColor = colors.textMuted,
                )
                RowDivider()
                GlassRow(
                    label = "Gyroscope",
                    value = sensors.gyroscope.availability.label,
                    valueColor = colors.textMuted,
                )
                RowDivider()
                GlassRow(
                    label = "Location",
                    value = locationSummary(uiState),
                    valueColor = colors.textMuted,
                    onClick = onRequestLocationPermission,
                )
                RowDivider()
                GlassRow(
                    label = "Detection state",
                    value = detection.level.label,
                    valueColor = colors.textMuted,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Prototype confidence")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${detection.confidence}%",
                    style = ResQTextStyles.titleLg,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                    modifier = Modifier.padding(top = 10.dp),
                )
                ConfidenceBar(
                    progress = detection.confidenceFraction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = ResQSpacing.sm, bottom = 10.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Simulation")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                SimulationControl("Simulate impact", onSimulatePossibleCollision)
                RowDivider()
                SimulationControl("Simulate fall", onSimulateHighConfidence)
                RowDivider()
                SimulationControl("Simulate no response", onSimulateNoResponse)
                RowDivider()
                SimulationControl("Simulate user OK", onSimulateUserOk)
                RowDivider()
                SimulationControl("Simulate hard braking", onSimulateHardBraking)
                RowDivider()
                SimulationControl("Reset simulation", onResetSimulation)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
            SectionLabel("Technical")
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(label = "App build", value = "1.0 (prototype)", valueColor = colors.textMuted)
                RowDivider()
                GlassRow(label = "Detection model", value = "Weighted heuristic", valueColor = colors.textMuted)
                RowDivider()
                GlassRow(label = "Sample window", value = "12 samples", valueColor = colors.textMuted)
            }
        }

        Text(
            text = "Simulation controls only change local UI state — they never start a " +
                "search, a dispatch or a call.",
            style = ResQTextStyles.caption,
            color = colors.textMuted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = ResQSpacing.lg),
        )
    }
}

@Composable
private fun SimulationControl(
    label: String,
    onClick: () -> Unit,
) {
    val colors = LocalResQColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ResQSpacing.md),
    ) {
        Text(
            text = label,
            style = ResQTextStyles.row,
            color = colors.text,
            modifier = Modifier.weight(1f),
        )
        ResQSecondaryButton(
            text = "Run",
            onClick = onClick,
            minHeight = 48.dp,
            modifier = Modifier.width(96.dp),
        )
    }
}

private fun locationSummary(uiState: ResQUiState): String {
    val location = uiState.sensors.location
    return when {
        !location.hasReading -> location.availability.label
        location.accuracyMeters != null -> "${location.availability.label} · ±${location.accuracyMeters.toInt()} m"
        else -> location.availability.label
    }
}
