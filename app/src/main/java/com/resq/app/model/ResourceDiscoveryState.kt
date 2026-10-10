package com.resq.app.model

/**
 * Why a nearby-resource search could not produce a usable answer.
 *
 * Mirrors the repository's failure taxonomy so the UI can explain what went
 * wrong without ever showing a raw exception, a response body or any part of
 * the location that was searched for.
 */
enum class ResourceSearchFailure {
    /** The backend could not be reached at all (offline, DNS, refused). */
    NETWORK,

    /** The backend answered with an error status. */
    SERVER,

    /** The backend answered with something that is not the expected schema. */
    INVALID_RESPONSE,
}

/**
 * State of the nearby-resource search for the current emergency.
 *
 * IDLE          -> no search has been started for this emergency.
 * LOADING       -> a search is in flight right now.
 * LOADED        -> the search finished; [Loaded.resources] is nearest-first and
 *                  may legitimately be empty, which means "none found" - not an
 *                  error.
 * FAILED        -> the search ran but the backend was unreachable or errored;
 *                  the UI offers an explicit retry.
 * NO_USABLE_LOCATION -> no request was made because the emergency location
 *                  snapshot is missing or stale. The UI explains the problem
 *                  and offers the existing location-recovery actions. This is a
 *                  REVERSIBLE verdict, never a terminal one: a later fresh fix
 *                  clears it and permits exactly one new search.
 *
 * Searches start only when the state machine enters the resource-discovery
 * stage, when a genuinely new fresh fix arrives after this verdict, or when the
 * user explicitly retries or refreshes - never on recomposition, rotation or
 * repeated state emissions.
 */
sealed interface ResourceDiscoveryState {

    data object Idle : ResourceDiscoveryState

    data object Loading : ResourceDiscoveryState

    data class Loaded(val resources: List<EmergencyResource>) : ResourceDiscoveryState

    data class Failed(val reason: ResourceSearchFailure) : ResourceDiscoveryState

    data object NoUsableLocation : ResourceDiscoveryState
}
