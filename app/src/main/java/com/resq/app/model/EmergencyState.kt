package com.resq.app.model

/**
 * Sequential progress of the emergency preparation flow. Steps always run in
 * declaration order, so a single value tells the UI which step is running,
 * which are done and which are still pending - no booleans required.
 */
enum class EmergencyProgress {
    LOCATING,
    LINKING_CONTACTS,
    SEARCHING_RESOURCES,
}

/**
 * Emergency lifecycle.
 *
 * INACTIVE -> no emergency on screen.
 * PREPARING -> location, contacts and resources are being assembled.
 * READY -> the resource section is shown; what it contains (loading, results,
 *          none found, an error, or an unusable location) is carried by
 *          [ResourceDiscoveryState].
 */
sealed interface EmergencyState {

    data object Inactive : EmergencyState

    data class Preparing(val progress: EmergencyProgress) : EmergencyState

    data class Ready(val resources: List<EmergencyResource>) : EmergencyState
}

/** Classification of a discovered emergency resource. */
enum class ResourceType {
    HOSPITAL,
    EMERGENCY_CARE,
    AMBULANCE,
    BLOOD_BANK,
}

/**
 * A single emergency resource, either a live search result or one of the local
 * [com.resq.app.data.MockData] fixtures.
 *
 * [isMock] tells the two apart so the UI can label prototype data explicitly
 * and never present it as a live result.
 *
 * Every field can be absent in a real search result - providers do not always
 * return an address, a phone number or coordinates - so nothing here may be
 * assumed non-null, and no availability is ever claimed: [availabilityLabel]
 * for live results only says whether the entry was verified or not.
 */
data class EmergencyResource(
    val type: ResourceType,
    val name: String,
    /** Straight-line distance in km, null when the backend could not compute one. */
    val distanceKm: Double?,
    val detail: String,
    val availability: SafetyState,
    val availabilityLabel: String,
    /** Provider-reported number, null when none was returned. Never invented. */
    val phone: String?,
    val address: String? = null,
    /**
     * Provider-reported destination for the Directions action, null when the
     * search did not return a usable position. These are never invented and are
     * never replaced by the user's own position: the resource is the destination,
     * so an entry without coordinates simply has no Directions action.
     */
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isMock: Boolean = false,
) {
    /**
     * True only when this resource carries a plausible, non-placeholder
     * destination AND that destination is different from [originLatitude] /
     * [originLongitude] (the user's own position), so Directions never routes a
     * user to where they already are.
     */
    fun hasDestinationFrom(originLatitude: Double?, originLongitude: Double?): Boolean {
        val target = destination() ?: return false
        val origin = if (originLatitude != null && originLongitude != null) {
            originLatitude to originLongitude
        } else {
            null
        }
        return origin == null || origin != target
    }

    /** Validated (latitude, longitude) pair, or null when there is no usable one. */
    fun destination(): Pair<Double, Double>? {
        val lat = latitude ?: return null
        val lon = longitude ?: return null
        if (!lat.isFinite() || !lon.isFinite()) return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        // (0, 0) is the classic "coordinate missing" sentinel and is open ocean;
        // treat it as no destination rather than routing anyone into the Gulf of
        // Guinea.
        if (lat == 0.0 && lon == 0.0) return null
        return lat to lon
    }
}
