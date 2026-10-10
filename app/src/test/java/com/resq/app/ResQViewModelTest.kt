package com.resq.app

import com.resq.app.model.AppScreen
import com.resq.app.model.BaselineAccidentScore
import com.resq.app.model.DetectionConfidence
import com.resq.app.model.DetectionState
import com.resq.app.model.EmergencyProgress
import com.resq.app.model.EmergencyState
import com.resq.app.model.GpsStatus
import com.resq.app.model.IncidentState
import com.resq.app.model.ProtectionState
import com.resq.app.model.ResourceDiscoveryState
import com.resq.app.model.SensorStatus
import com.resq.app.viewmodel.ResQViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for the ResQ UI state machine. The timers themselves are driven
 * through the internal tick hooks so the logic can be verified without
 * sleeping or touching any Android API.
 */
class ResQViewModelTest {

    private lateinit var viewModel: ResQViewModel

    @Before
    fun setUp() {
        viewModel = ResQViewModel()
    }

    @Test
    fun initialState_isProtectedHome() {
        val state = viewModel.state
        assertEquals(AppScreen.HOME, state.screen)
        assertEquals(ProtectionState.ACTIVE, state.protection)
        assertEquals(IncidentState.None, state.incident)
        assertEquals(EmergencyState.Inactive, state.emergency)
        assertEquals(SensorStatus.MONITORING, state.telemetry.sensors)
        assertEquals(GpsStatus.LOCKED, state.telemetry.gps)
    }

    @Test
    fun toggleProtection_pausesAndResumesMonitoring() {
        viewModel.toggleProtection()
        assertEquals(ProtectionState.PAUSED, viewModel.state.protection)
        assertEquals(SensorStatus.PAUSED, viewModel.state.telemetry.sensors)
        assertEquals(GpsStatus.STANDBY, viewModel.state.telemetry.gps)

        viewModel.toggleProtection()
        assertEquals(ProtectionState.ACTIVE, viewModel.state.protection)
        assertEquals(SensorStatus.MONITORING, viewModel.state.telemetry.sensors)
        assertEquals(GpsStatus.LOCKED, viewModel.state.telemetry.gps)
    }

    @Test
    fun toggleProtection_isIgnoredWhileAnIncidentIsOpen() {
        viewModel.simulatePossibleCollision()
        viewModel.toggleProtection()

        assertEquals(ProtectionState.ACTIVE, viewModel.state.protection)
    }

    @Test
    fun possibleCollision_opensIncidentCountdown() {
        viewModel.simulatePossibleCollision()

        val state = viewModel.state
        assertEquals(AppScreen.INCIDENT, state.screen)
        val incident = state.incident as IncidentState.PossibleImpact
        assertEquals(10, incident.secondsRemaining)
        assertEquals(DetectionConfidence.POSSIBLE, incident.confidence)
        assertEquals(DetectionState.POSSIBLE_IMPACT, state.telemetry.detection)
    }

    @Test
    fun highConfidenceCollision_usesShorterWindow() {
        viewModel.simulateHighConfidenceCollision()

        val incident = viewModel.state.incident as IncidentState.PossibleImpact
        assertEquals(5, incident.secondsRemaining)
        assertEquals(DetectionConfidence.HIGH, incident.confidence)
        assertEquals(DetectionState.HIGH_CONFIDENCE_IMPACT, viewModel.state.telemetry.detection)
    }

    @Test
    fun countdownTicksDownBeforeExpiring() {
        viewModel.simulatePossibleCollision()

        viewModel.onCountdownTick()
        val incident = viewModel.state.incident as IncidentState.PossibleImpact
        assertEquals(9, incident.secondsRemaining)
        assertEquals(AppScreen.INCIDENT, viewModel.state.screen)
    }

    @Test
    fun expiredCountdown_movesToConfirmation() {
        viewModel.simulatePossibleCollision()
        repeat(10) { viewModel.onCountdownTick() }

        assertEquals(IncidentState.Confirmation, viewModel.state.incident)
        assertEquals(AppScreen.INCIDENT, viewModel.state.screen)
    }

    @Test
    fun userOk_resolvesIncidentAndReturnsHome() {
        viewModel.simulatePossibleCollision()
        viewModel.confirmUserOk()

        assertEquals(IncidentState.None, viewModel.state.incident)
        assertEquals(AppScreen.HOME, viewModel.state.screen)
        assertEquals(DetectionState.IDLE, viewModel.state.telemetry.detection)
        assertEquals(BaselineAccidentScore, viewModel.state.telemetry.accidentScore, 0.001f)
    }

    @Test
    fun simulateUserOk_normalizesTelemetryOnDiagnostics() {
        viewModel.openDiagnostics()
        viewModel.simulateHardBraking()
        viewModel.simulateUserOk()

        assertEquals(AppScreen.DIAGNOSTICS, viewModel.state.screen)
        assertEquals(IncidentState.None, viewModel.state.incident)
        assertEquals(DetectionState.IDLE, viewModel.state.telemetry.detection)
        assertEquals(BaselineAccidentScore, viewModel.state.telemetry.accidentScore, 0.001f)
    }

    @Test
    fun simulateUserOk_returnsHomeWhenTheAlertIsOpen() {
        viewModel.simulatePossibleCollision()
        viewModel.simulateUserOk()

        assertEquals(AppScreen.HOME, viewModel.state.screen)
        assertEquals(IncidentState.None, viewModel.state.incident)
    }

    @Test
    fun noResponse_movesIncidentToConfirmation() {
        viewModel.simulatePossibleCollision()
        viewModel.simulateNoResponse()

        assertEquals(IncidentState.Confirmation, viewModel.state.incident)
        assertEquals(AppScreen.INCIDENT, viewModel.state.screen)
    }

    @Test
    fun requestEmergencyHelp_startsPreparationFlow() {
        viewModel.simulatePossibleCollision()
        viewModel.requestEmergencyHelp()

        val state = viewModel.state
        assertEquals(AppScreen.EMERGENCY, state.screen)
        assertEquals(IncidentState.None, state.incident)
        assertEquals(
            EmergencyState.Preparing(EmergencyProgress.LOCATING),
            state.emergency,
        )
    }

    @Test
    fun emergencySequence_withoutUsableLocation_reachesReadyWithoutSearching() {
        viewModel.requestEmergencyHelp()

        viewModel.advanceEmergency()
        assertEquals(
            EmergencyState.Preparing(EmergencyProgress.LINKING_CONTACTS),
            viewModel.state.emergency,
        )

        viewModel.advanceEmergency()
        assertEquals(
            EmergencyState.Preparing(EmergencyProgress.SEARCHING_RESOURCES),
            viewModel.state.emergency,
        )
        assertEquals(
            ResourceDiscoveryState.NoUsableLocation,
            viewModel.state.resourceDiscovery,
        )

        viewModel.advanceEmergency()
        val ready = viewModel.state.emergency as EmergencyState.Ready
        assertTrue(ready.resources.isEmpty())
        assertEquals(
            ResourceDiscoveryState.NoUsableLocation,
            viewModel.state.resourceDiscovery,
        )
    }

    @Test
    fun standDown_clearsEmergencyAndReturnsHome() {
        viewModel.requestEmergencyHelp()
        viewModel.standDown()

        assertEquals(AppScreen.HOME, viewModel.state.screen)
        assertEquals(EmergencyState.Inactive, viewModel.state.emergency)
        assertEquals(IncidentState.None, viewModel.state.incident)
    }

    @Test
    fun hardBraking_updatesDetectionStateAndScore() {
        viewModel.openDiagnostics()
        viewModel.simulateHardBraking()

        val telemetry = viewModel.state.telemetry
        assertEquals(AppScreen.DIAGNOSTICS, viewModel.state.screen)
        assertEquals(DetectionState.HARD_BRAKING, telemetry.detection)
        assertTrue(telemetry.accidentScore > BaselineAccidentScore)
    }

    @Test
    fun resetSimulation_restoresDefaultsAndKeepsDiagnosticsOpen() {
        viewModel.openDiagnostics()
        viewModel.simulateHighConfidenceCollision()
        viewModel.openDiagnostics()
        viewModel.resetSimulation()

        val state = viewModel.state
        assertEquals(AppScreen.DIAGNOSTICS, state.screen)
        assertEquals(ProtectionState.ACTIVE, state.protection)
        assertEquals(IncidentState.None, state.incident)
        assertEquals(EmergencyState.Inactive, state.emergency)
        assertEquals(DetectionState.IDLE, state.telemetry.detection)
        assertEquals(BaselineAccidentScore, state.telemetry.accidentScore, 0.001f)
    }

    @Test
    fun backNavigation_closesDiagnostics() {
        viewModel.openDiagnostics()
        viewModel.closeDiagnostics()

        assertEquals(AppScreen.HOME, viewModel.state.screen)
    }
}
