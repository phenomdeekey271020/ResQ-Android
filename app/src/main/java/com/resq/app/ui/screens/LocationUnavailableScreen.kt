package com.resq.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resq.app.model.EmergencyContact
import com.resq.app.model.EmergencyLocationSnapshot
import com.resq.app.model.EmergencyResource
import com.resq.app.model.needsSystemSettings
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationRecoveryReason
import com.resq.app.model.LocationRecoveryState
import com.resq.app.model.PrimaryContactCall
import com.resq.app.model.SmsEscalation
import com.resq.app.model.label
import com.resq.app.ui.components.ChevronRight
import com.resq.app.ui.components.EmergencyBackArrow
import com.resq.app.ui.components.EmergencyHeader
import com.resq.app.ui.components.GlassRow
import com.resq.app.ui.components.GlassRowGroup
import com.resq.app.ui.components.ResQPrimaryButton
import com.resq.app.ui.components.ResQSecondaryButton
import com.resq.app.ui.components.RowDivider
import com.resq.app.ui.components.SectionLabel
import com.resq.app.ui.theme.LocalResQColors
import com.resq.app.ui.theme.ResQSpacing
import com.resq.app.ui.theme.ResQTextStyles
import java.util.Locale

/**
 * Location Unavailable. DESIGN.md 6.4: the emergency atmosphere stays on. Shown
 * when the search is blocked because the position is missing OR too stale to
 * search around. Shows the last known fix (only when one actually exists), its
 * age, the recovery action that fits the actual problem, and the emergency
 * number. It never claims ResQ can silently turn Location on, and never invents
 * a position.
 */
@Composable
fun LocationUnavailableScreen(
    location: EmergencyLocationSnapshot?,
    recovery: LocationRecoveryState,
    resources: List<EmergencyResource>,
    contacts: List<EmergencyContact>,
    primaryCall: PrimaryContactCall,
    smsEscalation: SmsEscalation = SmsEscalation.NotAttempted,
    onCallContact: (EmergencyContact) -> Unit,
    onStandDown: () -> Unit,
    onEnableLocation: () -> Unit,
    onRefreshLocation: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onCallEmergency: () -> Unit,
    onOpenResource: (EmergencyResource) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalResQColors.current
    val limited = resources.take(2)
    // "Out of date" is only true for a position that EXISTS but is not fresh.
    // The old check (any snapshot with coordinates) also fired for a fresh but
    // too-coarse fix, telling the user their location was old when the real
    // problem was precision - and contradicting the Reason row beside it.
    val stale = location?.hasCoordinates == true && location.isFresh != true
    val acquiring = recovery.isBusy
    // The reason shown prefers the LIVE recovery verdict over the last published
    // snapshot: the user may have just turned Location on, in which case the
    // snapshot still says "off" and would show them the wrong instruction.
    val reason = when (val next = recovery) {
        is LocationRecoveryState.Unavailable -> next.reason.label()
        LocationRecoveryState.Acquiring -> AcquiringCopy
        LocationRecoveryState.Idle -> locationUnavailableReason(location?.availability)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
    ) {
        // Same deterministic back control as the main emergency screen.
        EmergencyBackArrow(onBack = onStandDown)

        EmergencyHeader(
            badge = "Emergency active",
            title = if (stale) "Location is out of date" else "Location is unavailable",
            subtitle = if (stale) {
                "The last position is too old to search around."
            } else {
                "Your emergency state is still active."
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(top = ResQSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(ResQSpacing.lg),
        ) {
            GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                GlassRow(
                    label = "Reason",
                    value = reason,
                    valueColor = colors.textMuted,
                )
                RowDivider()
                GlassRow(
                    label = "Last known location",
                    value = lastKnownValue(location),
                    valueColor = if (location?.hasCoordinates == true) colors.text else colors.textMuted,
                )
                RowDivider()
                // Explicit, because a cached pre-incident fix is a real position
                // and would otherwise be indistinguishable from a live one.
                GlassRow(
                    label = "Position basis",
                    value = locationBasisValue(location),
                    valueColor = if (location?.isFresh == true) colors.text else colors.textMuted,
                )
                RowDivider()
                GlassRow(
                    // Precision is stated, never rounded away: a coarse-only grant
                    // can be several hundred metres out and an emergency app that
                    // hides that is misleading the user about where they are.
                    label = "Accuracy",
                    value = accuracyValue(location),
                    valueColor = colors.textMuted,
                )
                RowDivider()
                GlassRow(
                    label = "Age",
                    value = ageValue(location),
                    valueColor = colors.textMuted,
                )
                RowDivider()
                GlassRow(
                    label = "Nearby resources",
                    value = if (resources.isEmpty()) "Not searched" else "Limited",
                    valueColor = colors.textMuted,
                )
            }

            if (acquiring) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        color = colors.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = AcquiringProgressLabel,
                        style = ResQTextStyles.caption,
                        color = colors.textMuted,
                    )
                }
            }

            if (limited.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
                    SectionLabel("Nearby (limited)")
                    GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                        limited.forEachIndexed { index, resource ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .minimumInteractiveComponentSize()
                                    .clickable { onOpenResource(resource) }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = resource.name,
                                        style = ResQTextStyles.rowStrong,
                                        color = colors.text,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = formatDistance(resource.distanceKm),
                                        style = ResQTextStyles.caption,
                                        color = colors.textMuted,
                                    )
                                }
                                ChevronRight(color = colors.textMuted)
                            }
                            if (index != limited.lastIndex) RowDivider()
                        }
                    }
                }
            }

            Text(
                text = when {
                    acquiring ->
                        "ResQ is asking the device for a position. This takes a " +
                            "few seconds, longer indoors."

                    stale ->
                        "ResQ will retry by itself when you come back, or you can " +
                            "refresh now. Calling the emergency number does not " +
                            "need a location."

                    else ->
                        "Enable Location for accurate help. Calling the emergency " +
                            "number does not require it."
                },
                style = ResQTextStyles.bodyLg,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ResQSpacing.sm),
            )

            // A missing position must never hide the people the user can call: this
            // is exactly the screen where the contacts matter most.
            EmergencyContactsSection(
                contacts = contacts,
                primaryCall = primaryCall,
                onCallContact = onCallContact,
                smsEscalation = smsEscalation,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = ResQSpacing.md),
            verticalArrangement = Arrangement.spacedBy(ResQSpacing.buttonGap),
        ) {
            // The primary action always matches the ACTUAL blocker: asking for a
            // permission that is already granted, or offering a refresh while the
            // only real fix is a settings trip, is what made this screen feel
            // broken. Both actions stay available either way, so the user is
            // never trapped on a dead end.
            val blockedBySettings = (recovery as? LocationRecoveryState.Unavailable)?.reason
                ?.needsSystemSettings == true
            val permissionMissing = (recovery as? LocationRecoveryState.Unavailable)?.reason ==
                LocationRecoveryReason.PERMISSION_DENIED

            if (blockedBySettings && !permissionMissing) {
                ResQPrimaryButton(
                    text = "Open Location settings",
                    onClick = onOpenLocationSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
                ResQSecondaryButton(
                    text = "Refresh location",
                    onClick = onRefreshLocation,
                    // Same rule as the neutral branch: one attempt at a time, so
                    // an impatient tap cannot queue a second acquisition.
                    enabled = !acquiring,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (permissionMissing) {
                ResQPrimaryButton(
                    text = "Allow location access",
                    onClick = onEnableLocation,
                    modifier = Modifier.fillMaxWidth(),
                )
                // A DENIED runtime permission lives on this app's own settings
                // page, not behind the device Location toggle - the button label
                // and the destination now agree.
                ResQSecondaryButton(
                    text = "Open app settings",
                    onClick = onOpenAppSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                ResQPrimaryButton(
                    text = if (acquiring) "Looking for a position…" else "Refresh location",
                    onClick = onRefreshLocation,
                    // Disabled only while THIS attempt is in flight, so an
                    // impatient tap cannot queue a second one. It re-enables the
                    // moment the attempt ends, however it ends.
                    enabled = !acquiring,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (stale || blockedBySettings) {
                    ResQSecondaryButton(
                        text = "Enable Location",
                        onClick = onEnableLocation,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            ResQSecondaryButton(
                text = "Call $EmergencyNumber",
                onClick = onCallEmergency,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun lastKnownValue(location: EmergencyLocationSnapshot?): String {
    if (location == null || !location.hasCoordinates) return "Unknown"
    val lat = location.latitude ?: return "Unknown"
    val lon = location.longitude ?: return "Unknown"
    return String.format(Locale.US, "%.3f, %.3f", lat, lon)
}

/**
 * What kind of position this is, stated explicitly.
 *
 * This row exists because a cached fix measured before the incident is a REAL
 * position and the emergency screen would otherwise render it exactly like a live
 * one. A rescuer - or the user reading over someone's shoulder - must be able to
 * see at a glance that this is where the phone was, not where it is.
 */
internal fun locationBasisValue(location: EmergencyLocationSnapshot?): String = when {
    location == null || !location.hasCoordinates -> "No position"
    location.isFresh -> "Current position from this device"
    else -> "Last known position - not the current location"
}

/**
 * Why there is no usable position, in one honest sentence per platform state.
 * Pure function so the wording is reviewable in one place and unit testable
 * without rendering anything. `null` (no snapshot yet) is still explained
 * rather than left blank.
 */
internal fun locationUnavailableReason(availability: LocationAvailability?): String =
    when (availability) {
        LocationAvailability.PERMISSION_DENIED ->
            "Location permission is denied."
        LocationAvailability.WAITING_FOR_PERMISSION ->
            "ResQ has not been allowed to use location yet."
        LocationAvailability.PROVIDERS_DISABLED ->
            "Device Location is switched off."
        LocationAvailability.WAITING_FOR_FIX ->
            "No fresh position has arrived yet."
        LocationAvailability.STALE ->
            "The last position is too old to use."
        LocationAvailability.READY ->
            "A position exists but is not usable for the search."
        LocationAvailability.UNKNOWN ->
            "The device cannot provide a position right now."
        null ->
            "ResQ has no position information yet."
    }

private fun ageValue(location: EmergencyLocationSnapshot?): String {
    val age = location?.ageMillis ?: return "Unknown"
    val seconds = age / 1000
    return when {
        seconds < 60 -> "$seconds s ago"
        else -> "${seconds / 60} min ago"
    }
}

/**
 * Precision, stated honestly.
 *
 * A coarse-only fix is labelled "approximate" rather than given a false-precision
 * number, because the platform itself only promised a neighbourhood.
 */
internal fun accuracyValue(location: EmergencyLocationSnapshot?): String {
    if (location == null || !location.hasCoordinates) return "Unknown"
    val meters = location.accuracyMeters?.toInt()
        ?: return if (location.isCoarse) "Approximate" else "Not reported"
    return if (location.isCoarse) "Approximate · ±$meters m" else "±$meters m"
}

/** Shown in the Reason row while an acquisition attempt is running. */
internal const val AcquiringCopy =
    "Waiting for a position from this device…"

/** Progress line under the diagnostics while an attempt is running. */
internal const val AcquiringProgressLabel =
    "Getting a position from GPS and network…"
