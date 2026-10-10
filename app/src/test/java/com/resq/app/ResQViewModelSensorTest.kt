package com.resq.app

import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SensorObserver
import com.resq.app.model.AccelerometerState
import com.resq.app.model.AppScreen
import com.resq.app.model.DetectionState
import com.resq.app.model.GyroscopeState
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationState
import com.resq.app.model.SensorAvailability
import com.resq.app.model.label
import com.resq.app.viewmodel.ResQViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory [SensorGateway] standing in for SensorManager and LocationManager so
 * the collection lifecycle can be verified on the JVM.
 */
private class FakeSensorGateway : SensorGateway {

    var permissionGranted: Boolean = true
    var locationEnabled: Boolean = true
    var startCount: Int = 0
    var stopCount: Int = 0
    var refreshCount: Int = 0
    private var permissionEverGranted = false
    private var observer: SensorObserver? = null

    val isRunning: Boolean get() = observer != null

    override fun hasLocationPermission(): Boolean = permissionGranted

    override fun isLocationEnabled(): Boolean = locationEnabled

    override fun start(observer: SensorObserver) {
        this.observer = observer
        startCount++
        if (permissionGranted) permissionEverGranted = true
        observer.onAccelerometer(
            AccelerometerState(
                availability = if (hasAccelerometer) {
                    SensorAvailability.WAITING
                } else {
                    SensorAvailability.UNAVAILABLE
                },
            ),
        )
        observer.onGyroscope(
            GyroscopeState(
                availability = if (hasGyroscope) {
                    SensorAvailability.WAITING
                } else {
                    SensorAvailability.UNAVAILABLE
                },
            ),
        )
        observer.onLocation(
            when {
                !permissionGranted -> LocationState(
                    availability = LocationAvailability.WAITING_FOR_PERMISSION,
                )

                !locationEnabled -> LocationState(
                    availability = LocationAvailability.PROVIDERS_DISABLED,
                )

                else -> LocationState(availability = LocationAvailability.WAITING_FOR_FIX)
            },
        )
    }

    override fun stop() {
        observer = null
        stopCount++
    }

    /**
     * Stands in for the gateway re-checking the platform: re-emits the current
     * availability so a permission or provider change made in system settings
     * shows up without reopening the screen.
     *
     * Mirrors the real gateway's distinction between a permission that was never
     * granted and one that was granted and then taken away.
     */
    override fun refresh() {
        refreshCount++
        if (permissionGranted) permissionEverGranted = true
        emitLocation(
            when {
                permissionGranted -> LocationState(
                    availability = if (locationEnabled) {
                        LocationAvailability.WAITING_FOR_FIX
                    } else {
                        LocationAvailability.PROVIDERS_DISABLED
                    },
                )

                permissionEverGranted -> LocationState(
                    availability = LocationAvailability.PERMISSION_DENIED,
                )

                else -> LocationState(
                    availability = LocationAvailability.WAITING_FOR_PERMISSION,
                )
            },
        )
    }

    /**
     * The forced retry. Same observable contract as [refresh] - it re-asks the
     * platform and republishes - with its own counter so a test can assert that
     * an explicit recovery really took the forced path rather than the periodic
     * one.
     */
    override fun refreshForcingRegistration() {
        forcedRefreshCount++
        refresh()
    }

    var forcedRefreshCount = 0

    var hasAccelerometer: Boolean = true
    var hasGyroscope: Boolean = true

    fun emitAccelerometer(state: AccelerometerState) = observer?.onAccelerometer(state)

    fun emitGyroscope(state: GyroscopeState) = observer?.onGyroscope(state)

    fun emitLocation(state: LocationState) = observer?.onLocation(state)
}

/**
 * Verifies that the ViewModel owns the sensor listeners correctly and keeps the
 * real sensor state apart from the simulated telemetry.
 */
class ResQViewModelSensorTest {

    private lateinit var gateway: FakeSensorGateway
    private lateinit var viewModel: ResQViewModel

    @Before
    fun setUp() {
        gateway = FakeSensorGateway()
        viewModel = ResQViewModel(sensorGateway = gateway)
    }

    @Test
    fun sensorCollection_doesNotRunBeforeDiagnosticsOpens() {
        assertEquals(0, gateway.startCount)
        assertEquals(SensorAvailability.WAITING, viewModel.state.sensors.accelerometer.availability)
    }

    @Test
    fun openingDiagnostics_startsCollectionAndPublishesOpeningStates() {
        viewModel.openDiagnostics()

        assertEquals(AppScreen.DIAGNOSTICS, viewModel.state.screen)
        assertEquals(1, gateway.startCount)
        assertTrue(gateway.isRunning)
        assertEquals(SensorAvailability.WAITING, viewModel.state.sensors.accelerometer.availability)
        assertEquals(SensorAvailability.WAITING, viewModel.state.sensors.gyroscope.availability)
        assertEquals(
            LocationAvailability.WAITING_FOR_FIX,
            viewModel.state.sensors.location.availability,
        )
    }

    @Test
    fun closingDiagnostics_stopsCollectionAndLeavesTheLastReadingVisible() {
        viewModel.openDiagnostics()
        gateway.emitAccelerometer(
            AccelerometerState(
                availability = SensorAvailability.READY,
                x = 1.5f,
                y = 2.5f,
                z = 9.5f,
                timestampMillis = 1_700_000_000_000L,
            ),
        )
        viewModel.closeDiagnostics()

        assertEquals(AppScreen.HOME, viewModel.state.screen)
        assertEquals(1, gateway.stopCount)
        assertFalse(gateway.isRunning)
        assertEquals(1.5f, viewModel.state.sensors.accelerometer.x, 0.0001f)
    }

    @Test
    fun samplesAfterStop_areIgnoredBecauseNoListenerIsRegistered() {
        viewModel.openDiagnostics()
        viewModel.closeDiagnostics()
        gateway.emitGyroscope(
            GyroscopeState(
                availability = SensorAvailability.READY,
                x = 9f,
                y = 9f,
                z = 9f,
                timestampMillis = 1_700_000_000_000L,
            ),
        )

        assertEquals(SensorAvailability.WAITING, viewModel.state.sensors.gyroscope.availability)
    }

    @Test
    fun leavingDiagnosticsForAnIncident_stopsCollection() {
        viewModel.openDiagnostics()
        viewModel.simulatePossibleCollision()

        assertEquals(AppScreen.INCIDENT, viewModel.state.screen)
        assertFalse(gateway.isRunning)
        assertEquals(1, gateway.stopCount)
    }

    /**
     * CHANGED BEHAVIOUR, and the assertion is deliberately stronger than before.
     *
     * This used to assert that reaching the emergency left the gateway stopped.
     * That was correct at the time and is exactly the reported bug: every location
     * entry point is guarded by "is anybody listening?", so an emergency that
     * owns no listener can never recover a position - Refresh, the settings
     * return and the provider broadcast all become silent no-ops.
     *
     * What is asserted now is the pair of facts that matter:
     *   - the DIAGNOSTICS consumer really is released (the gateway is stopped);
     *   - the emergency immediately takes a source of its own, so a position can
     *     actually arrive.
     */
    @Test
    fun leavingDiagnosticsForAnEmergency_releasesDiagnosticsAndTheEmergencyTakesOver() {
        viewModel.openDiagnostics()
        viewModel.requestEmergencyHelp()

        assertEquals(AppScreen.EMERGENCY, viewModel.state.screen)
        assertEquals("The Diagnostics consumer must be released", 1, gateway.stopCount)
        assertTrue(
            "The emergency must own a listener, or no position can ever arrive",
            gateway.isRunning,
        )
    }

    @Test
    fun endingTheEmergency_releasesTheSourceItHeld() {
        viewModel.requestEmergencyHelp()
        assertTrue(gateway.isRunning)

        viewModel.standDown()

        assertFalse(
            "A finished incident must not keep the location listener registered",
            gateway.isRunning,
        )
    }

    @Test
    fun noResponseFromDiagnostics_stopsCollection() {
        viewModel.openDiagnostics()
        viewModel.simulateNoResponse()

        assertEquals(AppScreen.INCIDENT, viewModel.state.screen)
        assertFalse(gateway.isRunning)
    }

    @Test
    fun missingHardware_reportsUnavailableInsteadOfZeroes() {
        gateway.hasGyroscope = false
        viewModel.openDiagnostics()

        val sensors = viewModel.state.sensors
        assertEquals(SensorAvailability.UNAVAILABLE, sensors.gyroscope.availability)
        assertEquals("Sensor unavailable", SensorAvailability.UNAVAILABLE.label)
        assertNull(sensors.gyroscope.reading)
    }

    @Test
    fun missingPermission_reportsWaitingForPermissionWithoutCrashing() {
        gateway.permissionGranted = false
        viewModel.openDiagnostics()

        val location = viewModel.state.sensors.location
        assertEquals(LocationAvailability.WAITING_FOR_PERMISSION, location.availability)
        assertEquals("Waiting for permission", location.availability.label)
        assertFalse(location.hasReading)
    }

    @Test
    fun disabledProviders_reportLocationServicesOff() {
        gateway.locationEnabled = false
        viewModel.openDiagnostics()

        assertEquals(
            LocationAvailability.PROVIDERS_DISABLED,
            viewModel.state.sensors.location.availability,
        )
    }

    @Test
    fun deniedPermission_isRecordedAsAStateNotAnError() {
        viewModel.openDiagnostics()
        viewModel.onLocationPermissionResult(granted = false)

        assertEquals(
            LocationAvailability.PERMISSION_DENIED,
            viewModel.state.sensors.location.availability,
        )
        assertEquals("Permission denied", viewModel.state.sensors.location.availability.label)
    }

    @Test
    fun grantedPermission_restartsCollectionSoAFixCanArrive() {
        gateway.permissionGranted = false
        viewModel.openDiagnostics()
        gateway.permissionGranted = true

        viewModel.onLocationPermissionResult(granted = true)

        assertEquals(2, gateway.startCount)
        assertTrue(gateway.isRunning)
        assertEquals(
            LocationAvailability.WAITING_FOR_FIX,
            viewModel.state.sensors.location.availability,
        )
    }

    @Test
    fun locationFix_isExposedWithAccuracyAndSpeed() {
        viewModel.openDiagnostics()
        gateway.emitLocation(
            LocationState(
                availability = LocationAvailability.READY,
                latitude = 12.971599,
                longitude = 77.594566,
                accuracyMeters = 6.5f,
                speedMetersPerSecond = 1.75f,
                provider = "gps",
                timestampMillis = 1_700_000_000_000L,
            ),
        )

        val location = viewModel.state.sensors.location
        assertTrue(location.hasReading)
        assertEquals(12.971599, location.latitude, 0.000001)
        assertEquals(77.594566, location.longitude, 0.000001)
        assertEquals(6.5f, location.accuracyMeters ?: 0f, 0.0001f)
        assertEquals(1.75f, location.speedMetersPerSecond ?: 0f, 0.0001f)
    }

    @Test
    fun fixWithoutAccuracyOrSpeed_keepsThoseFieldsNull() {
        viewModel.openDiagnostics()
        gateway.emitLocation(
            LocationState(
                availability = LocationAvailability.READY,
                latitude = 1.0,
                longitude = 2.0,
            ),
        )

        val location = viewModel.state.sensors.location
        assertNull(location.accuracyMeters)
        assertNull(location.speedMetersPerSecond)
    }

    @Test
    fun realSensors_doNotDisturbSimulatedTelemetry() {
        viewModel.openDiagnostics()
        val telemetryBefore = viewModel.state.telemetry

        gateway.emitAccelerometer(
            AccelerometerState(
                availability = SensorAvailability.READY,
                x = 3f,
                y = 4f,
                z = 5f,
                timestampMillis = 1_700_000_000_000L,
            ),
        )

        assertEquals(telemetryBefore, viewModel.state.telemetry)
        assertEquals(DetectionState.IDLE, viewModel.state.telemetry.detection)
    }

    @Test
    fun simulationControls_doNotTouchRealSensorState() {
        viewModel.openDiagnostics()
        val realBefore = viewModel.state.sensors

        viewModel.simulateHardBraking()
        assertEquals(DetectionState.HARD_BRAKING, viewModel.state.telemetry.detection)

        viewModel.simulatePossibleCollision()
        assertEquals(DetectionState.POSSIBLE_IMPACT, viewModel.state.telemetry.detection)
        assertEquals(realBefore, viewModel.state.sensors)
    }

    @Test
    fun resetSimulation_republishesRealHardwareState() {
        viewModel.openDiagnostics()
        gateway.emitGyroscope(
            GyroscopeState(
                availability = SensorAvailability.READY,
                x = 0.3f,
                y = 0.2f,
                z = 0.1f,
                timestampMillis = 1_700_000_000_000L,
            ),
        )

        viewModel.resetSimulation()

        assertEquals(AppScreen.DIAGNOSTICS, viewModel.state.screen)
        assertEquals(SensorAvailability.WAITING, viewModel.state.sensors.gyroscope.availability)
        assertTrue(gateway.isRunning)
    }
}
