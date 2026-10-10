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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resq.app.model.EmergencyContact
import com.resq.app.model.EmergencyLocationSnapshot
import com.resq.app.model.EmergencyProgress
import com.resq.app.model.EmergencyResource
import com.resq.app.model.EmergencyState
import com.resq.app.model.LocationRecoveryState
import com.resq.app.model.PrimaryContactCall
import com.resq.app.model.ResourceDiscoveryState
import com.resq.app.model.ResourceSearchFailure
import com.resq.app.model.ResourceType
import com.resq.app.model.SmsEscalation
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
 * Emergency Response. DESIGN.md 6.3: emergency atmosphere, header, location row,
 * a resource card, and the bottom Call / Directions actions.
 *
 * The resource card renders [ResourceDiscoveryState]: a loading row while the
 * search runs, the results nearest-first when they arrive, "none found" for an
 * empty but successful search, and an explanation plus an explicit retry after
 * a failure. No request is started here - recomposition, rotation and repeated
 * state emissions all render whatever the ViewModel already decided. Falls
 * through to the location unavailable state (6.4) when the position is missing
 * or too stale to search around.
 */
@Composable
fun EmergencyResponseScreen(
    emergency: EmergencyState,
    location: EmergencyLocationSnapshot?,
    discovery: ResourceDiscoveryState,
    recovery: LocationRecoveryState,
    contacts: List<EmergencyContact>,
    primaryCall: PrimaryContactCall,
    smsEscalation: SmsEscalation,
    onCallContact: (EmergencyContact) -> Unit,
    onStandDown: () -> Unit,
    onEnableLocation: () -> Unit,
    onRefreshLocation: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestCallPermission: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    onRetryResources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val ready = emergency is EmergencyState.Ready
    val resources = (discovery as? ResourceDiscoveryState.Loaded)?.resources
        ?: (emergency as? EmergencyState.Ready)?.resources.orEmpty()
    var selected by remember { mutableStateOf<EmergencyResource?>(null) }

    val current = selected
    if (current != null) {
        EmergencyResourceDetailsScreen(
            resource = current,
            onBack = { selected = null },
            onCall = { current.phone?.let { number -> dialNumber(context, number) } },
            // The selected resource's OWN coordinates are the destination. The
            // user's position is never substituted, and a resource without a
            // usable position simply has no Directions action.
            onDirections = {
                if (!openDirections(context, current.latitude, current.longitude)) {
                    showDirectionsUnavailableToast(context)
                }
            },
            modifier = modifier,
        )
        return
    }

    val locationUnavailable = ready && (
        discovery is ResourceDiscoveryState.NoUsableLocation ||
            location?.hasCoordinates != true
        )
    if (locationUnavailable) {
        LocationUnavailableScreen(
            location = location,
            recovery = recovery,
            resources = resources,
            contacts = contacts,
            primaryCall = primaryCall,
            smsEscalation = smsEscalation,
            onCallContact = onCallContact,
            onStandDown = onStandDown,
            onEnableLocation = onEnableLocation,
            onRefreshLocation = onRefreshLocation,
            onOpenLocationSettings = onOpenLocationSettings,
            onOpenAppSettings = onOpenAppSettings,
            onCallEmergency = { dialNumber(context, EmergencyNumber) },
            onOpenResource = { selected = it },
            modifier = modifier,
        )
        return
    }

    // The resource list is already nearest-first, so the first entry with a
    // trustworthy destination is the nearest one worth navigating to. When no
    // result carries a position the action is disabled rather than pointed at an
    // invented place.
    val directionsTarget = resources.firstOrNull {
        it.hasDestinationFrom(location?.latitude, location?.longitude)
    }

    EmergencyResponseContent(
        emergency = emergency,
        location = location,
        discovery = discovery,
        resources = resources,
        contacts = contacts,
        primaryCall = primaryCall,
        smsEscalation = smsEscalation,
        onCallContact = onCallContact,
        onStandDown = onStandDown,
        directionsEnabled = directionsTarget != null,
        onOpenResource = { selected = it },
        onRetryResources = onRetryResources,
        onRequestCallPermission = onRequestCallPermission,
        onRequestSmsPermission = onRequestSmsPermission,
        onCallEmergency = { dialNumber(context, EmergencyNumber) },
        onDirections = {
            if (directionsTarget != null &&
                !openDirections(context, directionsTarget.latitude, directionsTarget.longitude)
            ) {
                // Nothing on the device could open the link. The old behaviour
                // swallowed the failure and returned true/false to nobody, so the
                // tap looked like a silent no-op in the middle of an emergency.
                showDirectionsUnavailableToast(context)
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun EmergencyResponseContent(
    emergency: EmergencyState,
    location: EmergencyLocationSnapshot?,
    discovery: ResourceDiscoveryState,
    resources: List<EmergencyResource>,
    contacts: List<EmergencyContact>,
    primaryCall: PrimaryContactCall,
    smsEscalation: SmsEscalation,
    onCallContact: (EmergencyContact) -> Unit,
    onStandDown: () -> Unit,
    directionsEnabled: Boolean,
    onOpenResource: (EmergencyResource) -> Unit,
    onRetryResources: () -> Unit,
    onRequestCallPermission: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    onCallEmergency: () -> Unit,
    onDirections: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalResQColors.current
    val ready = emergency is EmergencyState.Ready
    val primary = contacts.firstOrNull { it.isPrimary } ?: contacts.firstOrNull()

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = ResQSpacing.gutter, vertical = ResQSpacing.md),
    ) {
        // One deterministic in-app back control - the same handler the system
        // Back gesture uses (see ResQApp), so the two can never disagree.
        EmergencyBackArrow(onBack = onStandDown)

        EmergencyHeader(
            badge = "Emergency active",
            title = if (ready) "Review nearby resources" else "Preparing emergency",
            subtitle = if (ready) {
                // This line used to claim, unconditionally, that no message had
                // been sent. With the alert text in the app that is no longer
                // always true, and a banner that lies is worse than no banner -
                // so it now states only what is unconditionally true: the nearby
                // RESOURCES are a discovery, separate from the escalation actions
                // reported in the contacts section below.
                "Nearby resources are for discovery only."
            } else {
                progressCopy(emergency)
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
            Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
                SectionLabel("Location")
                GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                    GlassRow(
                        label = "Position",
                        value = locationValue(location),
                        valueColor = if (location?.isFresh == true) colors.accent else colors.textMuted,
                        showDot = location?.isFresh == true,
                    )
                    RowDivider()
                    GlassRow(
                        label = "Location age",
                        value = locationAgeValue(location),
                        valueColor = colors.textMuted,
                    )
                }
            }

            if (ready) {
                ResourceSection(
                    discovery = discovery,
                    resources = resources,
                    onOpenResource = onOpenResource,
                    onRetryResources = onRetryResources,
                )
            }

            // Always shown, ready or not: the trusted contacts are the part of the
            // emergency experience that does not depend on location, on the
            // backend or on an automatic call having worked.
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
            ResQPrimaryButton(
                text = "Call $EmergencyNumber",
                onClick = onCallEmergency,
                modifier = Modifier.fillMaxWidth(),
            )

            // The manual escape hatch, promoted to a real button rather than a
            // row in a list. When the automatic escalation was refused, blocked
            // or never configured, this is the one action that must remain
            // obvious - so it is offered above Directions, which is optional.
            if (primary != null) {
                ResQPrimaryButton(
                    text = "Call ${primary.name}",
                    onClick = { onCallContact(primary) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Only shown when the automatic call was actually blocked by the
            // missing permission. Offering it otherwise would be noise: the user
            // has already made the decision, and re-prompting mid-emergency is
            // exactly the wrong moment to ask for a sensitive permission.
            if (primaryCall is PrimaryContactCall.CallPermissionRequired) {
                ResQSecondaryButton(
                    text = "Allow ResQ to call contacts",
                    onClick = onRequestCallPermission,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            /*
             * Offered ONLY when the automatic path was actually blocked by the
             * missing SEND_SMS grant - never for a user who has not consented.
             *
             * The distinction is deliberate and follows the same reasoning as the
             * CALL_PHONE button above: a permission prompt during an emergency is
             * coercive, but so is asking someone to agree to spending their
             * mobile credit at the worst possible moment. A user who did not
             * enable alert texts is told they can do so in Settings; they are not
             * handed a one-tap way to change their mind while the incident runs.
             */
            if (smsEscalation is SmsEscalation.SmsPermissionRequired) {
                ResQSecondaryButton(
                    text = "Allow alert texts",
                    onClick = onRequestSmsPermission,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ResQSecondaryButton(
                text = "Directions",
                onClick = onDirections,
                enabled = directionsEnabled,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!directionsEnabled) {
                Text(
                    text = "Directions needs a result with a location. None was returned yet.",
                    style = ResQTextStyles.caption,
                    color = colors.textMuted,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Every state the resource card can be in: searching, results, nothing found,
 * or a failure with a retry action. Purely a render of what the ViewModel
 * already holds - it never starts a request of its own.
 */
@Composable
private fun ResourceSection(
    discovery: ResourceDiscoveryState,
    resources: List<EmergencyResource>,
    onOpenResource: (EmergencyResource) -> Unit,
    onRetryResources: () -> Unit,
) {
    val colors = LocalResQColors.current
    val nearestIndex = nearestHospitalIndex(resources)
    Column(verticalArrangement = Arrangement.spacedBy(ResQSpacing.sm)) {
        SectionLabel(sectionLabel(discovery, resources))
        when (discovery) {
            is ResourceDiscoveryState.Loaded -> {
                if (resources.isEmpty()) {
                    GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "No nearby resources found in this area.",
                            style = ResQTextStyles.row,
                            color = colors.textMuted,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                } else {
                    GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                        resources.forEachIndexed { index, resource ->
                            ResourceRow(
                                resource = resource,
                                nearest = index == nearestIndex,
                                onClick = { onOpenResource(resource) },
                            )
                            if (index != resources.lastIndex) {
                                com.resq.app.ui.components.RowDivider()
                            }
                        }
                    }
                    // The recommendation, when the data supports one, is stated
                    // as what it actually is: distance, not medical judgement.
                    nearestRecommendationCopy(resources)?.let { copy ->
                        Text(
                            text = copy,
                            style = ResQTextStyles.caption,
                            color = colors.textMuted,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            is ResourceDiscoveryState.Failed -> {
                GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = failureCopy(discovery.reason),
                        style = ResQTextStyles.row,
                        color = colors.textMuted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                ResQSecondaryButton(
                    text = "Retry search",
                    onClick = onRetryResources,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ResourceDiscoveryState.Loading, ResourceDiscoveryState.Idle -> {
                GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Searching for nearby resources…",
                            style = ResQTextStyles.row,
                            color = colors.textMuted,
                        )
                    }
                }
            }

            ResourceDiscoveryState.NoUsableLocation -> {
                GlassRowGroup(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Waiting for a usable location before searching.",
                        style = ResQTextStyles.row,
                        color = colors.textMuted,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResourceRow(
    resource: EmergencyResource,
    onClick: () -> Unit,
    nearest: Boolean = false,
) {
    val colors = LocalResQColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (nearest) {
                Text(
                    text = NearestBadgeLabel,
                    style = ResQTextStyles.sectionLabel,
                    color = colors.accent,
                )
            }
            Text(
                text = resource.name,
                style = ResQTextStyles.rowStrong,
                color = colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatDistance(resource.distanceKm)} · ${resource.availabilityLabel}",
                style = ResQTextStyles.caption,
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (resource.address != null) {
                Text(
                    text = resource.address,
                    style = ResQTextStyles.caption,
                    color = colors.textMuted,
                    // Wraps to a second line when needed instead of running into
                    // the row below; the column's weight bounds it either way.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            resource.phone?.takeIf { it.isNotBlank() }?.let { phone ->
                Text(
                    text = phone,
                    style = ResQTextStyles.caption,
                    color = colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ChevronRight(color = colors.textMuted)
    }
}

@Composable
private fun progressCopy(emergency: EmergencyState): String = when (emergency) {
    is EmergencyState.Preparing -> when (emergency.progress) {
        EmergencyProgress.LOCATING -> "Finding your location on this device."
        EmergencyProgress.LINKING_CONTACTS -> "Preparing your emergency contacts."
        EmergencyProgress.SEARCHING_RESOURCES -> "Looking for nearby resources."
    }

    else -> "Preparing emergency information."
}

/**
 * Precision of the current position, stated honestly.
 *
 * A coarse fix is labelled approximate. Showing "±5 m" for a fix that may be a
 * kilometre out would be a safety problem, not a cosmetic one.
 */
internal fun locationValue(location: EmergencyLocationSnapshot?): String = when {
    location == null -> "No fix"
    !location.hasCoordinates -> "No fix"
    location.isCoarse -> "Approximate position"
    location.isFresh -> "±${location.accuracyMeters?.toInt() ?: 0} m"
    else -> "Last known (stale)"
}

/** How old the displayed position is. Always shown next to it. */
internal fun locationAgeValue(location: EmergencyLocationSnapshot?): String {
    val age = location?.ageMillis ?: return "Unknown"
    val seconds = age / 1000
    return when {
        seconds < 60 -> "$seconds s ago"
        else -> "${seconds / 60} min ago"
    }
}

/** Section heading that never lets prototype data read as live results. */
private fun sectionLabel(
    discovery: ResourceDiscoveryState,
    resources: List<EmergencyResource>,
): String = when {
    resources.isNotEmpty() && resources.all { it.isMock } -> "Nearest resources (prototype data)"
    discovery is ResourceDiscoveryState.Loaded -> "Nearest resources"
    else -> "Nearby resources"
}

/**
 * Index of the resource the "nearest" highlight belongs to: the first hospital
 * that actually carries a distance. Results are already sorted nearest-first by
 * the repository, and a hospital without a distance can never be claimed to be
 * nearest anything. -1 means the highlight is not earned by this data.
 */
internal fun nearestHospitalIndex(resources: List<EmergencyResource>): Int =
    resources.indexOfFirst { it.type == ResourceType.HOSPITAL && it.distanceKm != null }

/** Badge shown on the row of the nearest hospital. */
internal const val NearestBadgeLabel = "Nearest · by distance"

/**
 * The honest recommendation sentence, shown only when a nearest hospital can
 * actually be identified. Distance is the whole basis of the claim - ResQ does
 * not know which hospital suits this emergency, and never pretends otherwise.
 */
internal fun nearestRecommendationCopy(resources: List<EmergencyResource>): String? =
    if (nearestHospitalIndex(resources) >= 0) {
        "Nearest hospital is chosen by distance only — ResQ cannot tell which " +
            "hospital suits your situation."
    } else {
        null
    }

/** True when this resource type has an ownership notion worth displaying. */
internal val EmergencyResource.showsOwnership: Boolean
    get() = type == ResourceType.HOSPITAL || type == ResourceType.EMERGENCY_CARE

/** Coarse, body-free explanations for a failed search. */
internal fun failureCopy(reason: ResourceSearchFailure): String = when (reason) {
    ResourceSearchFailure.NETWORK ->
        "Could not reach the resource service. Check the connection and retry."
    ResourceSearchFailure.SERVER ->
        "The resource service had a problem. Retry in a moment."
    ResourceSearchFailure.INVALID_RESPONSE ->
        "The resource service sent an unexpected response. Retry."
}

internal fun formatDistance(distanceKm: Double?): String =
    distanceKm?.let { String.format(Locale.US, "%.1f km", it) } ?: "Distance unknown"

/** Regional emergency number. A placeholder until a locale lookup exists. */
internal const val EmergencyNumber = "112"

/** Small helper used by the resource-type badge. */
internal fun ResourceType.shortLabel(): String = when (this) {
    ResourceType.HOSPITAL -> "Hospital"
    ResourceType.EMERGENCY_CARE -> "Emergency care"
    ResourceType.AMBULANCE -> "Ambulance"
    ResourceType.BLOOD_BANK -> "Blood bank"
}
