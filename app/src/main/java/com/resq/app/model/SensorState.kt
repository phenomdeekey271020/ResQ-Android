package com.resq.app.model

/*
 * Immutable snapshots of the real device hardware read by Task 3.
 *
 * These models only describe what was measured - they never interpret the
 * values. Accident thresholds, confidence scoring and emergency escalation are
 * deliberately absent and belong to the next task.
 */

/** Timestamp sentinel meaning "this sample was never produced". */
const val NoSensorTimestamp: Long = 0L

/** A fix older than this is surfaced as a stale last-known position. */
const val LocationStaleAfterMillis: Long = 30_000L

/** Whether a motion sensor is currently streaming usable data. */
enum class SensorAvailability {
    READY,
    WAITING,
    UNAVAILABLE,
}

/** Why a real position is not currently available. */
enum class LocationAvailability {
    WAITING_FOR_PERMISSION,
    PERMISSION_DENIED,
    PROVIDERS_DISABLED,
    WAITING_FOR_FIX,
    STALE,
    UNKNOWN,
    READY,
}

/** Real TYPE_ACCELEROMETER sample, in m/s² including gravity. */
data class AccelerometerState(
    val availability: SensorAvailability = SensorAvailability.WAITING,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val timestampMillis: Long = NoSensorTimestamp,
) {
    /** True only when the numbers below came from the hardware. */
    val hasReading: Boolean get() = availability == SensorAvailability.READY

    /** Projection onto the shared [SensorReading] shape used by the UI. */
    val reading: SensorReading?
        get() = if (hasReading) SensorReading(x, y, z) else null
}

/** Real TYPE_GYROSCOPE sample, in rad/s. */
data class GyroscopeState(
    val availability: SensorAvailability = SensorAvailability.WAITING,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f,
    val timestampMillis: Long = NoSensorTimestamp,
) {
    /** True only when the numbers below came from the hardware. */
    val hasReading: Boolean get() = availability == SensorAvailability.READY

    /** Projection onto the shared [SensorReading] shape used by the UI. */
    val reading: SensorReading?
        get() = if (hasReading) SensorReading(x, y, z) else null
}

/**
 * Real position fix. Null accuracy or speed means the platform reported that
 * attribute as unknown - it is never substituted with a made-up number.
 */
data class LocationState(
    val availability: LocationAvailability = LocationAvailability.WAITING_FOR_PERMISSION,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val accuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val provider: String? = null,
    val timestampMillis: Long = NoSensorTimestamp,
    /**
     * True when this fix can only have come from the COARSE location grant.
     *
     * It is recorded rather than inferred from a large accuracy radius, because
     * an emergency app that shows a coarse fix as a precise one is lying about
     * where someone is. Carried through to the UI so the precision can be
     * stated honestly.
     */
    val isCoarse: Boolean = false,
) {
    /** True when real coordinates are present, including an aged last-known fix. */
    val hasReading: Boolean
        get() = availability == LocationAvailability.READY ||
            availability == LocationAvailability.STALE
}

/** Everything the sensor layer reports, kept separate from simulated telemetry. */
data class SensorState(
    val accelerometer: AccelerometerState = AccelerometerState(),
    val gyroscope: GyroscopeState = GyroscopeState(),
    val location: LocationState = LocationState(),
)

/** Diagnostics caption for a motion sensor. */
val SensorAvailability.label: String
    get() = when (this) {
        SensorAvailability.READY -> "Live"
        SensorAvailability.WAITING -> "Waiting for sensor data"
        SensorAvailability.UNAVAILABLE -> "Sensor unavailable"
    }

/** Diagnostics caption for the location layer. */
val LocationAvailability.label: String
    get() = when (this) {
        LocationAvailability.READY -> "Live"
        LocationAvailability.WAITING_FOR_PERMISSION -> "Waiting for permission"
        LocationAvailability.PERMISSION_DENIED -> "Permission denied"
        LocationAvailability.PROVIDERS_DISABLED -> "Location services off"
        LocationAvailability.WAITING_FOR_FIX -> "Waiting for location"
        LocationAvailability.STALE -> "Last known fix (stale)"
        LocationAvailability.UNKNOWN -> "Location unavailable"
    }

/**
 * Pure staleness rule for a fix. A fix with no timestamp is treated as stale so
 * the UI never presents a position it cannot vouch for.
 */
fun isStaleFix(fixTimestampMillis: Long, nowMillis: Long): Boolean =
    fixTimestampMillis <= NoSensorTimestamp || nowMillis - fixTimestampMillis > LocationStaleAfterMillis
