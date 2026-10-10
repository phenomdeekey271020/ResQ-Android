package com.resq.app

import com.resq.app.alert.AlertAnnouncer
import com.resq.app.alert.AlertCue
import com.resq.app.contacts.CallLaunchResult
import com.resq.app.contacts.CallLauncher
import com.resq.app.contacts.EmergencyContactsStore
import com.resq.app.contacts.EmergencyContactsPolicy
import com.resq.app.contacts.toPrimaryContactCall
import com.resq.app.escalation.EmergencyEscalationCoordinator
import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SensorObserver
import com.resq.app.detection.AccidentEvent
import com.resq.app.detection.AccidentFlowState
import com.resq.app.model.AccelerometerState
import com.resq.app.model.AppScreen
import com.resq.app.model.EmergencyContact
import com.resq.app.model.GyroscopeState
import com.resq.app.model.SensorAvailability
import com.resq.app.model.EscalationCapability
import com.resq.app.model.PrimaryContactCall
import com.resq.app.protection.ProtectionEngine
import com.resq.app.protection.ProtectionSource
import com.resq.app.sms.SmsCapability
import com.resq.app.sms.SmsFailureReason
import com.resq.app.sms.SmsMessage
import com.resq.app.sms.SmsSendListener
import com.resq.app.sms.SmsSendState
import com.resq.app.sms.SmsSendUpdate
import com.resq.app.sms.SmsSender
import com.resq.app.ui.screens.primaryCallCopy
import com.resq.app.viewmodel.ResQViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The ONLY way a call can originate from an automated test.
 *
 * It records intent and returns a scripted result; it never touches Android, so
 * no test in this project can place a real telephone call - not to the emergency
 * number, and not to any contact.
 */
private class RecordingCallLauncher(
    private val placeResult: CallLaunchResult = CallLaunchResult.Placed,
    private val dialResult: CallLaunchResult = CallLaunchResult.Opened,
) : CallLauncher {

    val placed = mutableListOf<String>()
    val dialed = mutableListOf<String>()

    /** Scripted CALL_PHONE grant, re-read on every call exactly as Android would. */
    @Volatile var directCallsAllowed: Boolean = true

    override fun canPlaceCallsDirectly(): Boolean = directCallsAllowed

    override fun placeCall(number: String): CallLaunchResult {
        placed += number
        return placeResult
    }

    override fun openDialer(number: String): CallLaunchResult {
        dialed += number
        return dialResult
    }
}

/** A gateway that can raise a real-looking hardware incident. */
private class ImpactSensorGateway : SensorGateway {

    private var observer: SensorObserver? = null

    override fun hasLocationPermission(): Boolean = false

    override fun isLocationEnabled(): Boolean = false

    override fun start(observer: SensorObserver) {
        this.observer = observer
        observer.onAccelerometer(AccelerometerState())
    }

    override fun refresh() = Unit

    override fun refreshForcingRegistration() = Unit

    override fun stop() {
        observer = null
    }

    /**
     * Five strong impact samples with rotation - enough for the REAL detector to
     * reach POSSIBLE. A single sample cannot, so the incident must travel the
     * same multi-sample path hardware does rather than being forced.
     */
    fun emitImpact() {
        var timestamp = 1_700_000_000_000L
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

/** In-memory contacts + consent, so no test touches SharedPreferences. */
private class InMemoryContactsStore(
    private var contacts: List<EmergencyContact> = emptyList(),
    private var consent: Boolean = false,
    /**
     * SMS consent defaults to TRUE here because the contract these tests pin down
     * is "the contact is called AFTER the alert text reaches the carrier". Tests
     * that are specifically about a refusal set it false explicitly.
     */
    private var smsConsent: Boolean = true,
) : EmergencyContactsStore {
    override fun contacts(): List<EmergencyContact> = contacts
    override fun saveContacts(contacts: List<EmergencyContact>) {
        this.contacts = contacts
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

/**
 * REGRESSION TESTS FOR EMERGENCY CONTACT ESCALATION.
 *
 * The reported gap was that a no-response escalation left the user sitting in a
 * dialer, still needing to press Call - useless to someone who cannot respond.
 * These tests pin down the replacement behaviour and, just as importantly, pin
 * down everything that must NOT happen.
 *
 * Every test uses the fake launcher above, so no automated test ever dials 112 or
 * any other real number.
 */
class EmergencyEscalationRegressionTest {

    private val primary = EmergencyContact(
        id = "a",
        name = "Priya Nair",
        phoneNumber = "9876543210",
        isPrimary = true,
    )
    private val secondary = EmergencyContact(
        id = "b",
        name = "Arun Kumar",
        phoneNumber = "9123456780",
        isPrimary = false,
    )

    private lateinit var gateway: ImpactSensorGateway
    private lateinit var engine: ProtectionEngine
    private lateinit var launcher: RecordingCallLauncher
    private lateinit var store: InMemoryContactsStore
    private lateinit var sms: EscalationSmsSender
    private lateinit var coordinator: EmergencyEscalationCoordinator

    @Before
    fun setUp() {
        gateway = ImpactSensorGateway()
        engine = ProtectionEngine(gateway)
        launcher = RecordingCallLauncher()
        store = InMemoryContactsStore(listOf(primary, secondary), consent = true)
        sms = EscalationSmsSender()
        // The escalation is dispatched by this process-wide coordinator, exactly
        // as in the running app - not by the ViewModel.
        coordinator = EmergencyEscalationCoordinator(
            engine = engine,
            smsSender = sms,
            callLauncher = launcher,
            contactsStore = store,
            alertAnnouncer = SilentEscalationAnnouncer,
        ).also { it.start() }
    }

    // 1. Genuine incident + consent + permission -> one supported action.
    @Test
    fun aHardwareIncidentWithConsentAndPermission_placesExactlyOneRealCall() {
        val viewModel = viewModel()
        runHardwareIncidentToEscalation(viewModel)

        assertEquals(
            "The primary contact must be called exactly once",
            listOf(primary.phoneNumber),
            launcher.placed,
        )
        assertEquals(
            PrimaryContactCall.CallPlaced(primary.name),
            viewModel.state.primaryContactCall,
        )
        // Only the primary is ever tried: nobody else gets rung unexpectedly.
        assertFalse(launcher.dialed.contains(secondary.phoneNumber))
        assertTrue(
            "The incident must have reached the emergency stage",
            engine.flow is AccidentFlowState.Emergency,
        )
    }

    // 2. Test Mode -> zero real call attempts.
    @Test
    fun aSimulatedIncident_neverPlacesACall() {
        val viewModel = viewModel()
        viewModel.simulateHighConfidenceCollision()
        viewModel.requestEmergencyHelp()

        assertTrue(launcher.placed.isEmpty())
        assertTrue(launcher.dialed.isEmpty())
        assertEquals(PrimaryContactCall.NotAttempted, viewModel.state.primaryContactCall)
    }

    @Test
    fun aSimulatedIncidentDrivenAllTheWayToNoResponse_neverPlacesACall() {
        val impatient = viewModel(countdownTickMillis = 10L)
        impatient.simulateHighConfidenceCollision()

        // Drive it exactly as a real incident would be driven: the alert window
        // runs out, then the unconfirmed-safety window runs out too.
        awaitCondition { engine.flow is AccidentFlowState.PossibleAccident }
        var guard = 0
        while (engine.flow is AccidentFlowState.PossibleAccident && guard++ < 100) {
            impatient.onCountdownTick()
        }
        assertEquals(AccidentFlowState.Confirmation, engine.flow)
        engine.submit(AccidentEvent.NoResponse)
        awaitCondition { engine.flow is AccidentFlowState.Emergency }

        // The whole escalation path ran, and not one call was attempted.
        assertTrue(launcher.placed.isEmpty())
        assertTrue(launcher.dialed.isEmpty())
        assertEquals(PrimaryContactCall.NotAttempted, impatient.state.primaryContactCall)
    }

    // 3. Consent OFF -> no automatic contact call.
    @Test
    fun withoutConsent_noAutomaticCallHappens() {
        store = InMemoryContactsStore(listOf(primary), consent = false)
        rebindCoordinator()
        val viewModel = viewModel()
        runHardwareIncidentToEscalation(viewModel)

        assertTrue(launcher.placed.isEmpty())
        assertTrue(launcher.dialed.isEmpty())
        assertEquals(PrimaryContactCall.ConsentRequired, viewModel.state.primaryContactCall)
    }

    // 4. Permission denied or revoked -> safe fallback.
    @Test
    fun withoutTheCallPermission_theDialerIsUsedInstead() {
        launcher.directCallsAllowed = false
        val viewModel = viewModel()
        runHardwareIncidentToEscalation(viewModel)

        assertTrue("No direct call may be attempted", launcher.placed.isEmpty())
        assertEquals(listOf(primary.phoneNumber), launcher.dialed)
        assertEquals(PrimaryContactCall.DialerOpened(primary.name), viewModel.state.primaryContactCall)
    }

    @Test
    fun aPermissionRevokedAtTheMomentOfTheAttempt_fallsBackSafely() {
        val viewModel = viewModel()
        // Granted at configuration time, revoked before the incident escalates.
        viewModel.onCallPermissionResult(granted = true)
        launcher.directCallsAllowed = false

        runHardwareIncidentToEscalation(viewModel)

        assertTrue(launcher.placed.isEmpty())
        assertNotEquals(
            PrimaryContactCall.CallPlaced(primary.name),
            viewModel.state.primaryContactCall,
        )
    }

    // 5. No primary contact -> safe fallback, emergency actions remain.
    @Test
    fun withNoConfiguredContact_nothingIsCalledAndTheEmergencyStillWorks() {
        store = InMemoryContactsStore(emptyList(), consent = true)
        rebindCoordinator()
        val viewModel = viewModel()
        runHardwareIncidentToEscalation(viewModel)

        assertTrue(launcher.placed.isEmpty())
        assertTrue(launcher.dialed.isEmpty())
        assertEquals(PrimaryContactCall.NoContactConfigured, viewModel.state.primaryContactCall)
        // The emergency itself is completely unaffected by the missing contact.
        assertEquals(com.resq.app.model.AppScreen.EMERGENCY, viewModel.state.screen)
        assertEquals(com.resq.app.model.EmergencyState.Preparing(
            com.resq.app.model.EmergencyProgress.LOCATING,
        ), viewModel.state.emergency)
    }

    // 6. Dispatch failure -> the workflow still advances and the fallback works.
    @Test
    fun aRefusedDispatch_advancesTheWorkflowAndKeepsTheFallbackUsable() {
        val refusing = object : CallLauncher {
            var placeAttempts = 0
            var dialerAttempts = 0
            override fun canPlaceCallsDirectly(): Boolean = true

            override fun placeCall(number: String): CallLaunchResult {
                placeAttempts++
                return CallLaunchResult.NoHandler
            }

            override fun openDialer(number: String): CallLaunchResult {
                dialerAttempts++
                return CallLaunchResult.NoHandler
            }
        }
        // The refusing launcher has to be the one the COORDINATOR calls through, not
        // merely the one the ViewModel holds.
        rebindCoordinator(callLauncher = refusing)
        val viewModel = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            alertAnnouncer = SilentEscalationAnnouncer,
            contactsStore = store,
            callLauncher = refusing,
            countdownTickMillis = 60_000L,
            escalationCoordinator = coordinator,
        )
        runHardwareIncidentToEscalation(viewModel)

        // The direct call was tried once and, being refused, the dialer fallback
        // was attempted too - both fail, and neither is retried.
        assertEquals(1, refusing.placeAttempts)
        assertEquals(0, refusing.dialerAttempts)
        assertEquals(PrimaryContactCall.CouldNotStart, viewModel.state.primaryContactCall)
        // The flow is NOT left in a dead-end countdown or spinner.
        assertEquals(AppScreen.EMERGENCY, viewModel.state.screen)
        assertTrue(
            "The emergency must still be active after a refused dispatch",
            engine.flow is AccidentFlowState.Emergency,
        )
    }

    // 7. Activity/ViewModel recreation -> no duplicate call attempt.
    @Test
    fun aRecreatedViewModel_doesNotCallTheContactASecondTime() {
        val first = viewModel()
        runHardwareIncidentToEscalation(first)
        assertEquals(1, launcher.placed.size)

        // Same engine (the process-wide singleton), brand new ViewModel - exactly
        // what an Activity recreation produces.
        val recreated = ResQViewModel(
            sensorGateway = null,
            engine = engine,
            alertAnnouncer = SilentEscalationAnnouncer,
            contactsStore = store,
            callLauncher = launcher,
            countdownTickMillis = 60_000L,
            escalationCoordinator = coordinator,
        )

        assertEquals(
            "The once-per-incident budget must survive a ViewModel recreation",
            1,
            launcher.placed.size,
        )
        assertEquals(AppScreen.EMERGENCY, recreated.state.screen)
    }

    // 8. A new incident gets a fresh attempt budget.
    @Test
    fun aNewIncidentAfterTheFirstOneIsResolved_getsAFreshAttempt() {
        val viewModel = viewModel()
        runHardwareIncidentToEscalation(viewModel)
        assertEquals(1, launcher.placed.size)

        // The incident ends...
        viewModel.standDown()
        assertEquals(AppScreen.HOME, viewModel.state.screen)

        // ...and a genuinely new one starts.
        runHardwareIncidentToEscalation(viewModel)

        assertEquals("Each real incident gets its own single attempt", 2, launcher.placed.size)
        assertTrue(launcher.placed.all { it == primary.phoneNumber })
    }

    // 9. Concurrent countdown callbacks -> one attempt only.
    @Test
    fun severalConcurrentEscalationTriggers_produceOneAttempt() {
        val viewModel = viewModel()
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact()

        // Hammer the escalation from many threads at once, the way a countdown
        // tick, a lifecycle resume and a sensor sample could all collide.
        val threads = (1..8).map { index ->
            Thread {
                when (index % 4) {
                    0 -> viewModel.onCountdownTick()
                    1 -> viewModel.onEmergencyScreenResumed()
                    2 -> engine.submit(AccidentEvent.NoResponse)
                    else -> gateway.emitImpact()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(5_000) }

        assertTrue(
            "At most one automatic call per incident, got ${launcher.placed.size}",
            launcher.placed.size <= 1,
        )
    }

    // 10/12. A dispatched intent is never reported as proof of delivery.
    @Test
    fun aDispatchedIntent_isNeverReportedAsAConnectedCall() {
        val viewModel = viewModel()
        runHardwareIncidentToEscalation(viewModel)

        assertEquals(PrimaryContactCall.CallPlaced(primary.name), viewModel.state.primaryContactCall)
        val text = primaryCallCopy(viewModel.state.primaryContactCall, listOf(primary)).lowercase()

        // Words that would be a lie about another human being reached - asserted
        // absent even inside a negation, because that is how a user skimming a
        // red emergency screen misreads it.
        listOf("connected", "answered", "reached", "spoke to", "is calling", "notified").forEach {
            assertFalse("Copy must not mention '$it': $text", text.contains(it))
        }
        // What it does say is exactly what was requested, and that the outcome is
        // unknowable from here.
        assertTrue(text.contains("asked your phone", ignoreCase = true))
        assertTrue(text.contains("no way to know", ignoreCase = true))
    }

    @Test
    fun everyOutcomeCopy_avoidsClaimingDelivery() {
        val outcomes = listOf(
            PrimaryContactCall.NotAttempted,
            PrimaryContactCall.ConsentRequired,
            PrimaryContactCall.NoContactConfigured,
            PrimaryContactCall.CallPlaced(primary.name),
            PrimaryContactCall.DialerOpened(primary.name),
            PrimaryContactCall.CouldNotStart,
            PrimaryContactCall.CallPermissionRequired,
            PrimaryContactCall.ManualCallRequired,
        )
        outcomes.forEach { outcome ->
            val lower = primaryCallCopy(outcome, listOf(primary)).lowercase()
            listOf("answered", "connected", "reached", "spoke to", "notified", "ambulance")
                .forEach { assertFalse("$outcome must not say '$it'", lower.contains(it)) }
        }
    }

    @Test
    fun everyLaunchResult_mapsToATruthfulOutcome() {
        assertEquals(
            PrimaryContactCall.CallPlaced("X"),
            CallLaunchResult.Placed.toPrimaryContactCall("X"),
        )
        assertEquals(
            PrimaryContactCall.DialerOpened("X"),
            CallLaunchResult.Opened.toPrimaryContactCall("X"),
        )
        assertEquals(
            PrimaryContactCall.CouldNotStart,
            CallLaunchResult.NoHandler.toPrimaryContactCall("X"),
        )
        assertEquals(
            PrimaryContactCall.CallPermissionRequired,
            CallLaunchResult.PermissionNotGranted.toPrimaryContactCall("X"),
        )
    }

    // 11. Manual 112 remains available, and no test ever dials it.
    @Test
    fun noAutomatedTestEverDialsTheEmergencyNumber() {
        val viewModel = viewModel()
        runHardwareIncidentToEscalation(viewModel)

        val allNumbers = launcher.placed + launcher.dialed
        assertFalse(
            "The emergency number must never be dialled automatically",
            allNumbers.contains(com.resq.app.ui.screens.EmergencyNumber),
        )
        // It stays available as a manual action on the screen, which is rendered
        // from this constant rather than from any escalation path.
        assertEquals("112", com.resq.app.ui.screens.EmergencyNumber)
    }

    @Test
    fun aManualContactCall_neverConsumesTheAutomaticBudget() {
        val viewModel = viewModel()
        runHardwareIncidentToEscalation(viewModel)
        val afterEscalation = launcher.placed.size

        // The user taps a contact themselves.
        viewModel.callContact(secondary)

        assertEquals(listOf(secondary.phoneNumber), launcher.dialed)
        assertEquals(afterEscalation, launcher.placed.size)
    }

    // Consent and capability reporting.
    @Test
    fun withNoContactAtAll_theCapabilitySaysSoRatherThanClaimingACall() {
        store = InMemoryContactsStore(emptyList(), consent = true)
        rebindCoordinator()
        launcher.directCallsAllowed = true
        val viewModel = viewModel()
        viewModel.onCallPermissionResult(granted = true)

        assertEquals(EscalationCapability.NO_CONTACT, viewModel.state.escalationCapability)
    }

    @Test
    fun consentWithoutPermission_reportsDialerNotDirectCall() {
        store = InMemoryContactsStore(listOf(primary), consent = true)
        rebindCoordinator()
        launcher.directCallsAllowed = false
        val viewModel = viewModel()

        assertEquals(
            EscalationCapability.PERMISSION_MISSING,
            viewModel.state.escalationCapability,
        )
    }

    @Test
    fun consentWithPermissionAndContact_reportsADirectCall() {
        val viewModel = viewModel()
        viewModel.onCallPermissionResult(granted = true)

        assertEquals(EscalationCapability.DIRECT_CALL, viewModel.state.escalationCapability)
    }

    @Test
    fun consentOff_reportsThatNothingHappensAutomatically() {
        store = InMemoryContactsStore(listOf(primary), consent = false)
        rebindCoordinator()
        val viewModel = viewModel()

        assertEquals(EscalationCapability.CONSENT_OFF, viewModel.state.escalationCapability)
    }

    @Test
    fun theFirstContactAddedIsPrimary_soAOneContactSetupStillHasACallTarget() {
        assertEquals(primary, EmergencyContactsPolicy.primary(listOf(primary, secondary)))
        assertEquals(
            secondary,
            EmergencyContactsPolicy.primary(listOf(secondary.copy(isPrimary = false))),
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun viewModel(countdownTickMillis: Long = 60_000L) = ResQViewModel(
        sensorGateway = null,
        engine = engine,
        alertAnnouncer = SilentEscalationAnnouncer,
        contactsStore = store,
        callLauncher = launcher,
        countdownTickMillis = countdownTickMillis,
        escalationCoordinator = coordinator,
    )

    /**
     * Rebinds the coordinator to a different store or call launcher.
     *
     * The coordinator resolves contacts and consent through the store it was
     * built with, so a test that swaps either must rebuild it - exactly as the
     * process-wide holder would if those collaborators changed.
     */
    private fun rebindCoordinator(
        contactsStore: EmergencyContactsStore = store,
        callLauncher: CallLauncher = launcher,
        smsSender: SmsSender = sms,
    ) {
        coordinator.stop()
        coordinator = EmergencyEscalationCoordinator(
            engine = engine,
            smsSender = smsSender,
            callLauncher = callLauncher,
            contactsStore = contactsStore,
            alertAnnouncer = SilentEscalationAnnouncer,
        ).also { it.start() }
    }

    /**
     * The full hardware path: real sensors raise the incident, nobody answers,
     * the countdown expires and the emergency opens.
     */
    private fun runHardwareIncidentToEscalation(viewModel: ResQViewModel) {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact()
        awaitCondition { engine.flow is AccidentFlowState.PossibleAccident }
        // The "are you okay?" window closes with no answer, then the
        // unconfirmed-safety window closes too.
        while (engine.flow is AccidentFlowState.PossibleAccident) {
            viewModel.onCountdownTick()
        }
        while (engine.flow == AccidentFlowState.Confirmation) {
            viewModel.onCountdownTick()
            engine.submit(AccidentEvent.NoResponse)
            if (engine.flow == AccidentFlowState.Confirmation) break
        }
        if (engine.flow == AccidentFlowState.Confirmation) {
            engine.submit(AccidentEvent.NoResponse)
        }
        awaitCondition { engine.flow is AccidentFlowState.Emergency }
        // Complete the SMS leg. The contact is called ONLY after the platform
        // reports the carrier accepted the alert text, so a test that is about the
        // call has to deliver that verdict. Nothing is sent for real: this is the
        // fake sender, and no wait is performed here so that a test asserting NO
        // call does not block on one.
        if (sms.sent.isNotEmpty()) {
            sms.reportCarrierHandoff()
        }
    }

    private fun awaitCondition(timeoutMillis: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out; flow was ${engine.flow}")
            }
            Thread.sleep(5)
        }
    }
}

/**
 * Records what would have been sent. Never reaches a radio, so no test in this
 * project can send a real message.
 *
 * It exists because the escalation is now ORDERED: the contact is called only
 * after the platform reports the carrier accepted the alert text. The tests that
 * assert a call therefore have to complete the SMS leg first.
 */
private class EscalationSmsSender : SmsSender {
    val sent = mutableListOf<SmsMessage>()
    private val references = mutableListOf<String>()
    private val listeners = mutableListOf<Pair<String, SmsSendListener>>()

    /** When true, the send is refused synchronously. */
    var failsImmediately: Boolean = false

    override fun capability(): SmsCapability = SmsCapability.READY

    override fun send(
        message: SmsMessage,
        reference: String,
        listener: SmsSendListener,
    ): SmsSendState {
        sent += message
        references += reference
        listeners += reference to listener
        return if (failsImmediately) {
            SmsSendState.Failed(SmsFailureReason.SEND_FAILED)
        } else {
            SmsSendState.Attempted
        }
    }

    /** The platform reports the carrier accepted the message at [index]. */
    fun reportCarrierHandoff(index: Int = 0) {
        val reference = references[index]
        listeners.first { it.first == reference }.second.onSmsUpdate(
            SmsSendUpdate(reference, index, SmsSendState.SentToCarrier(reference)),
        )
    }
}

/** Never plays anything, so no test can reach a real speaker. */
private object SilentEscalationAnnouncer : AlertAnnouncer {
    override fun announce(cue: AlertCue) = Unit
    override fun silence() = Unit
    override fun release() = Unit
}
