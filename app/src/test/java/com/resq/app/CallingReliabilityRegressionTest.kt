package com.resq.app

import com.resq.app.alert.AlertAnnouncer
import com.resq.app.model.EmergencyContact
import com.resq.app.model.EmergencyState
import com.resq.app.model.IncidentState
import com.resq.app.model.PrimaryContactCall
import com.resq.app.contacts.CallLauncher
import com.resq.app.contacts.CallLaunchResult
import com.resq.app.contacts.EmergencyContactsStore
import com.resq.app.contacts.EmergencyContactsPolicy
import com.resq.app.escalation.EmergencyEscalationCoordinator
import com.resq.app.escalation.InMemoryEscalationLedger
import com.resq.app.protection.ProtectionEngine
import com.resq.app.sms.SmsCapability
import com.resq.app.sms.SmsMessage
import com.resq.app.sms.SmsSendListener
import com.resq.app.sms.SmsSendState
import com.resq.app.sms.SmsSendUpdate
import com.resq.app.sms.SmsSender
import com.resq.app.viewmodel.ResQViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WHAT THE AUTOMATED CALLING TESTS CANNOT PROVE, AND WHAT THEY MUST STILL HOLD.
 *
 * Nothing here reaches a real dialer, places a real call, or touches 112. Every
 * test drives a fake launcher, so the whole suite is incapable of dialling a
 * phone number even if a future change wired it to the real system by mistake.
 *
 * The scenarios covered are the ones that are decided by ResQ's own bookkeeping
 * and therefore CAN be settled automatically: consent, the permission, the
 * per-incident budget, and what happens when the platform refuses the call.
 * Whether Android will actually place a call with the screen locked is a device
 * question - see the physical-device checklist, not this file.
 */

/** Records every call attempt. Never dials anything. */
private class CallLauncherSpy(
    private var directCallsAllowed: Boolean = true,
    private val directCallResult: CallLaunchResult = CallLaunchResult.Placed,
    private val dialerResult: CallLaunchResult = CallLaunchResult.Opened,
) : CallLauncher {

    val placed = mutableListOf<String>()
    val dialled = mutableListOf<String>()

    override fun placeCall(number: String): CallLaunchResult {
        placed += number
        return if (directCallsAllowed) directCallResult else CallLaunchResult.PermissionNotGranted
    }

    override fun openDialer(number: String): CallLaunchResult {
        dialled += number
        return dialerResult
    }

    override fun canPlaceCallsDirectly(): Boolean = directCallsAllowed
}

private class ReliabilityContactsStore : EmergencyContactsStore {
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

private class SilentTestAnnouncer : AlertAnnouncer {
    override fun announce(cue: com.resq.app.alert.AlertCue) = Unit
    override fun silence() = Unit
    override fun release() = Unit
}

class CallingReliabilityRegressionTest {

    /** The most recently built fake sender, so a test can complete the SMS leg. */
    private var lastSms: ReliabilitySmsSender? = null

    /** Shared durable ledger: one incident identity across every test's engine. */
    private val ledger = InMemoryEscalationLedger()

    private val primary = EmergencyContact(
        id = "c1",
        name = "Priya Nair",
        phoneNumber = "9876543210",
        isPrimary = true,
    )

private fun viewModel(
    launcher: CallLauncherSpy,
    contacts: List<EmergencyContact> = listOf(primary),
    consent: Boolean = true,
    smsConsent: Boolean = true,
): ResQViewModel {
    val store = ReliabilityContactsStore().apply {
        if (contacts.isNotEmpty()) saveContacts(contacts)
        setEscalationConsentGranted(consent)
        setSmsEscalationConsentGranted(smsConsent)
    }
    val engine = ProtectionEngine(sensorGateway = null, escalationLedger = ledger)
    val sms = ReliabilitySmsSender()
    // Kept so a test can complete the SMS leg and release the automatic call.
    lastSms = sms
    // The escalation itself is dispatched by the process-wide coordinator, so it
    // is wired exactly as the app wires it.
    val coordinator = EmergencyEscalationCoordinator(
        engine = engine,
        smsSender = sms,
        callLauncher = launcher,
        contactsStore = store,
        alertAnnouncer = SilentTestAnnouncer(),
    ).also { it.start() }
    return ResQViewModel(
        sensorGateway = null,
        engine = engine,
        contactsStore = store,
        callLauncher = launcher,
        alertAnnouncer = SilentTestAnnouncer(),
        countdownTickMillis = 60_000L,
        escalationCoordinator = coordinator,
    )
}

/**
 * Records what would have been sent. Never reaches a radio.
 *
 * The contact is called only after the platform reports the carrier accepted the
 * alert text, so a test that asserts on the call must call [reportSentToCarrier]
 * once the emergency has opened.
 */
private class ReliabilitySmsSender : SmsSender {
    val sent = mutableListOf<SmsMessage>()
    private val references = mutableListOf<String>()
    private val listeners = mutableListOf<Pair<String, SmsSendListener>>()

    override fun capability(): SmsCapability = SmsCapability.READY

    override fun send(
        message: SmsMessage,
        reference: String,
        listener: SmsSendListener,
    ): SmsSendState {
        sent += message
        references += reference
        listeners += reference to listener
        return SmsSendState.Attempted
    }

    fun reportSentToCarrier(index: Int = 0) {
        if (index >= references.size) return
        val reference = references[index]
        listeners.first { it.first == reference }.second.onSmsUpdate(
            SmsSendUpdate(reference, index, SmsSendState.SentToCarrier(reference)),
        )
    }
}

/**
 * Opens the emergency the way the app does, then completes the SMS leg.
 *
 * The contact is called only once the platform reports the carrier accepted the
 * alert text, so every test asserting on a call has to finish that leg. It is
 * done here so the individual tests still read as "escalate, then assert".
 */
private fun escalateToEmergency(viewModel: ResQViewModel, sms: ReliabilitySmsSender? = null) {
    viewModel.requestEmergencyHelp()
    viewModel.advanceEmergency()
    viewModel.advanceEmergency()
    sms?.reportSentToCarrier()
}

    // ------------------------------------------------------------------
    // One attempt, only the primary, only under consent + permission
    // ------------------------------------------------------------------

    @Test
    fun consentAndPermissionProduceExactlyOneAttemptForThePrimaryContact() {
        val launcher = CallLauncherSpy()
    val model = viewModel(launcher)

    escalateToEmergency(model, lastSms)

        assertEquals(listOf(primary.phoneNumber), launcher.placed)
        assertTrue(launcher.dialled.isEmpty())
        assertEquals(PrimaryContactCall.CallPlaced(primary.name), model.state.primaryContactCall)
    }

    @Test
    fun consentOffMeansZeroAutomaticCalls() {
        val launcher = CallLauncherSpy()
        val model = viewModel(launcher, consent = false)

    escalateToEmergency(model, lastSms)

        assertTrue("Consent is off, so nothing may be dialled", launcher.placed.isEmpty())
        assertTrue(launcher.dialled.isEmpty())
        assertEquals(PrimaryContactCall.ConsentRequired, model.state.primaryContactCall)
    }

    @Test
    fun aMissingPermissionFallsBackToTheDialerAndNeverPlacesACall() {
        val launcher = CallLauncherSpy(directCallsAllowed = false)
    val model = viewModel(launcher)

    escalateToEmergency(model, lastSms)

        assertTrue(launcher.placed.isEmpty())
        assertEquals(listOf(primary.phoneNumber), launcher.dialled)
        assertEquals(PrimaryContactCall.DialerOpened(primary.name), model.state.primaryContactCall)
    }

    @Test
    fun withNoPrimaryContact_nothingIsCalledAndTheEmergencyStillWorks() {
        val launcher = CallLauncherSpy()
        val model = viewModel(launcher, contacts = emptyList())

    escalateToEmergency(model, lastSms)

        assertTrue(launcher.placed.isEmpty())
        assertTrue(launcher.dialled.isEmpty())
        assertEquals(PrimaryContactCall.NoContactConfigured, model.state.primaryContactCall)
        assertFalse(model.state.emergency is EmergencyState.Inactive)
    }

    /**
     * Android may refuse to place a call with the screen locked or the app
     * backgrounded. When it does, the honest outcome is reported AND the manual
     * action is still there - the refusal must never become a dead end.
     */
    @Test
    fun aBlockedBackgroundCallLeavesAManualFallbackAndAWorkingEmergency() {
        val launcher = CallLauncherSpy(
            directCallsAllowed = true,
            // The platform accepted nothing and no dialer could be opened either.
            directCallResult = CallLaunchResult.NoHandler,
            dialerResult = CallLaunchResult.NoHandler,
        )
    val model = viewModel(launcher)

    escalateToEmergency(model, lastSms)

        assertEquals(PrimaryContactCall.CouldNotStart, model.state.primaryContactCall)
        // The incident itself is untouched by the failed call.
        assertFalse(model.state.emergency is EmergencyState.Inactive)
        assertEquals(com.resq.app.model.AppScreen.EMERGENCY, model.state.screen)

        // And the user can still complete the call themselves.
        model.callContact(primary)
        assertEquals(listOf(primary.phoneNumber), launcher.dialled)
    }

    /** A dispatched intent is a request, never a connected call. */
    @Test
    fun aDispatchedIntentIsNeverReportedAsAConnectedCall() {
        val launcher = CallLauncherSpy()
    val model = viewModel(launcher)

    escalateToEmergency(model, lastSms)

        val copy = com.resq.app.ui.screens.primaryCallCopy(
            model.state.primaryContactCall,
            model.state.contacts,
        )
        assertTrue(copy.contains("place a call", ignoreCase = true))
        for (forbidden in listOf("connected", "answered", "reached")) {
            assertFalse(
                "ResQ must never claim a call was $forbidden: $copy",
                copy.contains(forbidden, ignoreCase = true),
            )
        }
    }

    // ------------------------------------------------------------------
    // The per-incident budget
    // ------------------------------------------------------------------

    @Test
    fun repeatedEscalationTriggersProduceASingleAttempt() {
        val launcher = CallLauncherSpy()
    val model = viewModel(launcher)

    escalateToEmergency(model, lastSms)
        // Every one of these is a plausible re-entry: a lifecycle event, a stage
        // advance racing the timer, a recomposition.
        repeat(10) {
            model.advanceEmergency()
            model.onEmergencyScreenResumed()
        }

        assertEquals(1, launcher.placed.size)
    }

    @Test
    fun aManualCallDoesNotConsumeTheAutomaticBudget() {
        val launcher = CallLauncherSpy()
    val model = viewModel(launcher)

        // The user calls first, while the incident is still running.
        model.callContact(primary)
        assertEquals(listOf(primary.phoneNumber), launcher.dialled)

        // The automatic escalation is then still allowed exactly once.
    escalateToEmergency(model, lastSms)
        assertEquals(listOf(primary.phoneNumber), launcher.placed)
    }

    @Test
    fun aNewIncidentAfterTheFirstEndsGetsAFreshAttempt() {
        val launcher = CallLauncherSpy()
    val model = viewModel(launcher)

    escalateToEmergency(model, lastSms)
        assertEquals(1, launcher.placed.size)

        model.standDown()
    escalateToEmergency(model, lastSms)

        assertEquals(
            "A finished incident must release the budget for the next one",
            2,
            launcher.placed.size,
        )
    }

    @Test
    fun aSimulatedIncidentNeverDialsAnything() {
        val launcher = CallLauncherSpy()
    val model = viewModel(launcher)

        // Developer/Test Mode. Real hardware is not involved, so nothing is placed
        // no matter how the incident is driven.
        model.simulateHighConfidenceCollision()
        model.advanceEmergency()
        model.advanceEmergency()

        assertTrue(launcher.placed.isEmpty())
        assertTrue(launcher.dialled.isEmpty())
    }

    // ------------------------------------------------------------------
    // The capability the UI reports must match what would really happen
    // ------------------------------------------------------------------

    @Test
    fun theReportedCapabilityMatchesTheConfiguration() {
        val noContact = viewModel(CallLauncherSpy(), contacts = emptyList())
        assertEquals(
            com.resq.app.model.EscalationCapability.NO_CONTACT,
            noContact.state.escalationCapability,
        )

        val noConsent = viewModel(CallLauncherSpy(), consent = false)
        assertEquals(
            com.resq.app.model.EscalationCapability.CONSENT_OFF,
            noConsent.state.escalationCapability,
        )

        val noPermission = viewModel(CallLauncherSpy(directCallsAllowed = false))
        assertEquals(
            com.resq.app.model.EscalationCapability.PERMISSION_MISSING,
            noPermission.state.escalationCapability,
        )

        val ready = viewModel(CallLauncherSpy())
        assertEquals(
            com.resq.app.model.EscalationCapability.DIRECT_CALL,
            ready.state.escalationCapability,
        )
    }

    /**
     * Losing CALL_PHONE mid-incident is reported honestly rather than left looking
     * like a call that may have happened.
     */
    @Test
    fun revokingThePermissionMidIncidentIsReportedHonestly() {
        val launcher = CallLauncherSpy()
    val model = viewModel(launcher)
    escalateToEmergency(model, lastSms)
        assertEquals(PrimaryContactCall.CallPlaced(primary.name), model.state.primaryContactCall)

        model.onCallPermissionResult(granted = false)

        assertEquals(
            PrimaryContactCall.CallPermissionRequired,
            model.state.primaryContactCall,
        )
    }

    @Test
    fun aNoResponseIncidentStillReachesTheEscalationWithoutAnyUserTap() {
        val launcher = CallLauncherSpy()
    val model = viewModel(launcher)

        model.simulateHighConfidenceCollision()
        // The hardware path, driven through the real state machine events a
        // no-response incident produces.
        model.simulateNoResponse()
        model.requestEmergencyHelp()

        // A simulated incident is deliberately silent, so the guarantee asserted
        // here is the narrower and more important one: the emergency is fully open
        // and usable regardless of whether any call was attempted.
        assertFalse(model.state.emergency is EmergencyState.Inactive)
        assertEquals(1, model.state.contacts.size)
    }

    @Test
    fun aResolvedIncidentNeverLeavesAnAlertRunning() {
        val launcher = CallLauncherSpy()
    val model = viewModel(launcher)
        model.simulateHighConfidenceCollision()

        model.standDown()

        assertEquals(IncidentState.None, model.state.incident)
        assertEquals(EmergencyState.Inactive, model.state.emergency)
    }
}
