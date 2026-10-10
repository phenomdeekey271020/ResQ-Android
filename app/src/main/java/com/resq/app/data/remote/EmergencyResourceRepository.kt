package com.resq.app.data.remote

import com.resq.app.model.EmergencyLocationSnapshot
import com.resq.app.model.EmergencyResource
import com.resq.app.model.ResourceSearchFailure
import com.resq.app.model.ResourceType
import com.resq.app.model.SafetyState

/**
 * Incident type sent to the backend for the accident flow. Matches one of the
 * backend's Literal values; the app only ever reports what it actually is - a
 * possible accident.
 */
const val IncidentTypePossibleAccident = "possible_accident"

/** Outcome of a nearby-resource search, already mapped to app models. */
sealed interface ResourceSearchResult {

    /** Resources, sorted nearest first. Never empty. */
    data class Success(
        val resources: List<EmergencyResource>,
        val searchedAt: String?,
    ) : ResourceSearchResult

    /** The search ran fine and found nothing in range. Not an error. */
    data object NoResults : ResourceSearchResult

    /** The search could not produce an answer. Carries a coarse reason only. */
    data class Failure(val reason: ResourceSearchFailure) : ResourceSearchResult
}

/**
 * Identity of the fix a search is based on.
 *
 * Provider, timestamp and coordinates together: the timestamp alone is not enough
 * (two providers can report the same instant) and coordinates alone are not either
 * (a provider re-sending an identical position must still count as the same fix).
 * Used purely to decide whether a NEW search is warranted, never sent anywhere.
 */
fun EmergencyLocationSnapshot.searchKey(): String? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    if (!lat.isFinite() || !lon.isFinite()) return null
    // The (0, 0) placeholder is not a position and can never identify a fix.
    if (lat == 0.0 && lon == 0.0) return null
    return "$provider|$timestampMillis|$lat|$lon"
}

/**
 * Repository the emergency flow calls for nearby resources.
 *
 * Implementations must be side-effect free apart from the single request per
 * call - no polling, no caching, no automatic retries. Deciding *when* to call
 * is the state machine's job.
 */
interface EmergencyResourceRepository {

    suspend fun searchNearbyResources(
        latitude: Double,
        longitude: Double,
        incidentType: String = IncidentTypePossibleAccident,
    ): ResourceSearchResult
}

/** Live implementation backed by the local FastAPI backend. */
class RemoteEmergencyResourceRepository(
    private val api: EmergencyResourcesApi,
) : EmergencyResourceRepository {

    override suspend fun searchNearbyResources(
        latitude: Double,
        longitude: Double,
        incidentType: String,
    ): ResourceSearchResult {
        val request = EmergencyResourcesSearchRequest(
            latitude = latitude,
            longitude = longitude,
            incidentType = incidentType,
        )
        return when (val outcome = api.search(request)) {
            is EmergencyResourcesApiResult.Parsed -> outcome.response.toSearchResult()

            is EmergencyResourcesApiResult.HttpError ->
                ResourceSearchResult.Failure(ResourceSearchFailure.SERVER)

            EmergencyResourcesApiResult.InvalidResponse ->
                ResourceSearchResult.Failure(ResourceSearchFailure.INVALID_RESPONSE)

            EmergencyResourcesApiResult.Unreachable ->
                ResourceSearchResult.Failure(ResourceSearchFailure.NETWORK)
        }
    }
}

/**
 * Classifies a parsed response. "no_results", an empty list and an unknown
 * status all reduce to [ResourceSearchResult.NoResults] rather than an error,
 * because a search that ran and found nothing is a valid answer. Results are
 * re-sorted nearest-first (missing distances sort last) so the guarantee holds
 * regardless of what the backend returns.
 */
internal fun EmergencyResourcesSearchResponse.toSearchResult(): ResourceSearchResult {
    val resources = resources
        .map { it.toEmergencyResource() }
        .sortedBy { it.distanceKm ?: Double.MAX_VALUE }
    return if (resources.isEmpty()) {
        ResourceSearchResult.NoResults
    } else {
        ResourceSearchResult.Success(resources, searchedAt)
    }
}

/**
 * Maps one backend resource onto the app model.
 *
 * Every optional field degrades to null rather than a placeholder: a missing
 * phone stays null (so no call action is ever offered for it), a missing
 * address stays null, and a missing distance stays null. Availability is
 * deliberately never claimed - the backend only tells us that a place exists,
 * not that it is open or that help is available.
 *
 * The returned coordinates are carried through to [EmergencyResource] because
 * the Directions action needs the resource's own position: without them the UI
 * can only offer a text search, and no destination is ever invented to fill
 * the gap.
 */
internal fun EmergencyResourceDto.toEmergencyResource(): EmergencyResource = EmergencyResource(
    type = type.toResourceType(),
    name = name?.trim()?.takeIf { it.isNotEmpty() } ?: UnnamedResourceLabel,
    distanceKm = distanceKm,
    detail = type.toTypeLabel(),
    availability = SafetyState.CAUTION,
    availabilityLabel = UnverifiedLabel,
    phone = phone?.trim()?.takeIf { it.isNotEmpty() },
    address = address?.trim()?.takeIf { it.isNotEmpty() },
    latitude = coordinates?.latitude,
    longitude = coordinates?.longitude,
    isMock = false,
)

private fun String?.toResourceType(): ResourceType = when (this) {
    "blood_bank" -> ResourceType.BLOOD_BANK
    "emergency_care" -> ResourceType.EMERGENCY_CARE
    "ambulance" -> ResourceType.AMBULANCE
    // "hospital" and anything unknown: a place you can go.
    else -> ResourceType.HOSPITAL
}

private fun String?.toTypeLabel(): String = when (this) {
    "hospital" -> "Hospital"
    "blood_bank" -> "Blood bank"
    "emergency_care" -> "Emergency care"
    "ambulance" -> "Ambulance"
    null -> "Resource"
    else -> this
        .replace('_', ' ')
        .trim()
        .replaceFirstChar { it.uppercaseChar() }
}

private const val UnnamedResourceLabel = "Unnamed resource"
private const val UnverifiedLabel = "Not verified"
