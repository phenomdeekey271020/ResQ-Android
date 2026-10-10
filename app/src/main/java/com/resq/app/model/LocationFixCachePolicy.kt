package com.resq.app.model

/*
 * THE CACHE OF RECENT VALID LOCATION FIXES.
 *
 * An accident is detected seconds to minutes after the phone was last moving, so
 * the single most useful thing ResQ can do is have a trustworthy position already
 * in hand BEFORE anything goes wrong. This file is that pre-incident cache, and
 * it is pure Kotlin so every rule below is unit tested without a device.
 *
 * Three rules are enforced here and nowhere else:
 *
 *  1. A cached fix is a MEASUREMENT, not a promise. It is stored with the
 *     timestamp, accuracy and approximate flag the platform reported, and it is
 *     re-classified against the clock every single time it is read. It is never
 *     relabelled as the current position.
 *
 *  2. A cached fix can only ever be HISTORICAL evidence. It is deliberately
 *     impossible for this cache to produce a FixFreshness.FRESH verdict: the
 *     emergency search must never run around a position nobody is standing at.
 *
 *  3. An expired, placeholder or malformed entry is not a fix at all, and is
 *     discarded rather than shown.
 */

private const val HistoricalRetentionMillis = 30 * 60_000L
private const val HistoricalUsableAccuracyMeters = 500f
private const val SearchCeilingAccuracyMeters = 5_000f
private const val MinLatitude = -90.0
private const val MaxLatitude = 90.0
private const val MinLongitude = -180.0
private const val MaxLongitude = 180.0

/** The outcome of asking the cache for the best position it holds. */
sealed interface LocationFixAvailability {

    /** A real measurement that is recent enough to show as historical context. */
    data class Usable(val fix: LastKnownLocationFix) : LocationFixAvailability

    /**
     * The cache holds something, but it is too old, too imprecise or malformed to
     * be shown as a position at all. Carries the reason so the UI can be honest
     * instead of blank.
     */
    data class Unusable(val reason: String) : LocationFixAvailability
}

/**
 * A position ResQ measured earlier and kept, with the metadata that decides how
 * much it can still be trusted.
 *
 * [latitude]/[longitude] are non-null here precisely because the validation rules
 * live in [LocationFixCachePolicy]: a stored fix always has coordinates, and the
 * (0, 0) placeholder can never enter the cache in the first place.
 */
data class LastKnownLocationFix(
    val latitude: Double,
    val longitude: Double,
    val timestampMillis: Long,
    val accuracyMeters: Float?,
    val isCoarse: Boolean,
    val provider: String?,
)

/**
 * ONE PLACE WHERE A CACHED FIX IS JUDGED.
 *
 * The cache is written optimistically (every good fix is stored, so nothing that
 * could help is thrown away) and judged strictly on every read, because the
 * conservative direction is the only safe one to be asymmetric.
 */
object LocationFixCachePolicy {

    /**
     * How long a cached fix remains worth showing.
     *
     * THIRTY MINUTES is a judgement call, not a platform limit. It is long enough
     * that a user who enabled Location, drove somewhere and then had an incident
     * still has a real position to give; short enough that a phone left off for a
     * night does not present yesterday's coordinates as if they meant something.
     */
    const val RetentionMillis: Long = HistoricalRetentionMillis

    /**
     * Above this accuracy the fix is a genuine measurement but too vague to be
     * worth showing as a position - a cell-tower estimate with a two kilometre
     * radius tells a rescuer very little.
     */
    const val UsableAccuracyMeters: Float = HistoricalUsableAccuracyMeters

    /** The loosest accuracy that may still influence the nearby-resource search. */
    const val SearchAccuracyCeilingMeters: Float = SearchCeilingAccuracyMeters

    /** What the cache says right now. Never throws, never returns a guess. */
    fun availability(fix: LastKnownLocationFix?, nowMillis: Long): LocationFixAvailability {
        if (fix == null) return LocationFixAvailability.Unusable("No position has been cached yet")
        if (!isPlausiblePosition(fix.latitude, fix.longitude)) {
            return LocationFixAvailability.Unusable("The cached position was not valid")
        }
        if (fix.timestampMillis <= NoSensorTimestamp) {
            return LocationFixAvailability.Unusable("The cached position has no usable timestamp")
        }
        val age = nowMillis - fix.timestampMillis
        if (age > RetentionMillis) {
            return LocationFixAvailability.Unusable("The cached position is too old to show")
        }
        val accuracy = fix.accuracyMeters
        if (accuracy != null && accuracy > UsableAccuracyMeters) {
            return LocationFixAvailability.Unusable("The cached position is too imprecise to show")
        }
        return LocationFixAvailability.Usable(fix)
    }

    /**
     * The best cached fix, or null when there is nothing worth showing.
     *
     * This is the ONLY read path the app uses, so "is this usable" can never be
     * answered two different ways in two different places.
     */
    fun best(fix: LastKnownLocationFix?, nowMillis: Long): LastKnownLocationFix? =
        (availability(fix, nowMillis) as? LocationFixAvailability.Usable)?.fix

    /**
     * Builds the [EmergencyLocationSnapshot] for a cached fix.
     *
     * Returns null when nothing usable is cached, which is what makes "no cached
     * position" representable without inventing one.
     *
     * The freshness verdict is FORCED to [FixFreshness.STALE] rather than being
     * derived from the clock. A cached fix that happens to be four seconds old is
     * still not evidence about where the phone is right now: the device may have
     * moved, and nothing in the cache knows that. Deriving the verdict from the
     * age here would be exactly the "relabel it as the current position" failure
     * this cache exists to prevent.
     */
    fun historicalSnapshot(fix: LastKnownLocationFix?, nowMillis: Long): EmergencyLocationSnapshot? {
        val usable = best(fix, nowMillis) ?: return null
        return EmergencyLocationSnapshot(
            // PROVIDERS_DISABLED would be a lie about WHY there is no live fix, so
            // the honest availability for "we only have history" is STALE.
            availability = LocationAvailability.STALE,
            freshness = FixFreshness.STALE,
            latitude = usable.latitude,
            longitude = usable.longitude,
            accuracyMeters = usable.accuracyMeters,
            speedMetersPerSecond = null,
            provider = usable.provider,
            timestampMillis = usable.timestampMillis,
            ageMillis = (nowMillis - usable.timestampMillis).coerceAtLeast(0L),
            isCoarse = usable.isCoarse,
        )
    }

    /**
     * True when this fix is precise enough to narrow a nearby-resource search.
     *
     * An unknown accuracy (the platform did not report one) is accepted rather
     * than rejected: the platform withholding a number is not evidence of a bad
     * position, and refusing it would throw away good fixes.
     */
    fun isAccurateEnoughForSearch(fix: LastKnownLocationFix?): Boolean {
        val accuracy = fix?.accuracyMeters ?: return fix != null
        if (!accuracy.isFinite() || accuracy < 0f) return false
        return accuracy <= SearchAccuracyCeilingMeters
    }

    /**
     * Validates a fix BEFORE it is written to the cache.
     *
     * The (0, 0) placeholder, a non-finite coordinate and an out-of-range
     * coordinate are all rejected here, which is what guarantees the cache can
     * never later supply a position ResQ would have to apologise for.
     */
    fun isCacheable(fix: LastKnownLocationFix): Boolean =
        isPlausiblePosition(fix.latitude, fix.longitude) &&
            fix.timestampMillis > NoSensorTimestamp &&
            // A negative accuracy is the platform's "unknown" sentinel; it is
            // stored rather than treated as a good or a bad measurement.
            (fix.accuracyMeters == null || !fix.accuracyMeters.isNaN())

    /** Coordinates that could describe a real place on Earth. */
    fun isPlausiblePosition(latitude: Double, longitude: Double): Boolean {
        if (!latitude.isFinite() || !longitude.isFinite()) return false
        if (latitude < MinLatitude || latitude > MaxLatitude) return false
        if (longitude < MinLongitude || longitude > MaxLongitude) return false
        // The classic "no position" sentinel. Treating it as a fix would point
        // every user at the Gulf of Guinea.
        if (latitude == 0.0 && longitude == 0.0) return false
        return true
    }
}

/**
 * Converts a live [LocationState] into the cache entry it should produce.
 *
 * Returns null when the state carries no reading, so a WAITING_FOR_FIX or
 * PROVIDERS_DISABLED placeholder can never overwrite a real cached measurement.
 */
fun LocationState.toCacheableFix(): LastKnownLocationFix? {
    if (!hasReading) return null
    val fix = LastKnownLocationFix(
        latitude = latitude,
        longitude = longitude,
        timestampMillis = timestampMillis,
        accuracyMeters = accuracyMeters,
        isCoarse = isCoarse,
        provider = provider,
    )
    return fix.takeIf { LocationFixCachePolicy.isCacheable(it) }
}