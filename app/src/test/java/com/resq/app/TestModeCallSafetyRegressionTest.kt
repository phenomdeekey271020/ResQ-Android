package com.resq.app

import com.resq.app.alert.AlertAnnouncer
import com.resq.app.contacts.CallLaunchResult
import com.resq.app.contacts.CallLauncher
import com.resq.app.contacts.EmergencyContactsPolicy
import com.resq.app.contacts.EmergencyContactsStore
import com.resq.app.detection.AccidentEvent
import com.resq.app.detection.AccidentFlowState
import com.resq.app.detection.AccidentStateMachine
import com.resq.app.detection.EmergencyStage
import com.resq.app.model.DetectionConfig
import com.resq.app.model.EmergencyContact
import com.resq.app.model.EmergencyState
import com.resq.app.model.IncidentState
import com.resq.app.model.countdownSeconds
import com.resq.app.viewmodel.ResQViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REGRESSION TESTS for two confirmed defects found in the end-to-end audit.
 *
 * 1. Developer/Test Mode call bypass (CRITICAL): "Simulate no response" used to
 *    drive the state machine without stamping the incident as SIMULATED, so a
 *    single Test Mode tap - with consent, a primary contact and CALL_PHONE - could
 *    escalate all the way to an automatic ACTION_CALL to a real person.
 *
 * 2. Countdown-ring total (HIGH): the alert countdown ring always drew against
 *    the per-level 10 s / 5 s windows even when the user had configured a flat
 *    20/30/45/60 s countdown, so the ring sat full for most of the window and
 *    then drained only over the last ten seconds.
 *
 * Nothing here reaches a real dialer: every test drives a fake launcher.
 */

/** Records every call attempt. Never dials anything. */
private class TestModeRecordingLauncher : CallLauncher {
    val placed = mutableListOf<String>()
    val dialled = mutableListOf<String>()

    override fun placeCall(number: String): CallLaunchResult {
        placed += number
        return CallLaunchResult.Placed
    }

    override fun openDialer(number: String): CallLaunchResult {
        dialled += number
        return CallLaunchResult.Opened
    }

    override fun canPlaceCallsDirectly(): Boolean = true
}

private class RegressionContactsStore : EmergencyContactsStore {
    private var stored = emptyList<EmergencyContact>()
    private var consent = false
    private var smsConsent = false

    override fun contacts(): List<EmergencyContact> = stored
    override fun saveContacts(contacts: List<EmergencyContact>) {
        stored = EmergencyContactsPolicy.ensureSinglePrimary(contacts)
    }

    override fun isEscalationConsentGranted(): Boolean = consent
    override fun setEscalationConsentGranted(granted: Boolean) {
        consent = granted
    }

    override fun isSmsEscalationConsentGranted(): Boolean = smsConsent
    override fun setSmsEscalationConsentGranted(granted: Boolean) {
        smsConsent = granted
    }
}

private class RegressionSilentAnnouncer : AlertAnnouncer {
    override fun announce(cue: com.resq.app.alert.AlertCue) = Unit
    override fun silence() = Unit
    override fun release() = Unit
}

class TestModeCallSafetyRegressionTest {

    private val primary = EmergencyContact(
        id = "c1",
        name = "Test Contact",
        phoneNumber = "9876543210",
        isPrimary = true,
    )

    private fun viewModel(
        launcher: CallLauncher,
        config: DetectionConfig = DetectionConfig(),
    ): ResQViewModel {
        val store = RegressionContactsStore().apply {
            saveContacts(listOf(primary))
            setEscalationConsentGranted(true)
        }
        return ResQViewModel(
            sensorGateway = null,
            config = config,
            contactsStore = store,
            callLauncher = launcher,
            alertAnnouncer = RegressionSilentAnnouncer(),
            countdownTickMillis = 60_000L,
        )
    }

    // ------------------------------------------------------------------
    // 1. Test Mode must never dial, on ANY of its entry paths
    // ------------------------------------------------------------------

    @Test
    fun simulateNoResponseFromACalmScreenEscalatesButNeverDials() {
        val launcher = TestModeRecordingLauncher()
        val model = viewModel(launcher)

        // One tap on the Developer/Test screen, with the full escalation
        // configuration in place - consent, primary contact and CALL_PHONE.
        model.simulateNoResponse()
        assertEquals(IncidentState.Confirmation, model.state.incident)

        // The confirmation window ends, exactly as the background timer would.
        model.simulateNoResponse()

        assertTrue(
            "The emergency itself must still open",
            model.state.emergency is EmergencyState.Preparing ||
                model.state.emergency is EmergencyState.Ready,
        )
        assertTrue(
            "A Test Mode incident must never place a call",
            launcher.placed.isEmpty(),
        )
        assertTrue(
            "A Test Mode incident must never open the dialer either",
            launcher.dialled.isEmpty(),
        )
    }

    @Test
    fun simulateNoResponseDuringAnAlertNeverDials() {
        val launcher = TestModeRecordingLauncher()
        val model = viewModel(launcher)

        // Possible-accident alert is already on screen (a simulated collision),
        // then the Test Mode "no response" control drives it to escalation.
        model.simulatePossibleCollision()
        model.simulateNoResponse() // PossibleAccident -> Confirmation
        model.simulateNoResponse() // Confirmation -> Emergency

        assertTrue(model.state.emergency !is EmergencyState.Inactive)
        assertTrue(launcher.placed.isEmpty())
        assertTrue(launcher.dialled.isEmpty())
    }

    // ------------------------------------------------------------------
    // 2. The countdown ring must draw against the CONFIGURED window
    // ------------------------------------------------------------------

    @Test
    fun aConfiguredAlertCountdownIsCarriedIntoTheIncidentTotal() {
        val model = viewModel(
            TestModeRecordingLauncher(),
            config = DetectionConfig(alertCountdownSeconds = 30),
        )

        model.simulatePossibleCollision()

        val incident = model.state.incident as? IncidentState.PossibleImpact
        assertNotNull("Simulation must raise the possible-impact alert", incident)
        assertEquals(30, incident!!.secondsRemaining)
        assertEquals(
            "The ring total must match the configured window, not the per-level default",
            30,
            incident.totalSeconds,
        )
    }

    @Test
    fun withoutAConfiguredOverrideThePerLevelTotalIsUsed() {
        val model = viewModel(TestModeRecordingLauncher())

        model.simulatePossibleCollision()

        val incident = model.state.incident as? IncidentState.PossibleImpact
        assertNotNull(incident)
        assertEquals(incident!!.confidence.countdownSeconds, incident.totalSeconds)
    }

    // ------------------------------------------------------------------
    // 3. NeedHelp must not rewind an already-open emergency
    // ------------------------------------------------------------------

    @Test
    fun needHelpWhileEmergencyIsOpenDoesNotRewindTheStage() {
        val machine = AccidentStateMachine(DetectionConfig())
        machine.reduce(AccidentEvent.NeedHelp, 0L)
        machine.reduce(AccidentEvent.EmergencyAdvanced, 0L)
        machine.reduce(AccidentEvent.EmergencyAdvanced, 0L)
        val advanced = machine.state as AccidentFlowState.Emergency
        assertEquals(EmergencyStage.SEARCHING_RESOURCES, advanced.stage)

        // A double tap on "I need help" during the screen crossfade.
        val after = machine.reduce(AccidentEvent.NeedHelp, 0L)

        assertEquals(
            "A repeated NeedHelp must leave the stage exactly where it was",
            EmergencyStage.SEARCHING_RESOURCES,
            (after as AccidentFlowState.Emergency).stage,
        )
    }
}
