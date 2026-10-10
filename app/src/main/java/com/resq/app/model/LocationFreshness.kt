package com.resq.app.model

/*
 * ONE PLACE WHERE "IS THIS FIX USABLE" IS DECIDED.
 *
 * Every other part of the app asks this file instead of inventing its own idea
 * of how old a position may be. That matters for an emergency app: a fix that is
 * five minutes old is a real measurement, but it is not evidence about where the
 * phone is right now, and it must never be presented as if it were.
 *
 * Pure Kotlin only - no Android, no timers - so every rule below is unit tested.
 */

/**
 * How much the latest reported fix can be trusted as *current* position.
 *
 * [FRESH] and [STALE] both carry real coordinates. The difference is purely how
 * old they are. [NO_FIX] means there is nothing to trust at all, whatever the
 * latitude and longitude fields happen to contain.
 */
enum class FixFreshness {
    /** Within the prototype freshness window: usable as current evidence. */
    FRESH,

    /** Real coordinates, but too old to describe where the phone is now. */
    STALE,

    /** No usable fix: no permission, providers off, or nothing received yet. */
    NO_FIX,
}

/** Diagnostics caption for a freshness verdict. */
val FixFreshness.label: String
    get() = when (this) {
        FixFreshness.FRESH -> "Fresh"
        FixFreshness.STALE -> "Stale"
        FixFreshness.NO_FIX -> "No fix"
    }

/**
 * The single freshness rule of the app.
 *
 * PROTOTYPE THRESHOLD: [LocationStaleAfterMillis] = 30 seconds. A fix older than
 * that is shown as last-known information but is never treated as fresh
 * emergency evidence, and never contributes motion context to the detector.
 *
 * The threshold lives in SensorState.kt and is referenced from here rather than
 * repeated, so there is exactly one number to change.
 */
object LocationFreshnessPolicy {

    /** True when [state] describes a position that is safe to act on right now. */
    fun isFresh(state: LocationState, nowMillis: Long): Boolean =
        classify(state, nowMillis) == FixFreshness.FRESH

    /**
     * Classifies a fix from its availability plus its age.
     *
     * Availability is checked first: an aged fix is only ever called [STALE] when
     * the gateway actually reported coordinates. A fix whose availability is
     * READY but whose timestamp is too old is downgraded to [STALE] here, so a
     * slow device that stopped delivering updates can never look live.
     *
     * A [LocationAvailability.STALE] verdict is deliberately STICKY: it is never
     * promoted back to [FRESH] by a newer clock. The gateway downgrades a fix as
     * time passes, and the conservative direction - away from claiming a position
     * is current - is the only safe one to be asymmetric.
     */
    fun classify(state: LocationState, nowMillis: Long): FixFreshness = when {
        !state.hasReading -> FixFreshness.NO_FIX
        state.availability == LocationAvailability.STALE -> FixFreshness.STALE
        isStaleFix(state.timestampMillis, nowMillis) -> FixFreshness.STALE
        else -> FixFreshness.FRESH
    }

    /** Age of the fix in milliseconds, or null when there is no fix at all. */
    fun ageMillis(state: LocationState, nowMillis: Long): Long? =
        if (state.hasReading) nowMillis - state.timestampMillis else null
}

/**
 * The best position the app can offer for the current emergency, plus the
 * metadata a reviewer needs to judge it.
 *
 * It is local state, not dispatch: while the flow is in EMERGENCY the snapshot
 * is used to search for nearby emergency resources on the configured ResQ
 * backend. It is never sent to emergency services or any third party, and it
 * carries its own freshness verdict so a stale last-known fix is always shown
 * as stale instead of being passed off as current - a stale fix is not even
 * used for the search.
 *
 * [latitude] and [longitude] are nullable on purpose - "no position" must be
 * representable without pretending the device is at 0,0 in the Gulf of Guinea.
 */
data class EmergencyLocationSnapshot(
    val availability: LocationAvailability = LocationAvailability.WAITING_FOR_FIX,
    val freshness: FixFreshness = FixFreshness.NO_FIX,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val provider: String? = null,
    val timestampMillis: Long = NoSensorTimestamp,
    val ageMillis: Long? = null,
    /**
     * True when the underlying grant was coarse only. Carried through so the UI
     * can say "approximate" rather than presenting a several-hundred-metre fix as
     * a precise position.
     */
    val isCoarse: Boolean = false,
) {
    /**
     * True only for a fix inside the freshness window. A stale last-known
     * position can be displayed but is NOT fresh emergency evidence: the
     * resource search refuses it, and anything else that acts on "where is the
     * user right now" must require this.
     */
    val isFresh: Boolean get() = freshness == FixFreshness.FRESH

    /** True when there are real coordinates to show at all, fresh or stale. */
    val hasCoordinates: Boolean get() = latitude != null && longitude != null

    /** One-line, truthful description for diagnostics. Never claims more. */
    val summary: String
        get() = when {
            !hasCoordinates -> "No position available (${availability.label})"
            isFresh -> "Fresh fix from ${provider ?: "unknown provider"} · " +
                "±${accuracyMeters?.toInt() ?: 0} m · ${ageMillis ?: 0} ms old"

            else -> "STALE last-known fix from ${provider ?: "unknown provider"} · " +
                "${ageMillis ?: 0} ms old - not current evidence"
        }
}

/**
 * Projects the live [LocationState] into an [EmergencyLocationSnapshot].
 *
 * Coordinates are only carried over when the state actually has a reading, so a
 * [LocationAvailability.WAITING_FOR_FIX] placeholder cannot masquerade as a
 * position at 0,0.
 */
fun LocationState.emergencySnapshot(nowMillis: Long): EmergencyLocationSnapshot {
    val verdict = LocationFreshnessPolicy.classify(this, nowMillis)
    return EmergencyLocationSnapshot(
        availability = availability,
        freshness = verdict,
        latitude = if (hasReading) latitude else null,
        longitude = if (hasReading) longitude else null,
        accuracyMeters = accuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        provider = provider,
        timestampMillis = timestampMillis,
        ageMillis = LocationFreshnessPolicy.ageMillis(this, nowMillis),
        isCoarse = isCoarse,
    )
}
