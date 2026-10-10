package com.resq.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.resq.app.model.EmergencyResource
import com.resq.app.model.SafetyState
import com.resq.app.ui.components.GlassRow
import com.resq.app.ui.components.GlassRowGroup
import com.resq.app.ui.components.ResQPrimaryButton
import com.resq.app.ui.components.ResQSecondaryButton
import com.resq.app.ui.components.ResQTextButton
import com.resq.app.ui.components.RowDivider
import com.resq.app.ui.components.StatusPill
import com.resq.app.ui.theme.LocalResQColors
import com.resq.app.ui.theme.ResQSpacing
import com.resq.app.ui.theme.ResQTextStyles

/**
 * Emergency Resource Details. DESIGN.md 6.5: back action, type/availability
 * pill, name as title, the distance as the strongest figure, then the
 * address/phone rows (each hidden when the provider did not return one) and
 * the Directions action. The Call action only exists when a real phone number
 * came back with the result - no number is ever invented for a call. Stays
 * inside the emergency atmosphere.
 */
@Composable
fun EmergencyResourceDetailsScreen(
    resource: EmergencyResource,
    onBack: () -> Unit,
    onCall: () -> Unit,
    onDirections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalResQColors.current
    val phone = resource.phone?.takeIf { it.isNotBlank() }
    val address = resource.address?.takeIf { it.isNotBlank() }
    // Directions needs the provider's own position. With no usable destination the
    // action is hidden and replaced by an explanation - the user's position is
    // never used as the destination and no coordinates are invented.
    val hasDestination = resource.destination() != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ResQSpacing.sm),
        ) {
            ResQTextButton(text = "Back", onClick = onBack)
            // Weighted (without fill) so a long pill label - at a large font
            // scale on a narrow screen - wraps inside the space left over
            // instead of pushing past the right edge of the screen.
            StatusPill(
                label = "${resource.type.shortLabel()} · ${resource.availabilityLabel}",
                state = resource.availability,
                modifier = Modifier.weight(1f, fill = false),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = ResQSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ResQSpacing.md),
        ) {
            Text(
                text = resource.name,
                style = ResQTextStyles.headlineLg,
                fontWeight = FontWeight.Bold,
                color = colors.text,
                textAlign = TextAlign.Center,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(ResQSpacing.xs),
            ) {
                Text(
                    text = resource.distanceKm?.let { formatDistance(it) } ?: "—",
                    style = ResQTextStyles.distance,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                )
                Text(
                    text = resource.detail,
                    style = ResQTextStyles.caption,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                )
            }

            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                if (address != null) {
                    GlassRow(
                        label = "Address",
                        value = address,
                        valueColor = colors.text,
                        // A street address is content, not a status value: it
                        // belongs under its label at full row width, not beside
                        // it fighting the label for half the row.
                        stackedValue = true,
                    )
                    RowDivider()
                }
                if (phone != null) {
                    GlassRow(
                        label = "Phone",
                        value = phone,
                        valueColor = colors.text,
                    )
                    RowDivider()
                }
                if (resource.showsOwnership) {
                    GlassRow(
                        label = "Ownership",
                        // The backend returns no ownership data for this result.
                        // Saying "unknown" is the only honest answer - a guess
                        // about public/private ownership could send someone to
                        // the wrong place.
                        value = "Unknown",
                        valueColor = colors.textMuted,
                    )
                    RowDivider()
                }
                GlassRow(
                    label = "Status",
                    value = resource.availabilityLabel,
                    valueColor = if (resource.availability == SafetyState.ACTIVE) colors.accent else colors.textMuted,
                    showDot = true,
                )
            }

            Text(
                text = if (resource.isMock) {
                    "Prototype data — not a verified provider."
                } else {
                    "Live search result — availability not verified."
                },
                style = ResQTextStyles.caption,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = ResQSpacing.md),
            verticalArrangement = Arrangement.spacedBy(ResQSpacing.buttonGap),
        ) {
            if (phone != null) {
                ResQPrimaryButton(
                    text = "Call ${resource.type.shortLabel()}",
                    onClick = onCall,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasDestination) {
                    ResQSecondaryButton(
                        text = "Directions",
                        onClick = onDirections,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else if (hasDestination) {
                // No number came back with this result: offer directions only.
                // There is no call action without a real phone number.
                ResQPrimaryButton(
                    text = "Directions",
                    onClick = onDirections,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (!hasDestination) {
                Text(
                    text = "Directions unavailable — this result has no usable location.",
                    style = ResQTextStyles.caption,
                    color = colors.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
