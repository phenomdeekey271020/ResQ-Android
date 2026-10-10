package com.resq.app

import com.resq.app.model.AccelerometerState
import com.resq.app.model.GyroscopeState
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationState
import com.resq.app.model.LocationStaleAfterMillis
import com.resq.app.model.NoSensorTimestamp
import com.resq.app.model.SensorAvailability
import com.resq.app.model.SensorReading
import com.resq.app.model.SensorState
import com.resq.app.model.isStaleFix
import com.resq.app.model.label
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the sensor state models. The hardware itself is never touched -
 * these cover the rules the UI relies on to decide whether a number may be shown.
 */
class SensorStateTest {

    @Test
    fun defaultStates_areWaitingAndCarryNoReading() {
        val sensors = SensorState()

        assertEquals(SensorAvailability.WAITING, sensors.accelerometer.availability)
        assertEquals(SensorAvailability.WAITING, sensors.gyroscope.availability)
        assertEquals(
            LocationAvailability.WAITING_FOR_PERMISSION,
            sensors.location.availability,
        )
        assertFalse(sensors.accelerometer.hasReading)
        assertFalse(sensors.gyroscope.hasReading)
        assertFalse(sensors.location.hasReading)
        assertEquals(NoSensorTimestamp, sensors.accelerometer.timestampMillis)
    }

    @Test
    fun unavailableSensors_neverExposeAReading() {
        val accelerometer = AccelerometerState(
            availability = SensorAvailability.UNAVAILABLE,
            x = 1f,
            y = 2f,
            z = 3f,
        )
        val gyroscope = GyroscopeState(availability = SensorAvailability.UNAVAILABLE)

        assertNull(accelerometer.reading)
        assertNull(gyroscope.reading)
        assertEquals("Sensor unavailable", SensorAvailability.UNAVAILABLE.label)
    }

    @Test
    fun readySensors_exposeValuesAndTimestamp() {
        val accelerometer = AccelerometerState(
            availability = SensorAvailability.READY,
            x = 0.5f,
            y = -0.25f,
            z = 9.81f,
            timestampMillis = 1_700_000_000_000L,
        )

        assertTrue(accelerometer.hasReading)
        assertEquals(SensorReading(0.5f, -0.25f, 9.81f), accelerometer.reading)
        assertEquals(1_700_000_000_000L, accelerometer.timestampMillis)
        assertEquals("Live", SensorAvailability.READY.label)
    }

    @Test
    fun sensorState_isImmutableAndCopiesPerChannel() {
        val initial = SensorState()
        val updated = initial.copy(
            gyroscope = GyroscopeState(
                availability = SensorAvailability.READY,
                x = 0.1f,
                y = 0.2f,
                z = 0.3f,
                timestampMillis = 42L,
            ),
        )

        assertEquals(SensorAvailability.WAITING, initial.gyroscope.availability)
        assertEquals(SensorAvailability.READY, updated.gyroscope.availability)
        assertEquals(SensorAvailability.WAITING, updated.accelerometer.availability)
    }

    @Test
    fun locationAvailability_labels_coverEveryReason() {
        assertEquals("Waiting for permission", LocationAvailability.WAITING_FOR_PERMISSION.label)
        assertEquals("Permission denied", LocationAvailability.PERMISSION_DENIED.label)
        assertEquals("Location services off", LocationAvailability.PROVIDERS_DISABLED.label)
        assertEquals("Waiting for location", LocationAvailability.WAITING_FOR_FIX.label)
        assertEquals("Last known fix (stale)", LocationAvailability.STALE.label)
        assertEquals("Location unavailable", LocationAvailability.UNKNOWN.label)
        assertEquals("Live", LocationAvailability.READY.label)
    }

    @Test
    fun locationWithoutFix_reportsNoReading() {
        val location = LocationState(
            availability = LocationAvailability.WAITING_FOR_FIX,
            latitude = 12.971599,
            longitude = 77.594566,
        )

        assertFalse(location.hasReading)
    }

    @Test
    fun staleFix_stillCarriesRealCoordinates() {
        val location = LocationState(
            availability = LocationAvailability.STALE,
            latitude = 12.971599,
            longitude = 77.594566,
            accuracyMeters = 12f,
            provider = "gps",
            timestampMillis = 1_700_000_000_000L,
        )

        assertTrue(location.hasReading)
        assertEquals(12.971599, location.latitude, 0.000001)
    }

    @Test
    fun unknownAccuracyOrSpeed_stayNullInsteadOfBeingZeroFilled() {
        val location = LocationState(
            availability = LocationAvailability.READY,
            latitude = 1.0,
            longitude = 2.0,
        )

        assertNull(location.accuracyMeters)
        assertNull(location.speedMetersPerSecond)
    }

    @Test
    fun stalenessRule_treatsMissingAndOldFixesAsStale() {
        val now = 1_700_000_000_000L

        assertTrue(isStaleFix(fixTimestampMillis = NoSensorTimestamp, nowMillis = now))
        assertFalse(isStaleFix(now, now))
        assertFalse(isStaleFix(now - LocationStaleAfterMillis, now))
        assertTrue(isStaleFix(now - LocationStaleAfterMillis - 1, now))
    }
}
