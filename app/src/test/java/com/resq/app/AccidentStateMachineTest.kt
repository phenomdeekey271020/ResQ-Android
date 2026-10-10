package com.resq.app

import com.resq.app.detection.AccidentEvent
import com.resq.app.detection.AccidentFlowState
import com.resq.app.detection.AccidentStateMachine
import com.resq.app.detection.EmergencyStage
import com.resq.app.model.DetectionConfig
import com.resq.app.model.DetectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the accident flow.
 *
 * The critical rule these tests protect: EMERGENCY is never reachable from a
 * detection level. It always needs a user decision or an unanswered window.
 */
class AccidentStateMachineTest {

    private val config = DetectionConfig()
    private val machine = AccidentStateMachine(config)
    private var now: Long = 1_000_000L

    // ------------------------------------------------------------------
    // Detection cannot reach an emergency
    // ------------------------------------------------------------------

    @Test
    fun detectionOfAnyLevel_opensTheConfirmationWindowOnly() {
        DetectionLevel.values().forEach { level ->
            val fresh = AccidentStateMachine(config)
            val flow = fresh.reduce(AccidentEvent.Detected(level), now)

            assertTrue(
                "level $level must not escalate past the countdown",
                flow is AccidentFlowState.PossibleAccident || flow is AccidentFlowState.Normal,
            )
            assertFalse(flow is AccidentFlowState.Emergency)
        }
    }

    @Test
    fun normalDetection_leavesTheAppCalm() {
        val flow = machine.reduce(AccidentEvent.Detected(DetectionLevel.NORMAL), now)

        assertEquals(AccidentFlowState.Normal, flow)
    }

    @Test
    fun possibleDetection_usesTheLongerCountdown() {
        val flow = machine.reduce(AccidentEvent.Detected(DetectionLevel.POSSIBLE), now) as
            AccidentFlowState.PossibleAccident

        assertEquals(DetectionLevel.POSSIBLE, flow.level)
        assertEquals(10, flow.secondsRemaining)
    }

    @Test
    fun highConfidenceDetection_usesTheShorterCountdown() {
        val flow = machine.reduce(AccidentEvent.Detected(DetectionLevel.HIGH_CONFIDENCE), now) as
            AccidentFlowState.PossibleAccident

        assertEquals(5, flow.secondsRemaining)
    }

    // ------------------------------------------------------------------
    // G. User OK
    // ------------------------------------------------------------------

    @Test
    fun userOk_fromCountdown_returnsToNormal() {
        machine.reduce(AccidentEvent.Detected(DetectionLevel.POSSIBLE), now)

        assertEquals(AccidentFlowState.Normal, machine.reduce(AccidentEvent.UserOk, now))
    }

    @Test
    fun userOk_fromConfirmation_returnsToNormal() {
        machine.reduce(AccidentEvent.Detected(DetectionLevel.POSSIBLE), now)
        machine.reduce(AccidentEvent.NoResponse, now)
        assertEquals(AccidentFlowState.Confirmation, machine.state)

        assertEquals(AccidentFlowState.Normal, machine.reduce(AccidentEvent.UserOk, now))
    }

    // ------------------------------------------------------------------
    // H. Need help
    // ------------------------------------------------------------------

    @Test
    fun needHelp_fromAnyState_opensTheEmergencyFlow() {
        assertEquals(
            AccidentFlowState.Emergency(EmergencyStage.LOCATING),
            machine.reduce(AccidentEvent.NeedHelp, now),
        )

        val alerting = AccidentStateMachine(config)
        alerting.reduce(AccidentEvent.Detected(DetectionLevel.HIGH_CONFIDENCE), now)
        assertTrue(alerting.reduce(AccidentEvent.NeedHelp, now) is AccidentFlowState.Emergency)

        val confirming = AccidentStateMachine(config)
        confirming.reduce(AccidentEvent.Detected(DetectionLevel.POSSIBLE), now)
        confirming.reduce(AccidentEvent.CountdownTick, now + 10_000L)
        assertTrue(confirming.reduce(AccidentEvent.NeedHelp, now) is AccidentFlowState.Emergency)
    }

    // ------------------------------------------------------------------
    // I. Timeout / no response
    // ------------------------------------------------------------------

    @Test
    fun expiredCountdown_movesToConfirmation() {
        machine.reduce(AccidentEvent.Detected(DetectionLevel.POSSIBLE), now)
        repeat(9) { machine.reduce(AccidentEvent.CountdownTick, now++) }

        assertTrue(machine.state is AccidentFlowState.PossibleAccident)

        machine.reduce(AccidentEvent.CountdownTick, now)

        assertEquals(AccidentFlowState.Confirmation, machine.state)
    }

    @Test
    fun noResponse_afterConfirmation_escalatesToEmergency() {
        machine.reduce(AccidentEvent.Detected(DetectionLevel.POSSIBLE), now)
        machine.reduce(AccidentEvent.NoResponse, now)
        assertEquals(AccidentFlowState.Confirmation, machine.state)

        val flow = machine.reduce(AccidentEvent.NoResponse, now)

        assertEquals(AccidentFlowState.Emergency(EmergencyStage.LOCATING), flow)
    }

    @Test
    fun emergencyProgressesThroughItsLocalStages() {
        machine.reduce(AccidentEvent.NeedHelp, now)
        assertEquals(
            EmergencyStage.LINKING_CONTACTS,
            (machine.reduce(AccidentEvent.EmergencyAdvanced, now) as AccidentFlowState.Emergency).stage,
        )
        assertEquals(
            EmergencyStage.SEARCHING_RESOURCES,
            (machine.reduce(AccidentEvent.EmergencyAdvanced, now) as AccidentFlowState.Emergency).stage,
        )
        assertEquals(
            EmergencyStage.READY,
            (machine.reduce(AccidentEvent.EmergencyAdvanced, now) as AccidentFlowState.Emergency).stage,
        )
        // The last stage is a fixed point.
        assertEquals(
            EmergencyStage.READY,
            (machine.reduce(AccidentEvent.EmergencyAdvanced, now) as AccidentFlowState.Emergency).stage,
        )
    }

    // ------------------------------------------------------------------
    // J. Reset
    // ------------------------------------------------------------------

    @Test
    fun reset_returnsToNormalFromEveryState() {
        val scenarios = listOf<AccidentFlowState>(
            AccidentFlowState.PossibleAccident(secondsRemaining = 3, level = DetectionLevel.POSSIBLE),
            AccidentFlowState.Confirmation,
            AccidentFlowState.Emergency(EmergencyStage.LOCATING),
        )

        scenarios.forEach { scenario ->
            val fresh = AccidentStateMachine(config)
            scenarioResetInto(fresh, scenario)
            assertEquals(
                "reset from $scenario",
                AccidentFlowState.Normal,
                fresh.reduce(AccidentEvent.Reset, now),
            )
        }
    }

    @Test
    fun reset_clearsTheCooldownSoDetectionWorksAgain() {
        machine.reduce(AccidentEvent.Detected(DetectionLevel.POSSIBLE), now)
        machine.reduce(AccidentEvent.UserOk, now)
        assertTrue(machine.isCoolingDown(now))

        machine.reduce(AccidentEvent.Reset, now)

        assertFalse(machine.isCoolingDown(now))
        assertTrue(machine.reduce(AccidentEvent.Detected(DetectionLevel.POSSIBLE), now) is
            AccidentFlowState.PossibleAccident)
    }

    // ------------------------------------------------------------------
    // Cooldown and repeated detections
    // ------------------------------------------------------------------

    @Test
    fun cooldown_stopsTheTailOfOneCrashFromRaisingAnother() {
        machine.reduce(AccidentEvent.Detected(DetectionLevel.POSSIBLE), now)
        machine.reduce(AccidentEvent.UserOk, now)

        val immediately = machine.reduce(AccidentEvent.Detected(DetectionLevel.POSSIBLE), now)
        assertEquals(AccidentFlowState.Normal, immediately)

        val later = machine.reduce(
            AccidentEvent.Detected(DetectionLevel.POSSIBLE),
            now + config.cooldownMillis,
        )
        assertTrue(later is AccidentFlowState.PossibleAccident)
    }

    @Test
    fun ongoingDetection_cannotRestartTheCountdown() {
        machine.reduce(AccidentEvent.Detected(DetectionLevel.POSSIBLE), now)
        machine.reduce(AccidentEvent.CountdownTick, now)
        val remaining = (machine.state as AccidentFlowState.PossibleAccident).secondsRemaining

        machine.reduce(AccidentEvent.Detected(DetectionLevel.HIGH_CONFIDENCE), now)

        val after = machine.state as AccidentFlowState.PossibleAccident
        assertEquals(remaining, after.secondsRemaining)
        assertEquals(DetectionLevel.POSSIBLE, after.level)
    }

    @Test
    fun normalDetectedAfterARecoveredWindow_doesNotDisturbTheFlow() {
        machine.reduce(AccidentEvent.Detected(DetectionLevel.POSSIBLE), now)
        machine.reduce(AccidentEvent.CountdownTick, now)

        assertEquals(machine.state, machine.reduce(AccidentEvent.Detected(DetectionLevel.NORMAL), now))
    }

    @Test
    fun emergencyIsNeverLeftByDetectionAlone() {
        machine.reduce(AccidentEvent.NeedHelp, now)

        machine.reduce(AccidentEvent.Detected(DetectionLevel.HIGH_CONFIDENCE), now)

        assertTrue(machine.state is AccidentFlowState.Emergency)
    }

    @Test
    fun everyDetectionLevel_staysPreEmergency() {
        // End-to-end proof that any verdict the detector can produce can only ever
        // reach the confirmation window, never the emergency flow.
        DetectionLevel.values().forEach { level ->
            val fresh = AccidentStateMachine(config)
            fresh.reduce(AccidentEvent.Detected(level), now)

            assertFalse("level $level escalated to emergency", fresh.state is AccidentFlowState.Emergency)
            assertTrue(
                "level $level produced an unexpected state",
                fresh.state == AccidentFlowState.Normal || fresh.state is AccidentFlowState.PossibleAccident,
            )
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun scenarioResetInto(machine: AccidentStateMachine, scenario: AccidentFlowState) {
        when (scenario) {
            AccidentFlowState.Normal -> Unit
            AccidentFlowState.Confirmation -> machine.reduce(AccidentEvent.ForceConfirmation, now)
            is AccidentFlowState.PossibleAccident -> machine.reduce(
                AccidentEvent.Detected(DetectionLevel.POSSIBLE),
                now,
            )

            is AccidentFlowState.Emergency -> machine.reduce(AccidentEvent.NeedHelp, now)
        }
    }
}