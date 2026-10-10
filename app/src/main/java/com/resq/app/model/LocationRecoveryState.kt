package com.resq.app.model

/*
 * THE STATE OF AN EXPLICIT "REFRESH LOCATION" ATTEMPT.
 *
 * This exists because of a concrete bug: pressing Refresh used to re-evaluate the
 * snapshot synchronously, which could only ever reproduce the same "no usable
 * location" verdict the screen was already showing. A position arrives
 * asynchronously, seconds later, through a LocationManager callback - so the
 * attempt had to become something with a beginning, a deadline and an outcome.
 *
 * Pure Kotlin, no Android and no timers: the transitions live in the ViewModel,
 * but the states and the copy that describes them live here so they are unit
 * testable and reviewable in one place.
 */
sealed interface LocationRecoveryState {

    /**
     * No attempt is running. This is also the resting state when the current
     * position is perfectly usable, so "Idle" never implies a problem.
     */
    data object Idle : LocationRecoveryState

    /**
     * A fresh acquisition attempt is in flight. The screen shows progress and the
     * Refresh button stays enabled as a retry, but no request can be duplicated.
     */
    data object Acquiring : LocationRecoveryState

    /**
     * The attempt finished without producing a usable fix, and [reason] says why
     * in terms the user can act on. The UI always offers another attempt: a failed
     * acquisition never blocks a retry.
     */
    data class Unavailable(val reason: LocationRecoveryReason) : LocationRecoveryState

    /** True while an attempt is running - the single "loading" flag of the flow. */
    val isBusy: Boolean get() = this is Acquiring
}

/** Why a location attempt produced nothing usable. */
enum class LocationRecoveryReason {

    /** The device-wide Location toggle is still off. */
    LOCATION_OFF,

    /** No location permission, or it was revoked in system settings. */
    PERMISSION_DENIED,

    /**
     * Location is on and permitted, but no fix arrived inside the attempt's
     * window - typically indoors, or with a weak GNSS signal.
     */
    TIMED_OUT,
}

/**
 * One honest sentence per failed attempt, shown under the recovery buttons.
 *
 * Deliberately never says "ResQ turned Location on" or implies the app can do
 * so: enabling a system setting is always the user's action.
 */
fun LocationRecoveryReason.label(): String = when (this) {
    LocationRecoveryReason.LOCATION_OFF ->
        "Device Location is still switched off. Turn it on in Android settings, " +
            "then come back - ResQ will retry by itself."

    LocationRecoveryReason.PERMISSION_DENIED ->
        "ResQ still does not have permission to use location. Grant it below, or " +
            "in Android app settings."

    LocationRecoveryReason.TIMED_OUT ->
        "No position arrived in time. Move somewhere with a clearer view of the " +
            "sky, or try again."
}

/** True when the only thing standing between the user and a fix is a settings trip. */
val LocationRecoveryReason.needsSystemSettings: Boolean
    get() = this == LocationRecoveryReason.LOCATION_OFF ||
        this == LocationRecoveryReason.PERMISSION_DENIED