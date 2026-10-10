package com.resq.app

import com.resq.app.alert.AlertAnnouncer
import com.resq.app.alert.AlertCue
import com.resq.app.contacts.CallLaunchResult
import com.resq.app.contacts.CallLauncher
import com.resq.app.contacts.EmergencyContactsStore
import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SensorObserver
import com.resq.app.detection.AccidentEvent
import com.resq.app.detection.AccidentFlowState
import com.resq.app.escalation.EmergencyEscalationCoordinator
import com.resq.app.escalation.EscalationLedger
import com.resq.app.escalation.InMemoryEscalationLedger
import com.resq.app.model.AccelerometerState
import com.resq.app.model.EmergencyContact
import com.resq.app.model.GyroscopeState
import com.resq.app.model.PrimaryContactCall
import com.resq.app.model.SmsEscalation
import com.resq.app.model.SensorAvailability
import com.resq.app.protection.ProtectionEngine
import com.resq.app.protection.ProtectionSource
import com.resq.app.sms.SmsAlertMessageBuilder
import com.resq.app.sms.SmsCapability
import com.resq.app.sms.SmsFailureReason
import com.resq.app.sms.SmsMessage
import com.resq.app.sms.SmsRecipientsPolicy
import com.resq.app.sms.SmsSendListener
import com.resq.app.sms.SmsSendState
import com.resq.app.sms.SmsSendUpdate
import com.resq.app.sms.SmsSender
import com.resq.app.ui.screens.EmergencyNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * REGRESSION TESTS FOR THE ESCALATION COORDINATOR.
 *
 * These drive the real coordinator against a real ProtectionEngine with fakes
 * for the SMS sender, the call launcher and the announcer. No test in this file
 * can reach a real radio, a real dialer or a real speaker - the fakes record
 * intent and return scripted results, so running the suite can never send a
 * message or place a call.
 *
 * The cases pin down both halves: what must happen, and - far more importantly -
 * everything that must NOT.
 */

/** Records what would have been sent and can be scripted to fail. */
private class FakeSmsSender(
    private var capability: SmsCapability = SmsCapability.READY,
) : SmsSender {

    val sent = mutableListOf<SmsMessage>()
    val references = mutableListOf<String>()
    private val listeners = mutableListOf<Pair<String, SmsSendListener>>()

    /** Scripted synchronous outcome per recipient index. */
    val scriptedOutcomes = mutableMapOf<Int, SmsSendState>()

    override fun capability(): SmsCapability = capability

    fun setCapability(value: SmsCapability) {
        capability = value
    }

    override fun send(
        message: SmsMessage,
        reference: String,
        listener: SmsSendListener,
    ): SmsSendState {
        val index = sent.size
        sent += message
        references += reference
        listeners += reference to listener
        return scriptedOutcomes[index] ?: SmsSendState.Attempted
    }

    /** The platform reports the carrier accepted message [index]. */
    fun reportSentToCarrier(index: Int) {
        val reference = references[index]
        listeners.first { it.first == reference }.second.onSmsUpdate(
            SmsSendUpdate(reference, index, SmsSendState.SentToCarrier(reference)),
        )
    }

    /** A delivery receipt arrives for message [index]. */
    fun reportDelivered(index: Int) {
        val reference = references[index]
        listeners.first { it.first == reference }.second.onSmsUpdate(
            SmsSendUpdate(reference, index, SmsSendState.Delivered(reference)),
        )
    }

    /** The platform reports message [index] FAILED at send time. */
    fun reportFailed(index: Int) {
        val reference = references[index]
        listeners.first { it.first == reference }.second.onSmsUpdate(
            SmsSendUpdate(reference, index, SmsSendState.Failed(SmsFailureReason.SEND_FAILED)),
        )
    }

    fun onlyBody(): String = sent.single().body
}

/** Records call intent. Places nothing, dials nothing. */
private class CoordinatorFakeCallLauncher(
    private var directCallsAllowed: Boolean = true,
) : CallLauncher {
    val placed = mutableListOf<String>()
    val dialed = mutableListOf<String>()

    override fun canPlaceCallsDirectly(): Boolean = directCallsAllowed

    override fun placeCall(number: String): CallLaunchResult {
        placed += number
        return CallLaunchResult.Placed
    }

    override fun openDialer(number: String): CallLaunchResult {
        dialed += number
        return CallLaunchResult.Opened
    }

    fun allowDirectCalls(allowed: Boolean) {
        directCallsAllowed = allowed
    }
}

/** Records every cue and silence, and plays nothing. */
private class RecordingAnnouncer : AlertAnnouncer {
    val cues = mutableListOf<AlertCue>()
    var silenceCount = 0

    override fun announce(cue: AlertCue) {
        cues += cue
    }

    override fun silence() {
        silenceCount++
    }

    override fun release() = Unit
}

private class Store(
    private var contacts: List<EmergencyContact> = emptyList(),
    private var callConsent: Boolean = false,
    private var smsConsent: Boolean = false,
) : EmergencyContactsStore {
    override fun contacts(): List<EmergencyContact> = contacts
    override fun saveContacts(contacts: List<EmergencyContact>) {
        this.contacts = contacts
    }

    override fun isEscalationConsentGranted(): Boolean = callConsent
    override fun setEscalationConsentGranted(granted: Boolean) {
        callConsent = granted
    }

    override fun isSmsEscalationConsentGranted(): Boolean = smsConsent
    override fun setSmsEscalationConsentGranted(granted: Boolean) {
        smsConsent = granted
    }
}

/** A gateway that can raise a real-looking hardware incident. */
private class Gateway : SensorGateway {
    private var observer: SensorObserver? = null
    private var timestamp = 1_700_000_000_000L

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

    fun emitImpact() {
        repeat(5) {
            observer?.onAccelerometer(
                AccelerometerState(
                    availability = SensorAvailability.READY,
                    x = 28.0f,
                    y = 4.0f,
                    z = -6.0f,
                    timestampMillis = timestamp,
                ),
            )
            observer?.onGyroscope(
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
}

private val PRIMARY = EmergencyContact(
    id = "a",
    name = "Priya Nair",
    phoneNumber = "9876543210",
    isPrimary = true,
)
private val SECONDARY = EmergencyContact(
    id = "b",
    name = "Daniel Ortiz",
    phoneNumber = "9876543299",
)

/**
 * One assembled app: a real engine, the real coordinator, and fakes for
 * everything with an outside effect.
 */
private class Harness(
    contacts: List<EmergencyContact> = listOf(PRIMARY),
    smsConsent: Boolean = true,
    callConsent: Boolean = true,
    capability: SmsCapability = SmsCapability.READY,
    ledger: EscalationLedger = InMemoryEscalationLedger(),
    /**
     * An existing engine to attach to, so a test can model a coordinator being
     * rebuilt over the process-wide engine after an Activity recreation.
     */
    sharedEngine: ProtectionEngine? = null,
) {
    val gateway = Gateway()
    val sender = FakeSmsSender(capability)
    val launcher = CoordinatorFakeCallLauncher()
    val announcer = RecordingAnnouncer()
    val store = Store(contacts, callConsent, smsConsent)

    val engine: ProtectionEngine =
        sharedEngine ?: ProtectionEngine(gateway, escalationLedger = ledger)
    val coordinator = EmergencyEscalationCoordinator(
        engine = engine,
        smsSender = sender,
        callLauncher = launcher,
        contactsStore = store,
        alertAnnouncer = announcer,
    ).also { it.start() }

    /** Continuous protection is on: the only source allowed to raise an incident. */
    fun hardwareIncident() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact()
    }

    /** Requirement 1: the user taps "I Need Help". */
    fun userTapsNeedHelp() {
        engine.submit(AccidentEvent.NeedHelp)
        assertTrue(engine.flow is AccidentFlowState.Emergency)
    }

    /** Requirement 2: a real no-response timeout, driven by the service. */
    fun noResponseTimeout() {
        hardwareIncident()
        assertTrue(engine.flow is AccidentFlowState.PossibleAccident)
        // What the foreground service does: submit NoResponse to the engine
        // directly. NO ViewModel is involved anywhere in this harness.
        engine.submit(AccidentEvent.NoResponse)
        assertEquals(AccidentFlowState.Confirmation, engine.flow)
        engine.submit(AccidentEvent.NoResponse)
        assertTrue(engine.flow is AccidentFlowState.Emergency)
    }

    /** A simulated incident, exactly as Developer/Test Mode opens one. */
    fun simulatedIncident() {
        engine.submit(AccidentEvent.Detected(com.resq.app.model.DetectionLevel.HIGH_CONFIDENCE), simulated = true)
        engine.submit(AccidentEvent.NoResponse)
        engine.submit(AccidentEvent.NoResponse)
    }
}

class EscalationCoordinatorTest {

    // ------------------------------------------------------------------
    // Requirement 1 - "I Need Help"
    // ------------------------------------------------------------------

    @Test
    fun needHelp_sendsOneAlertTextToEveryEligibleContact() {
        val harness = Harness()

        harness.userTapsNeedHelp()

        assertEquals(
            listOf(PRIMARY.phoneNumber),
            harness.sender.sent.map { it.recipientNumber },
        )
    }

    @Test
    fun needHelp_includesWhatHappenedAndWhatToDo() {
        val harness = Harness()

        harness.userTapsNeedHelp()

        val body = harness.sender.onlyBody()
        assertTrue("Says ResQ: $body", body.contains("ResQ alert"))
        assertTrue("Says help is needed: $body", body.contains("unable to respond"))
        assertTrue("Tells the recipient what to do: $body", body.contains("check on them in person"))
        assertTrue("Gives a time: $body", body.contains("Detected at"))
    }

    // ------------------------------------------------------------------
    // Requirement 2 - real no-response timeout, with no ViewModel anywhere
    // ------------------------------------------------------------------

    @Test
    fun aNoResponseTimeout_sendsTheAlertTextWithNoViewModelInTheLoop() {
        // This harness contains NO ResQViewModel at all - it is the direct
        // regression for the lifecycle bug where the escalation could only run
        // from a ViewModel that Android was free to destroy.
        val harness = Harness()

        harness.noResponseTimeout()

        assertEquals(1, harness.sender.sent.size)
        assertEquals(PRIMARY.phoneNumber, harness.sender.sent.single().recipientNumber)
    }

    @Test
    fun aNoResponseTimeout_keepsTheEmergencyState() {
        val harness = Harness()

        harness.noResponseTimeout()

        assertTrue("The emergency must remain active", harness.engine.flow is AccidentFlowState.Emergency)
    }

    @Test
    fun repeatedServiceEventsForTheSameIncident_neverSendASecondMessage() {
        val harness = Harness()
        harness.noResponseTimeout()
        assertEquals(1, harness.sender.sent.size)

        // The service ticking again, a lifecycle resume, and an extra snapshot:
        // none may produce a second message.
        harness.engine.submit(AccidentEvent.NoResponse)
        harness.engine.submit(AccidentEvent.EmergencyAdvanced)
        harness.engine.submit(AccidentEvent.NoResponse)

        assertEquals(1, harness.sender.sent.size)
    }

    @Test
    fun aRecreatedListenerForTheSameIncident_doesNotDuplicateSideEffects() {
        val ledger = InMemoryEscalationLedger()
        val first = Harness(ledger = ledger)
        first.noResponseTimeout()
        first.sender.reportSentToCarrier(0)
        assertEquals(1, first.sender.sent.size)
        assertEquals(listOf(PRIMARY.phoneNumber), first.launcher.placed)

        // A brand-new coordinator over the SAME engine and SAME durable ledger:
        // exactly what an Activity recreation produces. It must recognise that
        // this incident has already been escalated and send nothing at all -
        // which is why its own sender has no messages to report on.
        val second = Harness(ledger = ledger, sharedEngine = first.engine)
        second.coordinator.start()
        second.engine.submit(AccidentEvent.NoResponse)
        second.engine.submit(AccidentEvent.EmergencyAdvanced)

        assertTrue(
            "A recreated coordinator must not re-send for the same incident",
            second.sender.sent.isEmpty(),
        )
        assertEquals("The ledger must survive recreation", 1, first.sender.sent.size)
        assertEquals("The call must not repeat", 1, first.launcher.placed.size)
    }

    @Test
    fun aNewIncidentAfterTheFirst_getsItsOwnAlertText() {
        val harness = Harness()
        harness.noResponseTimeout()
        assertEquals(1, harness.sender.sent.size)

        harness.engine.submit(AccidentEvent.Reset)
        harness.noResponseTimeout()

        assertEquals("Each real incident earns its own message", 2, harness.sender.sent.size)
    }

    // ------------------------------------------------------------------
    // Simulated incidents - and the stale-origin defect (audit finding B)
    // ------------------------------------------------------------------

    @Test
    fun aSimulatedIncident_neverSendsAndNeverCalls() {
        val harness = Harness()

        harness.simulatedIncident()

        assertTrue("Test Mode must never send", harness.sender.sent.isEmpty())
        assertTrue("Test Mode must never call", harness.launcher.placed.isEmpty())
        assertTrue(harness.launcher.dialed.isEmpty())
        assertEquals(SmsEscalation.NotAttempted, harness.coordinator.smsOutcome)
        assertEquals(PrimaryContactCall.NotAttempted, harness.coordinator.callOutcome)
    }

    @Test
    fun simulatedThenStandDownThenARealUserInitiatedEmergency_doesEscalate() {
        // THE REGRESSION FOR AUDIT FINDING B. The old ViewModel field was never
        // reset, so this new, genuine emergency inherited the SIMULATED origin of
        // an unrelated earlier test and was silently denied its escalation.
        val harness = Harness()

        harness.simulatedIncident()
        harness.engine.submit(AccidentEvent.Reset)
        harness.engine.submit(AccidentEvent.NeedHelp)

        assertTrue(
            "A user-initiated emergency after a simulated one MUST still escalate",
            harness.sender.sent.size == 1,
        )
        assertEquals(PRIMARY.phoneNumber, harness.sender.sent.single().recipientNumber)
    }

    @Test
    fun anIncidentStartedByAServiceEventIsNotSilentlyTreatedAsSimulated() {
        // The simulated flag is a property of the INCIDENT, decided when it opens.
        // A later real CountdownTick/NoResponse from the service must not change
        // it - and equally, a real incident must not inherit one.
        val harness = Harness()

        harness.simulatedIncident()
        assertTrue(harness.engine.incidentSimulated)
        harness.engine.submit(AccidentEvent.Reset)

        harness.hardwareIncident()
        assertFalse("A new hardware incident must not be simulated", harness.engine.incidentSimulated)
    }

    // ------------------------------------------------------------------
    // Requirement 3 - the call happens only after carrier handoff
    // ------------------------------------------------------------------

    @Test
    fun theCarrierHandoff_isWhatAuthorisesThePrimaryContactCall() {
        val harness = Harness()
        harness.noResponseTimeout()

        // ATTEMPTED alone - SmsManager took the message - is NOT enough.
        assertTrue("Nothing may be called before the carrier accepts", harness.launcher.placed.isEmpty())
        assertTrue(harness.launcher.dialed.isEmpty())

        harness.sender.reportSentToCarrier(0)

        assertEquals("The call is authorised by carrier handoff", listOf(PRIMARY.phoneNumber), harness.launcher.placed)
    }

    @Test
    fun aDeliveryReceiptAlsoAuthorisesTheCall() {
        val harness = Harness()
        harness.noResponseTimeout()

        harness.sender.reportDelivered(0)

        assertEquals(listOf(PRIMARY.phoneNumber), harness.launcher.placed)
    }

    @Test
    fun carrierHandoff_callsThePrimaryContactExactlyOnce() {
        val harness = Harness()
        harness.noResponseTimeout()

        harness.sender.reportSentToCarrier(0)
        harness.sender.reportSentToCarrier(0)
        harness.sender.reportDelivered(0)
        harness.engine.submit(AccidentEvent.EmergencyAdvanced)

        assertEquals("Exactly one call per incident", 1, harness.launcher.placed.size)
    }

    @Test
    fun onlyThePrimaryContactIsEverCalledAutomatically() {
        val harness = Harness(contacts = listOf(PRIMARY, SECONDARY))
        harness.noResponseTimeout()

        harness.sender.reportSentToCarrier(0)
        harness.sender.reportSentToCarrier(1)

        assertEquals(listOf(PRIMARY.phoneNumber), harness.launcher.placed)
        assertFalse(harness.launcher.placed.contains(SECONDARY.phoneNumber))
    }

    @Test
    fun theCallNeverTargetsTheEmergencyNumber() {
        val harness = Harness(
            contacts = listOf(
                EmergencyContact("e", "Emergency", EmergencyNumber, isPrimary = true),
                SECONDARY,
            ),
        )
        harness.noResponseTimeout()

        harness.sender.reportSentToCarrier(0)

        val everyNumber = harness.launcher.placed + harness.launcher.dialed
        assertFalse(
            "112 must never be dialled automatically",
            everyNumber.contains(EmergencyNumber),
        )
    }

    // ------------------------------------------------------------------
    // Requirement 3.7 - an SMS that does not reach the carrier blocks the call
    // ------------------------------------------------------------------

    @Test
    fun anSmsThatFails_neverTriggersTheAutomaticCall() {
        val harness = Harness()
        harness.sender.scriptedOutcomes[0] = SmsSendState.Failed(SmsFailureReason.SEND_FAILED)
        harness.noResponseTimeout()

        assertEquals(
            SmsEscalation.CouldNotSend(SmsEscalation.SmsFailureReasonMirror.SEND_FAILED),
            harness.coordinator.smsOutcome,
        )
        assertTrue("No call may follow a failed message", harness.launcher.placed.isEmpty())
        assertTrue(harness.launcher.dialed.isEmpty())
    }

    @Test
    fun anSmsThatFailsAfterTheRequest_neverTriggersTheAutomaticCall() {
        val harness = Harness()
        harness.noResponseTimeout()

        harness.sender.reportFailed(0)

        assertTrue(harness.launcher.placed.isEmpty())
    }

    @Test
    fun aPartialSuccess_callsThePrimaryContactBecauseOneMessageWasAccepted() {
        val harness = Harness(contacts = listOf(PRIMARY, SECONDARY))
        harness.sender.scriptedOutcomes[1] = SmsSendState.Failed(SmsFailureReason.SEND_FAILED)
        harness.noResponseTimeout()

        val state = harness.coordinator.smsOutcome
        assertTrue("Must be reported as partial: $state", state is SmsEscalation.PartiallySent)

        harness.sender.reportSentToCarrier(0)

        assertEquals(listOf(PRIMARY.phoneNumber), harness.launcher.placed)
    }

    // ------------------------------------------------------------------
    // Missing safeguards must never invent a destination
    // ------------------------------------------------------------------

    @Test
    fun withoutSmsConsent_nothingIsSentAndNothingIsCalled() {
        val harness = Harness(smsConsent = false)

        harness.noResponseTimeout()

        assertTrue(harness.sender.sent.isEmpty())
        assertEquals(SmsEscalation.ConsentRequired, harness.coordinator.smsOutcome)
        assertTrue(harness.launcher.placed.isEmpty())
    }

    @Test
    fun withoutTheSmsPermission_nothingIsSentAndNothingIsCalled() {
        val harness = Harness(capability = SmsCapability.PERMISSION_DENIED)

        harness.noResponseTimeout()

        assertTrue(harness.sender.sent.isEmpty())
        assertEquals(SmsEscalation.SmsPermissionRequired, harness.coordinator.smsOutcome)
        assertTrue(harness.launcher.placed.isEmpty())
    }

    @Test
    fun withoutASim_nothingIsSentAndNothingIsCalled() {
        val harness = Harness(capability = SmsCapability.NO_SIM)

        harness.noResponseTimeout()

        assertTrue(harness.sender.sent.isEmpty())
        assertEquals(SmsEscalation.NoSimAvailable, harness.coordinator.smsOutcome)
        assertTrue(harness.launcher.placed.isEmpty())
    }

    @Test
    fun withNoContacts_nothingIsSentOrCalledAndNoNumberIsInvented() {
        val harness = Harness(contacts = emptyList())

        harness.noResponseTimeout()

        assertTrue(harness.sender.sent.isEmpty())
        assertEquals(SmsEscalation.NoContactConfigured, harness.coordinator.smsOutcome)
        assertEquals(PrimaryContactCall.NoContactConfigured, harness.coordinator.callOutcome)
        assertTrue(harness.launcher.placed.isEmpty())
        assertTrue(harness.launcher.dialed.isEmpty())
    }

    @Test
    fun withoutCallConsent_theMessageStillGoesAndNoCallIsPlaced() {
        val harness = Harness(callConsent = false)

        harness.noResponseTimeout()
        harness.sender.reportSentToCarrier(0)

        assertEquals(1, harness.sender.sent.size)
        assertTrue("No consent, no automatic call", harness.launcher.placed.isEmpty())
        assertEquals(PrimaryContactCall.ConsentRequired, harness.coordinator.callOutcome)
    }

    @Test
    fun withoutCallPermission_theExistingDialerFallbackIsUsed() {
        val harness = Harness()
        harness.launcher.allowDirectCalls(false)

        harness.noResponseTimeout()
        harness.sender.reportSentToCarrier(0)

        assertTrue("No direct call may be attempted", harness.launcher.placed.isEmpty())
        assertEquals(listOf(PRIMARY.phoneNumber), harness.launcher.dialed)
        assertEquals(
            PrimaryContactCall.DialerOpened(PRIMARY.name),
            harness.coordinator.callOutcome,
        )
    }

    // ------------------------------------------------------------------
    // Requirement 2.5 - silencing is tied to carrier handoff ONLY
    // ------------------------------------------------------------------

    @Test
    fun aSuccessfulCarrierHandoff_silencesResQsOwnAlert() {
        val harness = Harness()
        harness.noResponseTimeout()
        assertEquals("Nothing may be silenced before handoff", 0, harness.announcer.silenceCount)

        harness.sender.reportSentToCarrier(0)

        assertTrue("ResQ's own alert must stop", harness.announcer.silenceCount > 0)
    }

    @Test
    fun theEmergencyStateAndUiSurviveSilencing() {
        val harness = Harness()
        harness.noResponseTimeout()
        harness.sender.reportSentToCarrier(0)

        assertTrue(
            "Silencing the alert must NOT leave the Emergency state",
            harness.engine.flow is AccidentFlowState.Emergency,
        )
    }

    @Test
    fun anAttemptedButUnconfirmedSend_doesNotSilenceTheAlert() {
        val harness = Harness()
        harness.noResponseTimeout()

        // SmsManager accepted the request; the carrier has not reported anything.
        assertEquals(1, harness.sender.sent.size)

        assertEquals(
            "An attempt alone must never silence the alert",
            0,
            harness.announcer.silenceCount,
        )
    }

    @Test
    fun aFailedSend_neverSilencesTheAlert() {
        val harness = Harness()
        harness.sender.scriptedOutcomes[0] = SmsSendState.Failed(SmsFailureReason.SEND_FAILED)
        harness.noResponseTimeout()

        assertEquals(0, harness.announcer.silenceCount)
    }

    @Test
    fun aBlockedSend_doesNotSilenceTheAlert() {
        val harness = Harness(smsConsent = false)
        harness.noResponseTimeout()

        assertEquals(0, harness.announcer.silenceCount)
    }

    @Test
    fun aSimulatedIncident_neverSilencesAnything() {
        val harness = Harness()
        harness.simulatedIncident()

        assertEquals(0, harness.announcer.silenceCount)
    }

    // ------------------------------------------------------------------
    // Location handling
    // ------------------------------------------------------------------

    @Test
    fun noUsablePosition_stillSendsAndInventsNoCoordinates() {
        val harness = Harness()

        harness.noResponseTimeout()

        assertEquals(1, harness.sender.sent.size)
        assertFalse(
            "No coordinates may be fabricated",
            harness.sender.onlyBody().contains("maps.google.com"),
        )
    }

    @Test
    fun anImplausiblePosition_isNeverPutInAMessage() {
        val body = SmsAlertMessageBuilder.build(
            incidentType = "Possible accident",
            incidentAtMillis = System.currentTimeMillis(),
            location = com.resq.app.model.EmergencyLocationSnapshot(
                availability = com.resq.app.model.LocationAvailability.READY,
                freshness = com.resq.app.model.FixFreshness.FRESH,
                latitude = 0.0,
                longitude = 0.0,
            ),
        )
        assertFalse("The (0,0) sentinel must never reach a human", body.contains("maps.google.com"))
    }

    @Test
    fun aHistoricalPosition_isLabelledAsLastKnownAndMayBeOutOfDate() {
        val body = SmsAlertMessageBuilder.build(
            incidentType = "Possible accident",
            incidentAtMillis = System.currentTimeMillis(),
            location = com.resq.app.model.EmergencyLocationSnapshot(
                availability = com.resq.app.model.LocationAvailability.STALE,
                freshness = com.resq.app.model.FixFreshness.STALE,
                latitude = 12.9716,
                longitude = 77.5946,
                ageMillis = 5 * 60_000L,
            ),
        )
        assertTrue(body.contains("Last known location"))
        assertTrue(body.contains("not the current position"))
        assertFalse(body.contains("Location (current)"))
    }

    @Test
    fun aFreshPosition_isLabelledAsCurrent() {
        val body = SmsAlertMessageBuilder.build(
            incidentType = "Possible accident",
            incidentAtMillis = System.currentTimeMillis(),
            location = com.resq.app.model.EmergencyLocationSnapshot(
                availability = com.resq.app.model.LocationAvailability.READY,
                freshness = com.resq.app.model.FixFreshness.FRESH,
                latitude = 12.9716,
                longitude = 77.5946,
            ),
        )
        assertTrue(body.contains("Location (current)"))
        assertTrue(body.contains("maps.google.com"))
        assertFalse(body.contains("Last known"))
    }

    // ------------------------------------------------------------------
    // Stand-down
    // ------------------------------------------------------------------

    @Test
    fun standingDownBeforeEscalation_sendsNothing() {
        val harness = Harness()
        harness.hardwareIncident()

        harness.engine.submit(AccidentEvent.Reset)

        assertTrue(harness.sender.sent.isEmpty())
        assertTrue(harness.launcher.placed.isEmpty())
    }

    @Test
    fun aLateCallbackAfterStandDown_cannotCallOrSilence() {
        val harness = Harness()
        harness.noResponseTimeout()
        val sender = harness.sender

        harness.engine.submit(AccidentEvent.Reset)
        val silencesBefore = harness.announcer.silenceCount
        sender.reportSentToCarrier(0)

        assertEquals(
            "A late carrier verdict must not ring the contact after stand-down",
            0,
            harness.launcher.placed.size,
        )
        assertEquals(silencesBefore, harness.announcer.silenceCount)
    }

    // ------------------------------------------------------------------
    // Truthfulness
    // ------------------------------------------------------------------

    @Test
    fun theIncidentSummaryNeverClaimsDelivery() {
        val harness = Harness()
        harness.noResponseTimeout()
        harness.sender.reportSentToCarrier(0)
        harness.sender.reportDelivered(0)

        // Even after a delivery receipt the incident-level claim stays at the
        // strongest thing that is always true.
        assertEquals(SmsEscalation.SentToCarrier(1), harness.coordinator.smsOutcome)
    }

    @Test
    fun noNumberOrLocationIsEverLogged() {
        // Structural rather than behavioural: the coordinator's source must not
        // contain a logging call at all, which is what guarantees a number, a
        // coordinate or the message body cannot leak into Logcat.
        val source = java.io.File(
            System.getProperty("user.dir") ?: "",
            "src/main/java/com/resq/app/escalation/EmergencyEscalationCoordinator.kt",
        )
        assertTrue("The coordinator source must exist", source.isFile)
        val code = source.readLines()
            .map { it.substringBefore("//") }
            .joinToString("\n")
        listOf("Log.", "println", "android.util.Log")
            .forEach { forbidden ->
                assertFalse("The coordinator must not log ($forbidden)", code.contains(forbidden))
            }
    }
}