package com.resq.app

import com.resq.app.detection.AccidentFlowState
import com.resq.app.detection.EmergencyStage
import com.resq.app.model.DetectionLevel
import com.resq.app.protection.ProtectionSnapshot
import com.resq.app.service.protectionNotificationCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notification is the only thing ResQ says to the user while the app is not
 * in front, so every sentence it can contain is asserted here. These tests fail
 * if the wording ever claims a confirmed crash, or claims that anybody was
 * contacted - neither is true of this build.
 */
class ProtectionNotificationCopyTest {

    private fun monitoring(flow: AccidentFlowState) = ProtectionSnapshot(
        monitoring = true,
        flow = flow,
    )

    private fun possible(level: DetectionLevel, seconds: Int) =
        AccidentFlowState.PossibleAccident(secondsRemaining = seconds, level = level)

    @Test
    fun aStoppedService_hasNoNotification() {
        assertNull(protectionNotificationCopy(ProtectionSnapshot()))
    }

    @Test
    fun protectionWhileMonitoring_claimsOnlyThatResQIsMonitoring() {
        val copy = protectionNotificationCopy(monitoring(AccidentFlowState.Normal))

        assertNotNull(copy)
        assertEquals("ResQ Protection Active", copy?.title)
        assertEquals("Monitoring for possible incidents", copy?.text)
    }

    @Test
    fun aPossibleAccident_isReportedAsPossibleAndNeverAsACrash() {
        val copy = protectionNotificationCopy(monitoring(possible(DetectionLevel.POSSIBLE, 10)))

        val wording = "${copy?.title} ${copy?.text}".lowercase()
        assertTrue(wording.contains("possible"))
        assertTrue(!wording.contains("crash"))
        assertTrue(!wording.contains("emergency service"))
        assertTrue(!wording.contains("called"))
        assertTrue(!wording.contains("contacted"))
    }

    @Test
    fun anUnansweredAlert_asksTheUserToConfirmTheyAreSafe() {
        val copy = protectionNotificationCopy(monitoring(AccidentFlowState.Confirmation))

        assertTrue("${copy?.title} ${copy?.text}".contains("confirm", ignoreCase = true))
    }

    @Test
    fun theEmergencyState_statesPlainlyThatNothingWasSent() {
        val copy = protectionNotificationCopy(
            monitoring(AccidentFlowState.Emergency(EmergencyStage.LOCATING)),
        )

        assertEquals("The emergency screen is open in ResQ. Nothing has been called or sent.", copy?.text)
    }

    @Test
    fun everyFlowStateHasItsOwnWording() {
        val copies = listOf(
            AccidentFlowState.Normal,
            possible(DetectionLevel.POSSIBLE, 10),
            AccidentFlowState.Confirmation,
            AccidentFlowState.Emergency(EmergencyStage.LOCATING),
        ).map { protectionNotificationCopy(monitoring(it)) }

        assertTrue(copies.all { it != null })
        assertEquals(copies.size, copies.distinct().size)
    }

    @Test
    fun bothDetectionLevels_shareTheSameHonestWording() {
        // The notification never quotes a confidence number: it is a prototype
        // heuristic, not a probability, so both levels are described the same way.
        val possibleCopy = protectionNotificationCopy(monitoring(possible(DetectionLevel.POSSIBLE, 10)))
        val highCopy = protectionNotificationCopy(monitoring(possible(DetectionLevel.HIGH_CONFIDENCE, 5)))

        assertEquals(possibleCopy, highCopy)
        assertTrue(possibleCopy != protectionNotificationCopy(monitoring(AccidentFlowState.Normal)))
    }

    @Test
    fun theCalmWordingIsNeverReusedWhileAnAlertIsOpen() {
        val calm = protectionNotificationCopy(monitoring(AccidentFlowState.Normal))
        val alert = protectionNotificationCopy(monitoring(possible(DetectionLevel.POSSIBLE, 10)))

        assertNotEquals(calm, alert)
    }

    @Test
    fun theCopyIsAPureFunctionOfTheSnapshot() {
        val snapshot = monitoring(AccidentFlowState.Normal)

        assertEquals(protectionNotificationCopy(snapshot), protectionNotificationCopy(snapshot))
    }
}