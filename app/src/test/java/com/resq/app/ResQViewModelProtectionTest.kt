package com.resq.app

import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SensorObserver
import com.resq.app.detection.SimulationSamples
import com.resq.app.model.AccelerometerState
import com.resq.app.model.AppScreen
import com.resq.app.model.BaselineAccidentScore
import com.resq.app.model.DetectionLevel
import com.resq.app.model.DetectionState
import com.resq.app.model.DetectionConfidence
import com.resq.app.model.EmergencyProgress
import com.resq.app.model.EmergencyState
import com.resq.app.model.GpsStatus
import com.resq.app.model.GyroscopeState
import com.resq.app.model.IncidentState
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationState
import com.resq.app.model.ProtectionState
import com.resq.app.model.SensorAvailability
import com.resq.app.model.SensorStatus
import com.resq.app.protection.ProtectionController
import com.resq.app.protection.ProtectionEngine
import com.resq.app.protection.ProtectionSource
import com.resq.app.viewmodel.ResQViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Records what the UI asked of the foreground service, so START_PROTECTION /
 * STOP_PROTECTION can be verified without Android.
 */
private class FakeProtectionController : ProtectionController {

    var startCount = 0
    var stopCount = 0
    override var isProtectionRunning: Boolean = false
        private set

    override fun startProtection() {
        startCount++
        isProtectionRunning = true
    }

    override fun stopProtection() {
        stopCount++
        isProtectionRunning = false
    }
}

/** Gateway that records registrations and replays samples on demand. */
private class SilentGateway : SensorGateway {

    var startCount = 0
    var stopCount = 0
    private var observer: SensorObserver? = null

    override fun hasLocationPermission(): Boolean = false

    override fun isLocationEnabled(): Boolean = false

    override fun start(observer: SensorObserver) {
        startCount++
        this.observer = observer
    }

    override fun refresh() = Unit

    override fun refreshForcingRegistration() = Unit

    override fun stop() {
        stopCount++
        observer = null
    }

    /** Exactly what the platform gateway does with a sensor event. */
    fun emitAccelerometer(accelerometerState: AccelerometerState) {
        observer?.onAccelerometer(accelerometerState)
    }

    fun emitGyroscope(gyroscopeState: GyroscopeState) {
        observer?.onGyroscope(gyroscopeState)
    }

    fun emitLocation(locationState: LocationState) {
        observer?.onLocation(locationState)
    }
}

/**
 * Verifies the wiring between the UI, the shared protection engine and the
 * foreground service: start/stop are user controlled and idempotent, the UI
 * reflects what the service actually did, and a detection raised by the service
 * surfaces the existing incident and emergency screens.
 */
class ResQViewModelProtectionTest {

    private lateinit var controller: FakeProtectionController
    private lateinit var gateway: SilentGateway
    private lateinit var engine: ProtectionEngine
    private lateinit var viewModel: ResQViewModel

    @Before
    fun setUp() {
        controller = FakeProtectionController()
        gateway = SilentGateway()
        engine = ProtectionEngine(gateway)
        viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            protectionController = controller,
        )
    }

    /** What the foreground service does when the user presses the Home button. */
    private fun serviceStarts() = engine.attachSource(ProtectionSource.SERVICE)

    private fun serviceStops() = engine.detachSource(ProtectionSource.SERVICE)

    // ------------------------------------------------------------------
    // START_PROTECTION / STOP_PROTECTION
    // ------------------------------------------------------------------

    @Test
    fun startingProtection_asksTheServiceExactlyOnce() {
        viewModel.startProtection()
        viewModel.startProtection()

        assertEquals(1, controller.startCount)
    }

    @Test
    fun stoppingProtection_asksTheServiceExactlyOnce() {
        viewModel.startProtection()
        viewModel.stopProtection()
        viewModel.stopProtection()

        assertEquals(1, controller.stopCount)
        assertEquals(1, controller.startCount)
    }

    @Test
    fun theHomeButton_togglesBetweenTheTwoIntents() {
        viewModel.toggleProtection()
        assertEquals(1, controller.startCount)

        viewModel.toggleProtection()
        assertEquals(1, controller.stopCount)
    }

    @Test
    fun protection_isIgnoredWhileAnIncidentIsOpen() {
        viewModel.simulatePossibleCollision()

        viewModel.toggleProtection()

        assertEquals(0, controller.startCount)
        assertEquals(0, controller.stopCount)
    }

    @Test
    fun nothing_isMonitoringBeforeTheUserAsksForIt() {
        assertEquals(0, controller.startCount)
        assertEquals(0, gateway.startCount)
    }

    // ------------------------------------------------------------------
    // What the UI shows reflects what the service really did
    // ------------------------------------------------------------------

    @Test
    fun protectionShowsOffUntilTheServiceConfirmsItIsMonitoring() {
        assertEquals(ProtectionState.PAUSED, viewModel.state.protection)
        assertEquals(SensorStatus.PAUSED, viewModel.state.telemetry.sensors)

        serviceStarts()

        assertEquals(ProtectionState.ACTIVE, viewModel.state.protection)
        assertEquals(SensorStatus.MONITORING, viewModel.state.telemetry.sensors)
        assertTrue(viewModel.isServiceMonitoring())
    }

    @Test
    fun gpsIsOnlyReportedAsLockedWhenThereIsARealFix() {
        serviceStarts()
        // No fix yet: a running service must not claim a GPS lock.
        assertEquals(GpsStatus.STANDBY, viewModel.state.telemetry.gps)

        gateway.emitLocation(
            LocationState(
                availability = LocationAvailability.READY,
                latitude = 12.971599,
                longitude = 77.594566,
                accuracyMeters = 6.5f,
                speedMetersPerSecond = 12f,
                provider = "gps",
                // A FRESH fix. The timestamp has to be current: the GPS pill makes
                // a claim about where the phone is now, so an aged fix must not
                // satisfy it.
                timestampMillis = System.currentTimeMillis(),
            ),
        )

        assertEquals(GpsStatus.LOCKED, viewModel.state.telemetry.gps)
    }

    @Test
    fun aStaleLastKnownFix_isNotReportedAsAGpsLock() {
        serviceStarts()

        // Real coordinates, but minutes old. This is exactly the case Task 5A
        // exists for: showing it is fine, calling it a lock is not.
        gateway.emitLocation(
            LocationState(
                availability = LocationAvailability.STALE,
                latitude = 12.971599,
                longitude = 77.594566,
                accuracyMeters = 6.5f,
                provider = "gps",
                timestampMillis = System.currentTimeMillis() - 5 * 60_000L,
            ),
        )

        assertEquals(GpsStatus.STANDBY, viewModel.state.telemetry.gps)
        // The coordinates are still available for display, just not as a lock.
        assertTrue(viewModel.state.sensors.location.hasReading)
    }

    @Test
    fun stoppingTheService_putsProtectionBackToOff() {
        viewModel.startProtection()
        serviceStarts()

        serviceStops()

        assertEquals(ProtectionState.PAUSED, viewModel.state.protection)
        assertEquals(SensorStatus.PAUSED, viewModel.state.telemetry.sensors)
        assertFalse(viewModel.isServiceMonitoring())
    }

    @Test
    fun aRecreatedUiReflectsTheRunningServiceImmediately() {
        viewModel.startProtection()
        serviceStarts()

        // A brand new ViewModel stands in for a recreated Activity: it must show
        // the current protection state, not a default.
        val recreated = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            protectionController = controller,
        )

        assertEquals(ProtectionState.ACTIVE, recreated.state.protection)
        assertTrue(recreated.isServiceMonitoring())
    }

    @Test
    fun aRecreatedUi_seesExactlyOneMonitoringSession() {
        viewModel.startProtection()
        serviceStarts()
        serviceStarts()

        assertEquals(1, gateway.startCount)
        assertEquals(0, gateway.stopCount)
    }

    // ------------------------------------------------------------------
    // Diagnostics must not disturb a running protection service
    // ------------------------------------------------------------------

    @Test
    fun openingDiagnostics_whileProtecting_doesNotRegisterTheSensorsAgain() {
        viewModel.startProtection()
        serviceStarts()

        viewModel.openDiagnostics()

        assertEquals(1, gateway.startCount)
        assertEquals(0, gateway.stopCount)
        assertEquals(AppScreen.DIAGNOSTICS, viewModel.state.screen)
    }

    @Test
    fun leavingDiagnostics_whileProtecting_keepsTheSensorsRegistered() {
        viewModel.startProtection()
        serviceStarts()
        viewModel.openDiagnostics()

        viewModel.closeDiagnostics()

        assertEquals(1, gateway.startCount)
        assertEquals(0, gateway.stopCount)
    }

    @Test
    fun diagnosticsWithoutProtection_stillReadTheHardwareAndReleaseIt() {
        viewModel.openDiagnostics()
        assertEquals(1, gateway.startCount)

        viewModel.closeDiagnostics()

        assertEquals(1, gateway.stopCount)
    }

    @Test
    fun previewingDiagnosticsWithoutProtection_neverClaimsToBeProtected() {
        viewModel.openDiagnostics()

        assertEquals(ProtectionState.PAUSED, viewModel.state.protection)
        assertEquals(SensorStatus.PAUSED, viewModel.state.telemetry.sensors)
        assertFalse(viewModel.isServiceMonitoring())
    }

    @Test
    fun diagnosticsStillScoreRealSamples_evenWhileProtectionIsOff() {
        viewModel.openDiagnostics()

        SimulationSamples.possibleCollision(5).forEach { sample ->
            gateway.emitAccelerometer(sample.accelerometer)
            gateway.emitGyroscope(sample.gyroscope)
        }

        assertEquals(DetectionLevel.POSSIBLE, viewModel.state.detection.level)
        // Scored and displayed, but a preview can never open a real alert.
        assertEquals(IncidentState.None, viewModel.state.incident)
        assertEquals(AppScreen.DIAGNOSTICS, viewModel.state.screen)
    }

    // ------------------------------------------------------------------
    // A detection raised by the service surfaces the existing screens
    // ------------------------------------------------------------------

    @Test
    fun aPossibleIncidentFromTheService_opensTheIncidentAlert() {
        serviceStarts()

        emitServiceImpact()

        val state = viewModel.state
        assertEquals(AppScreen.INCIDENT, state.screen)
        val incident = state.incident as IncidentState.PossibleImpact
        assertEquals(10, incident.secondsRemaining)
        assertEquals(DetectionConfidence.POSSIBLE, incident.confidence)
        assertEquals(DetectionState.POSSIBLE_IMPACT, state.telemetry.detection)
    }

    @Test
    fun theIncidentCountdownKeepsRunningThroughTheSharedEngine() {
        serviceStarts()
        emitServiceImpact()

        viewModel.onCountdownTick()

        assertEquals(9, (viewModel.state.incident as IncidentState.PossibleImpact).secondsRemaining)
    }

    @Test
    fun anExhaustedCountdown_reachesTheConfirmationStep() {
        serviceStarts()
        emitServiceImpact()

        repeat(10) { viewModel.onCountdownTick() }

        assertEquals(IncidentState.Confirmation, viewModel.state.incident)
        assertEquals(AppScreen.INCIDENT, viewModel.state.screen)
    }

    @Test
    fun theUserSaysTheyAreFine_returnsToNormal() {
        serviceStarts()
        emitServiceImpact()

        viewModel.confirmUserOk()

        assertEquals(IncidentState.None, viewModel.state.incident)
        assertEquals(AppScreen.HOME, viewModel.state.screen)
        assertEquals(DetectionState.IDLE, viewModel.state.telemetry.detection)
        assertEquals(BaselineAccidentScore, viewModel.state.telemetry.accidentScore, 0.001f)
    }

    @Test
    fun theUserAsksForHelp_opensTheEmergencyExperience() {
        serviceStarts()
        emitServiceImpact()

        viewModel.requestEmergencyHelp()

        assertEquals(AppScreen.EMERGENCY, viewModel.state.screen)
        assertEquals(
            EmergencyState.Preparing(EmergencyProgress.LOCATING),
            viewModel.state.emergency,
        )
    }

    @Test
    fun standingDownFromAServiceIncident_returnsHome() {
        serviceStarts()
        emitServiceImpact()
        viewModel.requestEmergencyHelp()

        viewModel.standDown()

        assertEquals(AppScreen.HOME, viewModel.state.screen)
        assertEquals(EmergencyState.Inactive, viewModel.state.emergency)
        assertEquals(IncidentState.None, viewModel.state.incident)
    }

    @Test
    fun hardBrakingWhileProtecting_neverOpensAnIncident() {
        serviceStarts()

        SimulationSamples.hardBraking(12).forEach { sample ->
            gateway.emitAccelerometer(sample.accelerometer)
            gateway.emitGyroscope(sample.gyroscope)
        }

        assertEquals(IncidentState.None, viewModel.state.incident)
        assertEquals(AppScreen.HOME, viewModel.state.screen)
        assertEquals(DetectionState.IDLE, viewModel.state.telemetry.detection)
    }

    @Test
    fun theDiagnosticsSimulationControls_stillWorkWhileProtecting() {
        serviceStarts()
        viewModel.openDiagnostics()

        viewModel.simulatePossibleCollision()

        assertEquals(AppScreen.INCIDENT, viewModel.state.screen)
        assertEquals(10, (viewModel.state.incident as IncidentState.PossibleImpact).secondsRemaining)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Replays a synthetic impact through the engine exactly as the service would. */
    private fun emitServiceImpact() {
        SimulationSamples.possibleCollision(5).forEach { sample ->
            gateway.emitAccelerometer(sample.accelerometer)
            gateway.emitGyroscope(sample.gyroscope)
        }
    }
}