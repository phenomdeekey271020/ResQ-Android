package com.resq.app.detection

import com.resq.app.model.AccelerometerState
import com.resq.app.model.GyroscopeState
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationState
import com.resq.app.model.SensorAvailability
import com.resq.app.model.SensorState

/*
 * Deterministic synthetic sensor sequences for the Diagnostics simulation
 * controls and for the unit tests.
 *
 * These are ordinary [SensorState] snapshots: the same detector and the same
 * state machine score them exactly as they would score real hardware, so a
 * simulated scenario and a real one cannot take different code paths.
 *
 * Nothing here was recorded from a real crash, and no physical crash test may be
 * used to produce these numbers.
 */

/** Where the synthetic sequences start. Fixed so every run is identical. */
const val SyntheticSampleStartMillis: Long = 0L

/** Spacing between synthetic samples, matching a fast SENSOR_DELAY_UI stream. */
const val SyntheticSampleIntervalMillis: Long = 40L

object SimulationSamples {

    /** A phone sitting still, then being picked up and carried. Stays NORMAL. */
    fun normal(count: Int = 8): List<SensorState> = sequence(count) { index ->
        AccelerometerState(
            availability = SensorAvailability.READY,
            x = 0.05f,
            y = 0.04f,
            z = 9.81f + (index % 2) * 0.02f,
        ) to GyroscopeState(
            availability = SensorAvailability.READY,
            x = 0.03f,
            y = -0.02f,
            z = 0.01f,
        )
    }

    /**
     * Firm braking: a steady deceleration of roughly 0.13 g, which is a
     * believable hard stop, not a collision. It must stay NORMAL forever.
     */
    fun hardBraking(count: Int = 8): List<SensorState> = sequence(count) { index ->
        AccelerometerState(
            availability = SensorAvailability.READY,
            x = 1.12f,
            y = -0.64f,
            z = 8.42f - (index % 2) * 0.01f,
        ) to GyroscopeState(
            availability = SensorAvailability.READY,
            x = 0.14f,
            y = -0.09f,
            z = 0.06f,
        )
    }

    /**
     * A moderate impact: strong acceleration together with rotation, repeated
     * for a few samples. Enough agreeing evidence to open the "are you okay?"
     * window, which is still not an emergency.
     */
    fun possibleCollision(count: Int = 5): List<SensorState> = sequence(count) { index ->
        AccelerometerState(
            availability = SensorAvailability.READY,
            x = 28.0f,
            y = 4.0f,
            z = -6.0f + (index % 2) * 0.5f,
        ) to GyroscopeState(
            availability = SensorAvailability.READY,
            x = 4.2f,
            y = 2.6f,
            z = -1.1f,
        )
    }

    /**
     * A violent impact and tumble: saturated acceleration and rotation sustained
     * across the whole window. Reaches HIGH_CONFIDENCE, which shortens the
     * countdown - it still only opens the confirmation window.
     */
    fun highConfidenceCollision(count: Int = 8): List<SensorState> = sequence(count) { index ->
        AccelerometerState(
            availability = SensorAvailability.READY,
            x = 36.0f,
            y = 8.0f,
            z = -10.0f + (index % 2) * 0.5f,
        ) to GyroscopeState(
            availability = SensorAvailability.READY,
            x = 6.4f,
            y = 3.1f,
            z = -1.8f,
        )
    }

    /** Same impact acceleration with no gyroscope data at all. */
    fun accelerationWithoutGyroscope(count: Int = 6): List<SensorState> =
        sequence(count) { _ ->
            AccelerometerState(
                availability = SensorAvailability.READY,
                x = 36.0f,
                y = 8.0f,
                z = -10.0f,
            ) to GyroscopeState(availability = SensorAvailability.UNAVAILABLE)
        }

    /** A violent tumble with no accelerometer data at all. */
    fun rotationWithoutAccelerometer(count: Int = 6): List<SensorState> =
        sequence(count) { _ ->
            AccelerometerState(availability = SensorAvailability.UNAVAILABLE) to
                GyroscopeState(
                    availability = SensorAvailability.READY,
                    x = 6.4f,
                    y = 3.1f,
                    z = -1.8f,
                )
        }

    /** A vehicle reporting a high speed on a fresh fix. Adds context evidence. */
    fun movingAtRoadSpeed(): LocationState = LocationState(
        availability = LocationAvailability.READY,
        latitude = 12.971599,
        longitude = 77.594566,
        accuracyMeters = 6.5f,
        speedMetersPerSecond = 18.4f,
        provider = "gps",
        timestampMillis = SyntheticSampleStartMillis,
    )

    /** An old fix that still carries coordinates and a speed. */
    fun staleLastKnownFix(): LocationState = movingAtRoadSpeed().copy(
        availability = LocationAvailability.STALE,
        timestampMillis = SyntheticSampleStartMillis - 5 * 60_000L,
    )

    private inline fun sequence(
        count: Int,
        sample: (Int) -> Pair<AccelerometerState, GyroscopeState>,
    ): List<SensorState> = (0 until count).map { index ->
        val (accelerometer, gyroscope) = sample(index)
        SensorState(
            accelerometer = accelerometer.copy(
                timestampMillis = SyntheticSampleStartMillis +
                    index * SyntheticSampleIntervalMillis,
            ),
            gyroscope = gyroscope.copy(
                timestampMillis = SyntheticSampleStartMillis +
                    index * SyntheticSampleIntervalMillis,
            ),
        )
    }
}