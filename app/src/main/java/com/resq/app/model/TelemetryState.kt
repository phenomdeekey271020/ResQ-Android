package com.resq.app.model

/** Confidence of the (mock) detection layer that raised an incident. */
enum class DetectionConfidence {
    POSSIBLE,
    HIGH,
}

/** What the detection layer currently believes is happening. */
enum class DetectionState {
    IDLE,
    HARD_BRAKING,
    POSSIBLE_IMPACT,
    HIGH_CONFIDENCE_IMPACT,
}

/** Whether the sensor monitoring pipeline is engaged. */
enum class SensorStatus {
    MONITORING,
    PAUSED,
}

/** Quality of the (mock) location fix. */
enum class GpsStatus {
    LOCKED,
    STANDBY,
    ACQUIRING,
    UNAVAILABLE,
}

/** A single simulated IMU sample, in m/s² (accelerometer) or rad/s (gyroscope). */
data class SensorReading(
    val x: Float,
    val y: Float,
    val z: Float,
)

/**
 * Simulated protection telemetry. Every value here is local UI state produced by
 * the diagnostics controls - no Android sensor, GPS or network is involved.
 *
 * Real hardware readings live in [SensorState] instead.
 */
data class TelemetryState(
    val detection: DetectionState = DetectionState.IDLE,
    val sensors: SensorStatus = SensorStatus.MONITORING,
    val gps: GpsStatus = GpsStatus.LOCKED,
    val accidentScore: Float = BaselineAccidentScore,
    val accelerometer: SensorReading = SensorReading(0.02f, 0.05f, 9.81f),
    val gyroscope: SensorReading = SensorReading(0.01f, -0.02f, 0.00f),
)

/** Resting accident score used when nothing is being simulated. */
const val BaselineAccidentScore: Float = 0.08f
