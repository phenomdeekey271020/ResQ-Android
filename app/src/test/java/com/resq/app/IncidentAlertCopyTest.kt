package com.resq.app

import com.resq.app.detection.AccidentFlowState
import com.resq.app.detection.EmergencyStage
import com.resq.app.model.DetectionLevel
import com.resq.app.protection.ProtectionSnapshot
import com.resq.app.service.incidentAlertCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The alert notification is the fallback when the spoken warning cannot play, so
 * it exists for exactly the states that need the user's attention and for no
 * others. A notification left behind after the incident would be worse than none
 * at all, which is what these tests guard against.
 */
class IncidentAlertCopyTest {

    private fun monitoring(flow: AccidentFlowState) = ProtectionSnapshot(
        monitoring = true,
        flow = flow,
    )

    @Test
    fun aStoppedService_hasNoIncidentNotification() {
        assertNull(incidentAlertCopy(ProtectionSnapshot()))
    }

    @Test
    fun aCalmFlow_hasNoIncidentNotification() {
        assertNull(incidentAlertCopy(monitoring(AccidentFlowState.Normal)))
    }

    @Test
    fun aPossibleAccident_isAlerted() {
        val copy = incidentAlertCopy(
            monitoring(
                AccidentFlowState.PossibleAccident(
                    secondsRemaining = 10,
                    level = DetectionLevel.POSSIBLE,
                ),
            ),
        )

        assertNotNull(copy)
        assertEquals("ResQ - possible incident detected", copy?.title)
    }

    @Test
    fun theUnconfirmedState_isAlertedAndAsksForConfirmation() {
        val copy = incidentAlertCopy(monitoring(AccidentFlowState.Confirmation))

        assertNotNull(copy)
        assertEquals("ResQ - please confirm you are safe", copy?.title)
    }

    @Test
    fun anActiveEmergency_isAlerted() {
        val copy = incidentAlertCopy(
            monitoring(AccidentFlowState.Emergency(EmergencyStage.LOCATING)),
        )

        assertNotNull(copy)
        assertEquals("ResQ incident - open the app", copy?.title)
    }

    @Test
    fun theIncidentCopy_neverClaimsAnIncidentServiceWasContacted() {
        val flows = listOf(
            AccidentFlowState.PossibleAccident(10, DetectionLevel.POSSIBLE),
            AccidentFlowState.Confirmation,
            AccidentFlowState.Emergency(EmergencyStage.LOCATING),
        )

        flows.forEach { flow ->
            val copy = incidentAlertCopy(monitoring(flow))
            val wording = "${copy?.title} ${copy?.text}".lowercase()
            assertFalse(flow.name, wording.contains("contacted"))
            assertFalse(flow.name, wording.contains("ambulance"))
            assertFalse(flow.name, wording.contains("dispatched"))
        }
    }
}
