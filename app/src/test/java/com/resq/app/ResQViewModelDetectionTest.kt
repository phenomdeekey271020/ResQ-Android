package com.resq.app

import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SensorObserver
import com.resq.app.model.AccelerometerState
import com.resq.app.model.AppScreen
import com.resq.app.model.BaselineAccidentScore
import com.resq.app.model.DetectionLevel
import com.resq.app.model.DetectionState
import com.resq.app.model.EmergencyProgress
import com.resq.app.model.EmergencyState
import com.resq.app.model.GyroscopeState
import com.resq.app.model.IncidentState
import com.resq.app.model.SensorAvailability
import com.resq.app.viewmodel.ResQViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies that the ViewModel only coordinates: the detection maths and the flow
 * rules live in the domain classes, and simulated scenarios travel through the
 * same detector as real hardware samples.
 */
class ResQViewModelDetectionTest {

    private lateinit var gateway: FakeDetectionGateway
    private lateinit var viewModel: ResQViewModel

    @Before
    fun setUp() {
        gateway = FakeDetectionGateway()
        viewModel = ResQViewModel(sensorGateway = gateway)
    }

    @Test
    fun realSensorSamples_areScoredAndExposed() {
        viewModel.openDiagnostics()
        gateway.emitImpact()

        val detection = viewModel.state.detection
        assertEquals(DetectionLevel.POSSIBLE, detection.level)
        assertTrue(detection.confidence > 0)
        assertTrue(detection.reasons.isNotEmpty())
        assertTrue(detection.hasSensorData)
    }

    @Test
    fun prototypeConfidence_mirrorsTheAssessmentScore() {
        viewModel.openDiagnostics()
        gateway.emitImpact()

        assertEquals(
            viewModel.state.detection.confidence / 100f,
            viewModel.state.telemetry.accidentScore,
            0.0001f,
        )
    }

    @Test
    fun sensorLoss_fallsBackToASafeNoDetectionAssessment() {
        viewModel.openDiagnostics()
        gateway.emitImpact()
        gateway.emitAccelerometer(AccelerometerState(availability = SensorAvailability.UNAVAILABLE))

        val detection = viewModel.state.detection
        assertEquals(DetectionLevel.NORMAL, detection.level)
        assertEquals(0, detection.confidence)
        assertEquals(IncidentState.None, viewModel.state.incident)
        assertEquals(EmergencyState.Inactive, viewModel.state.emergency)
    }

    @Test
    fun possibleCollisionSimulation_opensTheCountdownWithThePrototypeConfidence() {
        viewModel.openDiagnostics()
        viewModel.simulatePossibleCollision()

        assertEquals(AppScreen.INCIDENT, viewModel.state.screen)
        val incident = viewModel.state.incident as IncidentState.PossibleImpact
        assertEquals(10, incident.secondsRemaining)
        assertEquals(DetectionState.POSSIBLE_IMPACT, viewModel.state.telemetry.detection)
        assertNotEquals(BaselineAccidentScore, viewModel.state.telemetry.accidentScore)
    }

    @Test
    fun highConfidenceSimulation_usesTheShorterCountdown() {
        viewModel.openDiagnostics()
        viewModel.simulateHighConfidenceCollision()

        val incident = viewModel.state.incident as IncidentState.PossibleImpact
        assertEquals(5, incident.secondsRemaining)
        assertEquals(DetectionState.HIGH_CONFIDENCE_IMPACT, viewModel.state.telemetry.detection)
    }

    @Test
    fun simulatedScenario_doesNotOverwriteRealSensorState() {
        viewModel.openDiagnostics()
        val realBefore = viewModel.state.sensors

        viewModel.simulateHighConfidenceCollision()

        assertEquals(realBefore, viewModel.state.sensors)
        assertEquals(AppScreen.INCIDENT, viewModel.state.screen)
    }

    @Test
    fun hardBrakingSimulation_neverReachesTheIncidentScreen() {
        viewModel.openDiagnostics()
        viewModel.simulateHardBraking()

        assertEquals(DetectionState.HARD_BRAKING, viewModel.state.telemetry.detection)
        assertEquals(IncidentState.None, viewModel.state.incident)
        assertEquals(EmergencyState.Inactive, viewModel.state.emergency)
        assertEquals(AppScreen.DIAGNOSTICS, viewModel.state.screen)
    }

    @Test
    fun hardBrakingThenUserOk_neverTouchedAnIncident() {
        viewModel.openDiagnostics()
        viewModel.simulateHardBraking()
        viewModel.simulateUserOk()

        assertEquals(IncidentState.None, viewModel.state.incident)
        assertEquals(EmergencyState.Inactive, viewModel.state.emergency)
        assertEquals(DetectionState.IDLE, viewModel.state.telemetry.detection)
    }

    @Test
    fun timeoutPath_reachesEmergencyOnlyThroughConfirmation() {
        viewModel.simulatePossibleCollision()
        repeat(10) { viewModel.onCountdownTick() }
        assertEquals(IncidentState.Confirmation, viewModel.state.incident)
        assertEquals(EmergencyState.Inactive, viewModel.state.emergency)

        viewModel.simulateNoResponse()

        assertEquals(
            EmergencyState.Preparing(EmergencyProgress.LOCATING),
            viewModel.state.emergency,
        )
        assertEquals(AppScreen.EMERGENCY, viewModel.state.screen)
    }

    @Test
    fun userOkPath_neverReachesEmergency() {
        viewModel.simulatePossibleCollision()
        viewModel.confirmUserOk()

        assertEquals(IncidentState.None, viewModel.state.incident)
        assertEquals(EmergencyState.Inactive, viewModel.state.emergency)
        assertEquals(AppScreen.HOME, viewModel.state.screen)
    }

    @Test
    fun resettingClearsTheDetectionAssessment() {
        viewModel.openDiagnostics()
        gateway.emitImpact()
        viewModel.simulateHighConfidenceCollision()

        viewModel.openDiagnostics()
        viewModel.resetSimulation()

        assertEquals(DetectionLevel.NORMAL, viewModel.state.detection.level)
        assertEquals(0, viewModel.state.detection.confidence)
        assertEquals(IncidentState.None, viewModel.state.incident)
    }
}

/**
 * A gateway that replays a synthetic impact through the real detector, proving
 * simulated and live samples take the same path.
 */
private class FakeDetectionGateway : SensorGateway {

    private var observer: SensorObserver? = null
    private var timestamp: Long = 1_700_000_000_000L

    override fun hasLocationPermission(): Boolean = false

    override fun isLocationEnabled(): Boolean = false

    override fun start(observer: SensorObserver) {
        this.observer = observer
        observer.onAccelerometer(AccelerometerState(availability = SensorAvailability.WAITING))
        observer.onGyroscope(GyroscopeState(availability = SensorAvailability.WAITING))
    }

    override fun refresh() = Unit

    override fun refreshForcingRegistration() = Unit

    override fun stop() {
        observer = null
    }

    /** Five strong impact samples with rotation - enough to reach POSSIBLE. */
    fun emitImpact() {
        repeat(5) {
            emitAccelerometer(
                AccelerometerState(
                    availability = SensorAvailability.READY,
                    x = 28.0f,
                    y = 4.0f,
                    z = -6.0f,
                    timestampMillis = timestamp,
                ),
            )
            emitGyroscope(
                GyroscopeState(
                    availability = SensorAvailability.READY,
                    x = 4.2f,
                    y = 2.6f,
                    z = -1.1f,
                    timestampMillis = timestamp,
                ),
            )
            timestamp += 40L
        }
    }

    fun emitAccelerometer(state: AccelerometerState) = observer?.onAccelerometer(state)

    fun emitGyroscope(state: GyroscopeState) = observer?.onGyroscope(state)
}