package com.resq.app

import com.resq.app.alert.AlertAnnouncer
import com.resq.app.alert.AlertCue
import com.resq.app.contacts.CallLaunchResult
import com.resq.app.contacts.CallLauncher
import com.resq.app.contacts.EmergencyContactsStore
import com.resq.app.data.sensors.SensorGateway
import com.resq.app.data.sensors.SensorObserver
import com.resq.app.model.AccelerometerState
import com.resq.app.model.AppScreen
import com.resq.app.model.EmergencyContact
import com.resq.app.model.EmergencyState
import com.resq.app.model.GyroscopeState
import com.resq.app.model.IncidentState
import com.resq.app.model.PrimaryContactCall
import com.resq.app.model.ResourceDiscoveryState
import com.resq.app.model.SensorAvailability
import com.resq.app.protection.ProtectionEngine
import com.resq.app.protection.ProtectionSource
import com.resq.app.viewmodel.ResQViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The end-to-end incident workflow, from a hardware impact to the emergency
 * screen, and the two external effects it is allowed to produce.
 *
 * Two things are asserted throughout:
 *
 *  1. the safety rules - one countdown per incident, one escalation per incident,
 *     one call per incident, and no call at all from a test flow;
 *  2. the honesty rules - ResQ reports what it did, never that a call connected.
 *
 * No test can reach a real speaker or a real dialer: both come in through
 * [AlertAnnouncer] and [CallLauncher] and are faked here.
 */
class ResQViewModelEmergencyWorkflowTest {

    private val primary = EmergencyContact(
        id = "a",
        name = "Priya Nair",
        phoneNumber = "9876543210",
        isPrimary = true,
    )
    private val secondary = EmergencyContact(id = "b", name = "Daniel Ortiz", phoneNumber = "9876543299")

    // ------------------------------------------------------------------
    // Stage A/B - a hardware impact opens the alert and speaks once
    // ------------------------------------------------------------------

    @Test
    fun aHardwareImpact_opensTheCountdownAndSpeaksTheFirstWarning() {
        val fixture = Fixture()
        fixture.triggerHardwareIncident()

        assertEquals(AppScreen.INCIDENT, fixture.viewModel.state.screen)
        val incident = fixture.viewModel.state.incident
        assertTrue(incident is IncidentState.PossibleImpact)
        assertEquals(10, (incident as IncidentState.PossibleImpact).secondsRemaining)
        assertEquals(listOf(AlertCue.POSSIBLE_ACCIDENT), fixture.announcer.cues)
    }

    @Test
    fun anIncidentFromHardware_doesNotNeedDeveloperOrTestMode() {
        val fixture = Fixture()

        // Nothing but "protection is on" and a real sample stream.
        fixture.triggerHardwareIncident()

        assertTrue(fixture.viewModel.state.incident is IncidentState.PossibleImpact)
    }

    @Test
    fun repeatedImpactSamples_neverRestartTheCountdownOrRepeatTheWarning() {
        val fixture = Fixture()
        fixture.triggerHardwareIncident()
        val first = fixture.viewModel.state.incident as IncidentState.PossibleImpact

        fixture.gateway.emitImpact()
        fixture.gateway.emitImpact()
        fixture.gateway.emitImpact()

        val after = fixture.viewModel.state.incident as IncidentState.PossibleImpact
        assertEquals(first.secondsRemaining, after.secondsRemaining)
        assertEquals(listOf(AlertCue.POSSIBLE_ACCIDENT), fixture.announcer.cues)
    }

    // ------------------------------------------------------------------
    // Stage B - "I'm Okay"
    // ------------------------------------------------------------------

    @Test
    fun userOk_cancelsTheCountdownAndCallsNobody() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.triggerHardwareIncident()

        fixture.viewModel.confirmUserOk()

        assertEquals(IncidentState.None, fixture.viewModel.state.incident)
        assertEquals(AppScreen.HOME, fixture.viewModel.state.screen)
        assertEquals(EmergencyState.Inactive, fixture.viewModel.state.emergency)
        assertTrue(fixture.launcher.dialed.isEmpty())
        assertEquals(PrimaryContactCall.NotAttempted, fixture.viewModel.state.primaryContactCall)
        assertTrue(fixture.announcer.silenceCount > 0)
    }

    @Test
    fun aCancelledIncident_canNeverEscalateLater() {
        // 1 ms ticks: without the cancellation this incident would have reached the
        // emergency screen and dialed within a few milliseconds.
        val fixture = Fixture(contacts = listOf(primary), consent = true, countdownTickMillis = 1L)
        fixture.triggerHardwareIncident()

        fixture.viewModel.confirmUserOk()
        Thread.sleep(200)

        assertEquals(IncidentState.None, fixture.viewModel.state.incident)
        assertEquals(AppScreen.HOME, fixture.viewModel.state.screen)
        assertEquals(EmergencyState.Inactive, fixture.viewModel.state.emergency)
        assertTrue(fixture.launcher.dialed.isEmpty())
    }

    // ------------------------------------------------------------------
    // Stage B/C - "I Need Help" escalates at once
    // ------------------------------------------------------------------

    @Test
    fun needHelp_escalatesImmediatelyAndSkipsTheNoResponseWarning() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.triggerHardwareIncident()

        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        assertEquals(AppScreen.EMERGENCY, fixture.viewModel.state.screen)
        assertTrue(fixture.viewModel.state.emergency != EmergencyState.Inactive)
        assertEquals(IncidentState.None, fixture.viewModel.state.incident)
        // The user answered, so the no-response warning is never played at them.
        assertFalse(fixture.announcer.cues.contains(AlertCue.NO_RESPONSE))
        assertEquals(listOf(primary.phoneNumber), fixture.launcher.dialed)
    }

    @Test
    fun needHelp_reachesTheEmergencyScreenEvenWhenTheCallLauncherIsMissing() {
        val fixture = Fixture(contacts = listOf(primary), consent = true, withLauncher = false)
        fixture.triggerHardwareIncident()

        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        assertEquals(AppScreen.EMERGENCY, fixture.viewModel.state.screen)
        assertEquals(PrimaryContactCall.CouldNotStart, fixture.viewModel.state.primaryContactCall)
    }

    // ------------------------------------------------------------------
    // Stage C - countdown expiry, second warning, escalation
    // ------------------------------------------------------------------

    @Test
    fun countdownExpiry_opensTheNoResponseStateAndSpeaksTheSecondWarning() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.triggerHardwareIncident()

        fixture.expireConfirmationCountdown()

        assertEquals(IncidentState.Confirmation, fixture.viewModel.state.incident)
        assertEquals(
            listOf(AlertCue.POSSIBLE_ACCIDENT, AlertCue.NO_RESPONSE),
            fixture.announcer.cues,
        )
        // The unconfirmed state is not the emergency yet, and nobody was called.
        assertEquals(EmergencyState.Inactive, fixture.viewModel.state.emergency)
        assertTrue(fixture.launcher.dialed.isEmpty())
    }

    @Test
    fun theNoResponseState_armsABoundedWindow() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.triggerHardwareIncident()

        fixture.expireConfirmationCountdown()

        val remaining = fixture.viewModel.state.confirmationSecondsRemaining
        assertTrue("The unconfirmed window must be armed", remaining != null && remaining > 0)
    }

    @Test
    fun noResponse_reachesTheEmergencyScreenAndCallsOnce() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.triggerHardwareIncident()
        fixture.expireConfirmationCountdown()

        fixture.escalateFromRealNoResponse()
        fixture.reportCarrierHandoff()

        assertEquals(AppScreen.EMERGENCY, fixture.viewModel.state.screen)
        assertEquals(listOf(primary.phoneNumber), fixture.launcher.dialed)
        assertNull(fixture.viewModel.state.confirmationSecondsRemaining)
    }

    @Test
    fun anUnattendedHardwareIncident_escalatesAndCallsOnce_withNoUserInteraction() {
        val fixture = Fixture(
            contacts = listOf(primary),
            consent = true,
            countdownTickMillis = 1L,
        )

        fixture.triggerHardwareIncident()

        assertTrue(
            "The whole flow must reach the emergency screen on its own",
            fixture.await { fixture.viewModel.state.screen == AppScreen.EMERGENCY },
        )
        // The screen, the alert text and the call attempt are applied by
        // DIFFERENT steps now: the emergency opens first, then the platform
        // reports the carrier accepted the alert text, and only then is the
        // contact called. Each is awaited rather than assumed.
        assertTrue(
            "The alert text must be handed over once the emergency starts",
            fixture.await { fixture.sms.sent.isNotEmpty() },
        )
        fixture.reportCarrierHandoff()
        assertTrue(
            "The automatic call attempt must run after the carrier handoff",
            fixture.await {
                fixture.viewModel.state.primaryContactCall != PrimaryContactCall.NotAttempted
            },
        )
        assertEquals(
            listOf(AlertCue.POSSIBLE_ACCIDENT, AlertCue.NO_RESPONSE),
            fixture.announcer.cues,
        )
        assertEquals(
            PrimaryContactCall.DialerOpened(primary.name),
            fixture.viewModel.state.primaryContactCall,
        )
        assertEquals(listOf(primary.phoneNumber), fixture.launcher.dialed)
    }

    // ------------------------------------------------------------------
    // Stage D - idempotency and truthfulness of the call attempt
    // ------------------------------------------------------------------

    @Test
    fun oneIncident_producesExactlyOneCallAttempt() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.triggerHardwareIncident()
        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        // Repeated escalation requests, repeated detector noise and the repeated
        // no-response control must all leave the single attempt alone.
        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()
        fixture.gateway.emitImpact()
        fixture.viewModel.simulateNoResponse()
        fixture.reportCarrierHandoff()

        assertEquals(listOf(primary.phoneNumber), fixture.launcher.dialed)
    }

    @Test
    fun onlyThePrimaryContact_isCalledAutomatically() {
        val fixture = Fixture(contacts = listOf(primary, secondary), consent = true)
        fixture.triggerHardwareIncident()

        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        assertEquals(listOf(primary.phoneNumber), fixture.launcher.dialed)
        assertFalse(fixture.launcher.dialed.contains(secondary.phoneNumber))
    }

    @Test
    fun theUnchosenFirstContact_isTheFallbackWhenNoPrimaryWasPicked() {
        val unchosen = primary.copy(isPrimary = false)
        val fixture = Fixture(contacts = listOf(unchosen), consent = true)
        fixture.triggerHardwareIncident()

        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        assertEquals(listOf(unchosen.phoneNumber), fixture.launcher.dialed)
    }

    @Test
    fun aLaunchedDialer_isReportedTruthfullyAndNeverAsAConnectedCall() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.triggerHardwareIncident()

        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        assertEquals(
            PrimaryContactCall.DialerOpened(primary.name),
            fixture.viewModel.state.primaryContactCall,
        )
    }

    @Test
    fun withoutConsent_noCallIsAttemptedAndTheUserIsTold() {
        val fixture = Fixture(contacts = listOf(primary), consent = false)
        fixture.triggerHardwareIncident()

        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        assertTrue(fixture.launcher.dialed.isEmpty())
        assertEquals(PrimaryContactCall.ConsentRequired, fixture.viewModel.state.primaryContactCall)
    }

    @Test
    fun withoutContacts_theOutcomeSaysSoInsteadOfInventingANumber() {
        val fixture = Fixture(contacts = emptyList(), consent = true)
        fixture.triggerHardwareIncident()

        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        assertTrue(fixture.launcher.dialed.isEmpty())
        assertEquals(
            PrimaryContactCall.NoContactConfigured,
            fixture.viewModel.state.primaryContactCall,
        )
    }

    @Test
    fun aRefusedDialer_isReportedAsFailureAndTheEmergencyScreenStillOpens() {
        val fixture = Fixture(
            contacts = listOf(primary),
            consent = true,
            launcherResult = CallLaunchResult.NoHandler,
        )
        fixture.triggerHardwareIncident()

        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        assertEquals(PrimaryContactCall.CouldNotStart, fixture.viewModel.state.primaryContactCall)
        assertEquals(AppScreen.EMERGENCY, fixture.viewModel.state.screen)
    }

    @Test
    fun testModeNeverDials() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.viewModel.openDiagnostics()
        fixture.viewModel.simulatePossibleCollision()

        assertEquals(AppScreen.INCIDENT, fixture.viewModel.state.screen)
        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        assertEquals(AppScreen.EMERGENCY, fixture.viewModel.state.screen)
        assertTrue("Developer/Test Mode must never place a call", fixture.launcher.dialed.isEmpty())
        assertEquals(PrimaryContactCall.NotAttempted, fixture.viewModel.state.primaryContactCall)
    }

    @Test
    fun aTestModeIncident_neverDialsEvenWhenTheCountdownRunsOut() {
        val fixture = Fixture(
            contacts = listOf(primary),
            consent = true,
            countdownTickMillis = 1L,
        )
        fixture.viewModel.openDiagnostics()
        fixture.viewModel.simulatePossibleCollision()

        assertTrue(
            fixture.await { fixture.viewModel.state.screen == AppScreen.EMERGENCY },
        )
        // A negative assertion: give the same synchronous handler that set the
        // screen time to finish before concluding nothing was dialed.
        Thread.sleep(200)
        assertTrue(fixture.launcher.dialed.isEmpty())
        assertEquals(PrimaryContactCall.NotAttempted, fixture.viewModel.state.primaryContactCall)
    }

    // ------------------------------------------------------------------
    // Stage E/G - standing down
    // ------------------------------------------------------------------

    @Test
    fun standingDown_returnsHomeAndSilencesTheAlert() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.triggerHardwareIncident()
        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        fixture.viewModel.standDown()

        assertEquals(AppScreen.HOME, fixture.viewModel.state.screen)
        assertEquals(EmergencyState.Inactive, fixture.viewModel.state.emergency)
        assertEquals(IncidentState.None, fixture.viewModel.state.incident)
        assertEquals(PrimaryContactCall.NotAttempted, fixture.viewModel.state.primaryContactCall)
        assertTrue(fixture.announcer.silenceCount > 0)
    }

    // ------------------------------------------------------------------
    // Contact editing through the ViewModel
    // ------------------------------------------------------------------

    @Test
    fun addingAValidContact_storesItOnce() {
        val store = FakeContactsStore()
        val viewModel = ResQViewModel(contactsStore = store)

        val saved = viewModel.saveContact(null, "  Priya Nair  ", " +91 98765 43210 ", true)

        assertTrue(saved != null)
        assertEquals(1, viewModel.state.contacts.size)
        assertEquals("Priya Nair", viewModel.state.contacts.single().name)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun invalidOrDuplicateContactInput_isRefusedAndNothingIsStored() {
        val store = FakeContactsStore()
        val viewModel = ResQViewModel(contactsStore = store)
        viewModel.saveContact(null, "Priya", "9876543210", true)

        assertNull(viewModel.saveContact(null, "", "9876543210", false))
        assertNull(viewModel.saveContact(null, "Bad", "123", false))
        assertNull(viewModel.saveContact(null, "Bad", "call mum", false))
        assertNull(viewModel.saveContact(null, "Duplicate", "(98765) 43210", false))

        assertEquals(1, viewModel.state.contacts.size)
    }

    @Test
    fun moreThanThreeContacts_areRefused() {
        val viewModel = ResQViewModel(contactsStore = FakeContactsStore())
        viewModel.saveContact(null, "One", "9876543210", true)
        viewModel.saveContact(null, "Two", "9876543211", false)
        viewModel.saveContact(null, "Three", "9876543212", false)

        val fourth = viewModel.saveContact(null, "Four", "9876543213", false)

        assertNull(fourth)
        assertEquals(3, viewModel.state.contacts.size)
    }

    @Test
    fun anExistingContactIsUpdatedRatherThanDuplicated() {
        val viewModel = ResQViewModel(contactsStore = FakeContactsStore())
        val saved = viewModel.saveContact(null, "Priya", "9876543210", true)
        requireNotNull(saved)

        val updated = viewModel.saveContact(saved.id, "Priya Nair", "9876543219", true)

        assertTrue(updated != null)
        assertEquals(1, viewModel.state.contacts.size)
        assertEquals("Priya Nair", viewModel.state.contacts.single().name)
    }

    @Test
    fun makingAContactPrimary_keepsExactlyOnePrimary() {
        val viewModel = ResQViewModel(contactsStore = FakeContactsStore())
        val first = requireNotNull(viewModel.saveContact(null, "One", "9876543210", true))
        val second = requireNotNull(viewModel.saveContact(null, "Two", "9876543211", false))

        viewModel.makeContactPrimary(second.id)

        assertEquals(1, viewModel.state.contacts.count { it.isPrimary })
        assertEquals(second.id, viewModel.state.contacts.single { it.isPrimary }.id)
        assertFalse(viewModel.state.contacts.first { it.id == first.id }.isPrimary)
    }

    @Test
    fun removingAContact_persistsTheRemoval() {
        val store = FakeContactsStore()
        val viewModel = ResQViewModel(contactsStore = store)
        val saved = requireNotNull(viewModel.saveContact(null, "One", "9876543210", true))

        viewModel.removeContact(saved.id)

        assertTrue(viewModel.state.contacts.isEmpty())
        assertEquals(2, store.saveCount)
    }

    @Test
    fun consentIsPersistedSoItIsAskedForOnce() {
        val store = FakeContactsStore()
        val viewModel = ResQViewModel(contactsStore = store)

        viewModel.setEscalationConsent(true)

        assertTrue(viewModel.state.escalationConsent)
        assertTrue(store.isEscalationConsentGranted())
    }

    @Test
    fun aResetClearsTheIncidentButNeverTheContacts() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.viewModel.openDiagnostics()

        fixture.viewModel.resetSimulation()

        assertEquals(listOf(primary), fixture.viewModel.state.contacts)
        assertTrue(fixture.viewModel.state.escalationConsent)
        assertEquals(IncidentState.None, fixture.viewModel.state.incident)
    }

    @Test
    fun userInitiatedCallsAreAllowedWithoutConsentAndReportedTruthfully() {
        val fixture = Fixture(contacts = listOf(primary, secondary), consent = false)

        fixture.viewModel.callContact(secondary)

        assertEquals(listOf(secondary.phoneNumber), fixture.launcher.dialed)
        assertEquals(
            PrimaryContactCall.DialerOpened(secondary.name),
            fixture.viewModel.state.primaryContactCall,
        )
    }

    // ------------------------------------------------------------------
    // Recreation, stand-down determinism and audio failure
    // ------------------------------------------------------------------

    @Test
    fun aRecreatedViewModel_neverPlacesASecondCallForTheSameIncident() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.triggerHardwareIncident()
        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()
        assertEquals(listOf(primary.phoneNumber), fixture.launcher.dialed)

        // The Activity is destroyed and recreated while the process - and with
        // it the engine, the incident and the running emergency - survive. The
        // brand-new ViewModel re-enters the emergency through its listener
        // during construction, but the engine-wide call ledger must keep it
        // from dialing the same incident a second time.
        val recreated = ResQViewModel(
            engine = fixture.engine,
            alertAnnouncer = fixture.announcer,
            contactsStore = FakeContactsStore(listOf(primary), consent = true),
            callLauncher = fixture.launcher,
            countdownTickMillis = 60_000L,
        )

        assertEquals(AppScreen.EMERGENCY, recreated.state.screen)
        // Give any in-flight timer a chance to fire before concluding nothing
        // extra was dialed.
        Thread.sleep(300)
        assertEquals(listOf(primary.phoneNumber), fixture.launcher.dialed)
    }

    @Test
    fun onceAnIncidentEnds_theNextIncidentEarnsAFreshCallAttempt() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.triggerHardwareIncident()
        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()
        fixture.viewModel.standDown()
        assertEquals(1, fixture.launcher.dialed.size)

        // A brand-new incident after the stand-down is a new call budget.
        fixture.gateway.emitImpact()
        assertTrue(fixture.viewModel.state.incident is IncidentState.PossibleImpact)
        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        assertEquals(2, fixture.launcher.dialed.size)
        assertEquals(AppScreen.EMERGENCY, fixture.viewModel.state.screen)
    }

    @Test
    fun aStaleStageAdvance_afterStandDown_neverResurrectsTheEmergencyScreen() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.triggerHardwareIncident()
        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        fixture.viewModel.standDown()
        assertEquals(AppScreen.HOME, fixture.viewModel.state.screen)

        // A stage timer that was already in flight when the user stood down,
        // plus a telemetry pulse racing it. Neither may put the emergency
        // screen back or dial again.
        fixture.viewModel.advanceEmergency()
        fixture.viewModel.pulseTelemetry()

        assertEquals(AppScreen.HOME, fixture.viewModel.state.screen)
        assertEquals(EmergencyState.Inactive, fixture.viewModel.state.emergency)
        assertEquals(IncidentState.None, fixture.viewModel.state.incident)
        assertEquals(1, fixture.launcher.dialed.size)
    }

    @Test
    fun standingDownRepeatedly_isIdempotent() {
        val fixture = Fixture(contacts = listOf(primary), consent = true)
        fixture.triggerHardwareIncident()
        fixture.viewModel.requestEmergencyHelp()
        fixture.reportCarrierHandoff()

        repeat(3) { fixture.viewModel.standDown() }

        assertEquals(AppScreen.HOME, fixture.viewModel.state.screen)
        assertEquals(EmergencyState.Inactive, fixture.viewModel.state.emergency)
        assertEquals(PrimaryContactCall.NotAttempted, fixture.viewModel.state.primaryContactCall)
        assertEquals(1, fixture.launcher.dialed.size)
    }

    @Test
    fun concurrentNavigationAndStateWriters_neverLoseTheLastNavigation() {
        // The back-path used to race background writers of the SAME state
        // object: a read-modify-write that started before the navigation could
        // land after it and silently put the old screen back. Both threads here
        // hammer the state from different threads; the invariant is that the
        // navigator's final close is what remains.
        val viewModel = ResQViewModel()
        val iterations = 20_000
        val navigator = Thread {
            repeat(iterations) {
                viewModel.openSettings()
                viewModel.closeSettings()
            }
        }
        val writer = Thread {
            repeat(iterations) {
                viewModel.setEscalationConsent(true)
                viewModel.setEscalationConsent(false)
            }
        }
        navigator.start()
        writer.start()
        navigator.join(30_000)
        writer.join(30_000)

        assertEquals(AppScreen.HOME, viewModel.state.screen)
    }

    @Test
    fun audioFailure_neverBlocksTheEmergencyScreen() {
        val announcer = ThrowingAnnouncer()
        val gateway = ImpactGateway()
        val engine = ProtectionEngine(gateway)
        val launcher = FakeCallLauncher()
        val sms = WorkflowSmsSender()
        val store = FakeContactsStore(listOf(primary), consent = true)
        val coordinator = com.resq.app.escalation.EmergencyEscalationCoordinator(
            engine = engine,
            smsSender = sms,
            callLauncher = launcher,
            contactsStore = store,
            alertAnnouncer = announcer,
        ).also { it.start() }
        val viewModel = ResQViewModel(
            engine = engine,
            alertAnnouncer = announcer,
            contactsStore = store,
            callLauncher = launcher,
            countdownTickMillis = 60_000L,
            escalationCoordinator = coordinator,
        )
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact()

        // The first spoken warning threw - the incident itself must continue.
        assertTrue(viewModel.state.incident is IncidentState.PossibleImpact)
        assertTrue(announcer.attempts > 0)

        viewModel.requestEmergencyHelp()
        // The announcer throwing must not have stopped the escalation either.
        sms.reportSentToCarrier()

        assertEquals(AppScreen.EMERGENCY, viewModel.state.screen)
        assertEquals(listOf(primary.phoneNumber), launcher.dialed)
    }

    @Test
    fun aViewModelRecreatedMidEmergency_resumesTheResourceSearch() {
        // The emergency already reached the search stage with no ViewModel
        // alive (Activity destroyed). The recreated ViewModel must not leave
        // the resource section on a spinner whose request never begins.
        val engine = ProtectionEngine(sensorGateway = null)
        engine.submit(com.resq.app.detection.AccidentEvent.NeedHelp)
        repeat(3) {
            engine.submit(com.resq.app.detection.AccidentEvent.EmergencyAdvanced)
        }

        // No location and no repository are wired, so the resumed search
        // resolves immediately (no usable location / retryable failure) - what
        // matters is that it ran at all rather than leaving the resource
        // section on a spinner whose request never begins.
        val recreated = ResQViewModel(engine = engine, countdownTickMillis = 60_000L)

        assertEquals(AppScreen.EMERGENCY, recreated.state.screen)
        assertTrue(
            "Discovery must leave Idle when the flow re-enters past the search stage",
            recreated.state.resourceDiscovery != ResourceDiscoveryState.Idle,
        )
    }
}

/** An announcer whose every operation throws, as a broken TTS stack would. */
private class ThrowingAnnouncer : AlertAnnouncer {

    var attempts = 0

    override fun announce(cue: AlertCue) {
        attempts++
        throw IllegalStateException("TTS unavailable")
    }

    override fun silence() {
        throw IllegalStateException("TTS unavailable")
    }

    override fun release() = Unit
}

/**
 * One assembled app: a real [ProtectionEngine] over a fake gateway, plus fake
 * announcer, contact store and call launcher.
 *
 * The tick interval defaults to a minute so nothing advances on its own - a test
 * that wants the timers must ask for them with `countdownTickMillis`.
 */
private class Fixture(
    contacts: List<EmergencyContact> = emptyList(),
    consent: Boolean = false,
    /**
     * SMS consent. TRUE by default because the escalation these tests pin down
     * is "the alert text reaches the carrier, and only then is the contact
     * called" - so a test about the call has to let the SMS leg succeed.
     */
    smsConsent: Boolean = true,
    launcherResult: CallLaunchResult = CallLaunchResult.Opened,
    withLauncher: Boolean = true,
    countdownTickMillis: Long = 60_000L,
) {
    val gateway = ImpactGateway()
    val engine = ProtectionEngine(gateway)
    val announcer = FakeAlertAnnouncer()
    val launcher = FakeCallLauncher(launcherResult)
    val sms = WorkflowSmsSender()
    private val store = FakeContactsStore(contacts, consent, smsConsent)

    // The escalation is dispatched by the coordinator, exactly as in the app.
    val coordinator = com.resq.app.escalation.EmergencyEscalationCoordinator(
        engine = engine,
        smsSender = sms,
        // The SAME launcher the ViewModel gets, so a "no dialer wired at all"
        // test really does exercise the coordinator's missing-launcher path.
        callLauncher = if (withLauncher) launcher else null,
        contactsStore = store,
        alertAnnouncer = announcer,
    ).also { it.start() }

    val viewModel = ResQViewModel(
        engine = engine,
        alertAnnouncer = announcer,
        contactsStore = store,
        // A missing launcher models a build with no dialer wiring at all, which
        // must be reported as a failure - never as a success.
        callLauncher = if (withLauncher) launcher else null,
        countdownTickMillis = countdownTickMillis,
        escalationCoordinator = coordinator,
    )

    /** Continuous protection is on: the only source allowed to raise an incident. */
    fun triggerHardwareIncident() {
        engine.attachSource(ProtectionSource.SERVICE)
        gateway.emitImpact()
    }

    /**
     * The REAL escalation path: the "are you okay?" window closes with no answer
     * and then the unconfirmed-safety window does the same.
     *
     * Deliberately not `viewModel.simulateNoResponse()`: that is the Developer/Test
     * control, and it now (correctly) marks its incident as simulated, which by
     * design suppresses every automatic side effect. A test about a real escalation
     * has to escalate the way the foreground service does.
     */
    fun escalateFromRealNoResponse() {
        engine.submit(com.resq.app.detection.AccidentEvent.NoResponse)
        engine.submit(com.resq.app.detection.AccidentEvent.NoResponse)
    }

    /**
     * Completes the SMS leg: the platform reports the carrier accepted the alert
     * text, which is what authorises the follow-up call. Never sends anything.
     */
    fun reportCarrierHandoff(index: Int = 0) {
        if (sms.sent.isNotEmpty()) sms.reportSentToCarrier(index)
    }

    /** The "are you okay?" window running out with no answer. */
    fun expireConfirmationCountdown() {
        repeat(10) { viewModel.onCountdownTick() }
    }

    /** Waits for a state to arrive on the ViewModel's own coroutine. */
    fun await(timeoutMillis: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }
}

/** Records every cue and cancellation, and never plays anything. */
private class FakeAlertAnnouncer : AlertAnnouncer {

    val cues = mutableListOf<AlertCue>()
    var silenceCount = 0
    var released = false

    override fun announce(cue: AlertCue) {
        cues += cue
    }

    override fun silence() {
        silenceCount++
    }

    override fun release() {
        released = true
    }
}

/**
 * Records dialer launches. This is the only place a call can come from in a test,
 * and it never touches Android - so no automated test can dial a real number.
 *
 * Reports the CALL_PHONE grant as NOT held by default, so these tests keep
 * exercising the dialer-escalation path they were written against. The direct
 * call path is covered explicitly in [EmergencyEscalationRegressionTest].
 */
private class FakeCallLauncher(
    private val result: CallLaunchResult = CallLaunchResult.Opened,
) : CallLauncher {

    val dialed = mutableListOf<String>()
    val placed = mutableListOf<String>()

    /** Whether the fake reports the CALL_PHONE grant as held. */
    var canPlaceDirectly: Boolean = false

    override fun canPlaceCallsDirectly(): Boolean = canPlaceDirectly

    override fun placeCall(number: String): CallLaunchResult {
        placed += number
        return result
    }

    override fun openDialer(number: String): CallLaunchResult {
        dialed += number
        return result
    }
}

/**
 * Records what would have been sent, and can report the carrier handoff.
 *
 * The contact is called only AFTER the platform reports the carrier accepted the
 * alert text, so every test that asserts on the call has to complete this leg
 * first. Nothing here reaches a radio: no test can send a real message.
 */
private class WorkflowSmsSender : com.resq.app.sms.SmsSender {
    val sent = mutableListOf<com.resq.app.sms.SmsMessage>()
    private val references = mutableListOf<String>()
    private val listeners =
        mutableListOf<Pair<String, com.resq.app.sms.SmsSendListener>>()

    override fun capability(): com.resq.app.sms.SmsCapability =
        com.resq.app.sms.SmsCapability.READY

    override fun send(
        message: com.resq.app.sms.SmsMessage,
        reference: String,
        listener: com.resq.app.sms.SmsSendListener,
    ): com.resq.app.sms.SmsSendState {
        sent += message
        references += reference
        listeners += reference to listener
        return com.resq.app.sms.SmsSendState.Attempted
    }

    fun reportSentToCarrier(index: Int = 0) {
        val reference = references[index]
        listeners.first { it.first == reference }.second.onSmsUpdate(
            com.resq.app.sms.SmsSendUpdate(
                reference,
                index,
                com.resq.app.sms.SmsSendState.SentToCarrier(reference),
            ),
        )
    }
}

/** In-memory contact storage, so no test touches SharedPreferences. */
private class FakeContactsStore(
    private var contacts: List<EmergencyContact> = emptyList(),
    private var consent: Boolean = false,
    private var smsConsent: Boolean = true,
) : EmergencyContactsStore {

    var saveCount = 0

    override fun contacts(): List<EmergencyContact> = contacts

    override fun saveContacts(contacts: List<EmergencyContact>) {
        this.contacts = contacts
        saveCount++
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
 * A gateway that replays a synthetic impact through the real detector, so the
 * incident under test travels the same path real hardware samples do.
 */
private class ImpactGateway : SensorGateway {

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
