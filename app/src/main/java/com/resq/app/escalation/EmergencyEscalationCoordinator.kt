package com.resq.app.escalation

import com.resq.app.alert.AlertAnnouncer
import com.resq.app.contacts.CallLaunchResult
import com.resq.app.contacts.CallLauncher
import com.resq.app.contacts.EmergencyContactsPolicy
import com.resq.app.contacts.EmergencyContactsStore
import com.resq.app.detection.AccidentFlowState
import com.resq.app.model.EmergencyContact
import com.resq.app.model.PrimaryContactCall
import com.resq.app.model.SmsEscalation
import com.resq.app.protection.NO_INCIDENT
import com.resq.app.protection.ProtectionEngine
import com.resq.app.protection.ProtectionEngineListener
import com.resq.app.protection.ProtectionSnapshot
import com.resq.app.sms.SmsAlertMessageBuilder
import com.resq.app.sms.SmsCapability
import com.resq.app.sms.SmsFailureReason
import com.resq.app.sms.SmsMessage
import com.resq.app.sms.SmsRecipientsPolicy
import com.resq.app.sms.SmsSendListener
import com.resq.app.sms.SmsSendState
import com.resq.app.sms.SmsSender

/*
 * THE ESCALATION COORDINATOR - WHERE THE SIDE EFFECTS LIVE NOW.
 *
 * WHY THIS CLASS EXISTS. The alert text and the primary-contact call used to be
 * fired from ResQViewModel.onFlowChanged. That made both of them depend on an
 * Activity being alive: ViewModel.onCleared() unregisters it as an engine
 * listener, so once Android destroyed the Activity the foreground service could
 * still walk the incident to EMERGENCY and NOTHING would be sent. For an
 * emergency app whose headline scenario is an unconscious user with the screen
 * off, that is the whole feature failing silently.
 *
 * This coordinator is a [ProtectionEngineListener] held by a process-wide object
 * (see [EscalationCoordinatorHolder]) and created by the FOREGROUND SERVICE, so it
 * exists whenever protection does. The UI observes its results; it does not drive
 * them.
 *
 * THE ORDERING, AND WHY IT IS EXACTLY THIS ORDER:
 *
 *   1. the alert text is attempted first, for every eligible contact;
 *   2. only when the CARRIER has accepted at least one message does ResQ go on to
 *      ring the primary contact.
 *
 * Step 2 waits for SENT_TO_CARRIER - the platform's own "the carrier accepted
 * this" report - and NOT for a delivery receipt. Delivery receipts are
 * operator-dependent and frequently never arrive, so waiting for one would mean
 * the call is sometimes never made. The precise condition that authorises the
 * call is therefore: the SmsSendState reached SENT_TO_CARRIER (or DELIVERED, which
 * implies it) for at least one recipient of the current incident. Anything less -
 * including ATTEMPTED, which only means SmsManager took the message - does NOT
 * authorise it, and neither does a failure.
 *
 * HONESTY. Nothing here ever reports delivery from a send request, a connected
 * call from a launched dialer, or a reached person from either. "Sent to carrier"
 * is the strongest claim about a message and "ResQ asked your phone to place a
 * call" is the strongest claim about a call, and the UI is worded to match.
 *
 * NO SECOND STATE MACHINE. This class owns no flow, no countdown and no incident
 * state of its own. It reacts to transitions the one existing AccidentStateMachine
 * already performs, and it holds no incident facts: identity, simulation origin
 * and the side-effect budgets all come from the engine and its durable ledger.
 */
class EmergencyEscalationCoordinator(
    private val engine: ProtectionEngine,
    private val smsSender: SmsSender?,
    private val callLauncher: CallLauncher?,
    private val contactsStore: EmergencyContactsStore?,
    private val alertAnnouncer: AlertAnnouncer?,
) : ProtectionEngineListener {

    /** What the coordinator last did, mirrored into the UI by the ViewModel. */
    @Volatile
    var smsOutcome: SmsEscalation = SmsEscalation.NotAttempted
        private set

    @Volatile
    var callOutcome: PrimaryContactCall = PrimaryContactCall.NotAttempted
        private set

    /**
     * True once the alert text reached the carrier for the current incident, and
     * the audible alert has therefore been silenced.
     */
    @Volatile
    var alertSilenced: Boolean = false
        private set

    /**
     * True when the device could send right now: SEND_SMS granted and a SIM
     * present.
     *
     * Exposed so the Settings screen can report what an escalation WOULD do. It
     * is asked of the coordinator rather than of a sender held by the ViewModel,
     * because the coordinator is the only component that owns one.
     */
    fun hasSmsPermission(): Boolean = smsSender?.capability() == SmsCapability.READY

    private val lock = Any()

    /**
     * Per-recipient progress for the incident being escalated, so a later
     * asynchronous callback can be attributed to the right incident and the right
     * recipient rather than to whatever happens to be on screen.
     */
    private var statesByIncident = NO_INCIDENT to emptyArray<SmsSendState?>()

    /**
     * Registers as an engine listener. Idempotent, so a holder that is asked for
     * the coordinator twice cannot escalate twice.
     *
     * Note what is NOT here: no timer, no coroutine and no Android dependency.
     * The coordinator does nothing until the engine publishes a flow transition,
     * which is what makes it immune to Activity lifecycle entirely.
     */
    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            engine.addListener(this)
        }
    }

    /** Returns the coordinator to its resting state between incidents. */
    private fun resetOutcome() {
        synchronized(lock) {
            smsOutcome = SmsEscalation.NotAttempted
            callOutcome = PrimaryContactCall.NotAttempted
            alertSilenced = false
            statesByIncident = NO_INCIDENT to emptyArray()
        }
    }

    /** Unregisters. Used by tests and never by production code. */
    fun stop() {
        synchronized(lock) {
            if (!started) return
            started = false
            engine.removeListener(this)
        }
    }

    private var started = false

    override fun onProtectionSnapshot(snapshot: ProtectionSnapshot) {
        val flow = snapshot.flow
        if (flow !is AccidentFlowState.Emergency) {
            // Outside an emergency there is no escalation outcome to report, so the
            // previous incident's verdict is cleared. Without this the last
            // incident's "called your contact" would still be on screen after the
            // user stood down - and would be re-applied over the UI's own reset
            // on the next snapshot.
            resetOutcome()
            return
        }
        if (!engine.hasOpenIncident) return

        // The escalation point itself. Because the engine mints a NEW incident id
        // on every entry into an incident, "a brand-new id" is precisely
        // "this incident has not been escalated yet" - across ViewModel
        // recreation, repeated service events and process restarts alike.
        synchronized(lock) {
            val incidentId = engine.currentIncidentId
            val ledger = engine.ledger
            if (ledger.isSmsHandled(incidentId) || statesByIncident.first == incidentId) return

            statesByIncident = incidentId to arrayOfNulls<SmsSendState>(
                SmsRecipientsPolicy.eligible(currentContacts()).size,
            )
            runEscalation(incidentId)
        }
        // Tell the UI about the outcome even though the engine has already
        // published; the carrier verdict usually arrives after that publish.
        engine.republishNow()
    }

    // ------------------------------------------------------------------
    // The escalation itself
    // ------------------------------------------------------------------

    private fun runEscalation(incidentId: Long) {
        // A simulated incident NEVER spends the user's money or contacts anyone.
        // This is checked against the engine's own incident record, which is set
        // atomically when the incident opens and cannot be lost by a ViewModel
        // being recreated or by a later event arriving from the service.
        if (engine.incidentSimulated) {
            smsOutcome = SmsEscalation.NotAttempted
            callOutcome = PrimaryContactCall.NotAttempted
            return
        }

        val contacts = currentContacts()
        if (!isSmsConsentGranted()) {
            smsOutcome = SmsEscalation.ConsentRequired
            callOutcome = PrimaryContactCall.ConsentRequired
            // No alert text means no carrier handoff, which means no automatic
            // call - but the manual actions stay available on the emergency UI.
            return
        }

        val sender = smsSender
        if (sender == null) {
            smsOutcome = SmsEscalation.CouldNotSend(
                SmsEscalation.SmsFailureReasonMirror.SEND_FAILED,
            )
            callOutcome = PrimaryContactCall.CouldNotStart
            return
        }

        when (sender.capability()) {
            SmsCapability.PERMISSION_DENIED -> {
                smsOutcome = SmsEscalation.SmsPermissionRequired
                callOutcome = PrimaryContactCall.CouldNotStart
                return
            }

            SmsCapability.NO_SIM -> {
                smsOutcome = SmsEscalation.NoSimAvailable
                callOutcome = PrimaryContactCall.CouldNotStart
                return
            }

            SmsCapability.READY -> Unit
        }

        val recipients = SmsRecipientsPolicy.eligible(contacts)
        if (recipients.isEmpty()) {
            smsOutcome = SmsEscalation.NoContactConfigured
            callOutcome = PrimaryContactCall.NoContactConfigured
            return
        }

        /*
         * The durable mark is written BEFORE the platform is touched. That is the
         * whole at-most-once strategy: a crash after this line can only ever cost
         * a message, never duplicate one, and a crash before it means nothing was
         * sent and the next opportunity may try again.
         */
        engine.ledger.markSmsHandled(incidentId)

        val body = SmsAlertMessageBuilder.build(
            incidentType = "the user has requested help and may need urgent assistance",
            incidentAtMillis = System.currentTimeMillis(),
            location = engine.emergencyLocationSnapshot(),
        )
        val batchId = System.currentTimeMillis()

        recipients.forEachIndexed { index, contact ->
            val reference = "resq-$incidentId-$batchId-$index"
            val result = runCatching {
                sender.send(
                    message = SmsMessage(contact.phoneNumber, body),
                    reference = reference,
                    listener = SmsSendListener { update -> onSmsUpdate(update) },
                )
            }.getOrElse { SmsSendState.Failed(SmsFailureReason.SEND_FAILED) }
            recordRecipientState(index, result)
        }

        publishAggregateOutcome()
    }

    /**
     * Folds one asynchronous platform verdict into the incident's recipient map.
     *
     * A callback whose incident id no longer matches is dropped: a message that
     * the carrier accepts twenty seconds late must not make a NEW incident look
     * like it reached the carrier.
     */
    private fun onSmsUpdate(update: com.resq.app.sms.SmsSendUpdate) {
        val index = update.recipientIndex
        if (index !in statesByIncident.second.indices) return
        val current = statesByIncident.second[index]
        // Never downgrade a confirmed verdict: a late failure cannot un-deliver.
        if (current is SmsSendState.Delivered) return
        if (current is SmsSendState.SentToCarrier &&
            update.state !is SmsSendState.Delivered &&
            update.state !is SmsSendState.Failed
        ) {
            return
        }
        synchronized(lock) {
            if (statesByIncident.first != engine.currentIncidentId) return
            statesByIncident.second[index] = update.state
            // Only a carrier handoff moves the escalation forward. ATTEMPTED and
            // DELIVERED both qualify (delivered implies accepted); FAILED does not.
            if (update.state is SmsSendState.SentToCarrier ||
                update.state is SmsSendState.Delivered
            ) {
                onCarrierHandoff(index)
            }
            publishAggregateOutcome()
        }
        engine.republishNow()
    }

    private fun recordRecipientState(index: Int, state: SmsSendState) {
        synchronized(lock) {
            if (index !in statesByIncident.second.indices) return
            statesByIncident.second[index] = state
            if (state is SmsSendState.SentToCarrier || state is SmsSendState.Delivered) {
                onCarrierHandoff(index)
            }
        }
    }

    /**
     * Step 2 of the ordering, and the ONLY thing that silences ResQ's own alert.
     *
     * Called when the carrier has accepted at least one message for this
     * incident. Both effects are guarded separately and durably, so neither can
     * be repeated by a later callback, a service tick or a ViewModel recreation.
     */
    private fun onCarrierHandoff(@Suppress("UNUSED_PARAMETER") recipientIndex: Int) {
        val incidentId = engine.currentIncidentId
        val ledger = engine.ledger

        if (!alertSilenced && !ledger.isAlertSilenced(incidentId)) {
            ledger.markAlertSilenced(incidentId)
            alertSilenced = true
            // ResQ's OWN alert only. No system-wide audio is touched, no stream is
            // muted, and the Emergency state itself is deliberately left alone -
            // the emergency UI and every manual action remain available.
            runCatching { alertAnnouncer?.silence() }
        }

        if (!ledger.isCallHandled(incidentId)) {
            ledger.markCallHandled(incidentId)
            attemptPrimaryContactCall(incidentId)
        }
    }

    /**
     * Rings the user's saved PRIMARY contact, and only after the carrier accepted
     * the alert text.
     *
     * Reuses the existing [CallLauncher] unchanged, so every existing safety rule
     * still applies: explicit call consent, CALL_PHONE, ACTION_CALL when permitted
     * and ACTION_DIAL when not. It never targets 112 - [EmergencyContactsPolicy]
     * has already excluded it from the recipient list, and the primary selection
     * is the user's own saved contact or nothing at all.
     */
    private fun attemptPrimaryContactCall(incidentId: Long) {
        val launcher = callLauncher
        val primary = eligiblePrimaryContact()
        callOutcome = when {
            primary == null -> PrimaryContactCall.NoContactConfigured
            !isCallConsentGranted() -> PrimaryContactCall.ConsentRequired
            launcher == null -> PrimaryContactCall.CouldNotStart
            else -> placeOrOpenCall(launcher, primary)
        }
    }

    /**
     * The saved contact this escalation is allowed to ring.
     *
     * It goes through [SmsRecipientsPolicy.eligible] and not merely through
     * [EmergencyContactsPolicy.primary], which is a genuine safety requirement
     * rather than tidiness: the recipient policy is what excludes the emergency
     * number. Without it a user who had saved "112" as a contact would have had
     * ResQ automatically PLACE A CALL to the emergency services, with no consent
     * screen and no human present - which is exactly the action the manual Call
     * 112 button exists to keep manual.
     */
    private fun eligiblePrimaryContact(): EmergencyContact? =
        SmsRecipientsPolicy.eligible(currentContacts())
            .firstOrNull { it.isPrimary }
            ?: SmsRecipientsPolicy.eligible(currentContacts()).firstOrNull()

    private fun placeOrOpenCall(launcher: CallLauncher, primary: EmergencyContact): PrimaryContactCall {
        if (!launcher.canPlaceCallsDirectly()) {
            return when (launcher.openDialer(primary.phoneNumber)) {
                CallLaunchResult.Opened -> PrimaryContactCall.DialerOpened(primary.name)
                // The missing permission is not reported here: granting it would
                // not have helped, because the dialer failed too.
                else -> PrimaryContactCall.CouldNotStart
            }
        }
        return when (launcher.placeCall(primary.phoneNumber)) {
            CallLaunchResult.Placed -> PrimaryContactCall.CallPlaced(primary.name)
            // Revoked between the check and the dispatch, or refused by OEM
            // policy: fall back to the dialer the user can still complete.
            CallLaunchResult.PermissionNotGranted ->
                when (launcher.openDialer(primary.phoneNumber)) {
                    CallLaunchResult.Opened -> PrimaryContactCall.DialerOpened(primary.name)
                    else -> PrimaryContactCall.CallPermissionRequired
                }

            CallLaunchResult.NoHandler -> PrimaryContactCall.CouldNotStart
            CallLaunchResult.Opened -> PrimaryContactCall.DialerOpened(primary.name)
        }
    }

    /**
     * Collapses the per-recipient states into the one incident-level verdict the
     * UI shows. A partial success is never rounded up into a blanket success.
     */
    private fun publishAggregateOutcome() {
        val states = statesByIncident.second
        if (states.isEmpty()) return
        val sent = states.count { it != null && it !is SmsSendState.Failed }
        val failedIndexes = states.withIndex()
            .filter { it.value is SmsSendState.Failed }
            .map { it.index }
        smsOutcome = when {
            sent == 0 -> SmsEscalation.CouldNotSend(firstFailureReason(states))
            failedIndexes.isNotEmpty() -> SmsEscalation.PartiallySent(
                sentRecipientCount = sent,
                // Names, never numbers: the screen can say who without putting a
                // phone number back into the UI.
                failedRecipients = failedIndexes.mapNotNull { index ->
                    SmsRecipientsPolicy.eligible(currentContacts()).getOrNull(index)?.name
                },
            )

            // Every recipient was accepted by the platform. Note the wording:
            // "handed to the carrier", never "delivered".
            else -> SmsEscalation.SentToCarrier(sent)
        }
    }

    private fun firstFailureReason(states: Array<SmsSendState?>): SmsEscalation.SmsFailureReasonMirror {
        states.forEach { state ->
            if (state is SmsSendState.Failed) {
                return when (state.reason) {
                    SmsFailureReason.PERMISSION_DENIED ->
                        SmsEscalation.SmsFailureReasonMirror.PERMISSION_DENIED

                    SmsFailureReason.INVALID_RECIPIENT ->
                        SmsEscalation.SmsFailureReasonMirror.INVALID_RECIPIENT

                    SmsFailureReason.NO_SIM -> SmsEscalation.SmsFailureReasonMirror.NO_SIM
                    SmsFailureReason.SEND_FAILED -> SmsEscalation.SmsFailureReasonMirror.SEND_FAILED
                }
            }
        }
        return SmsEscalation.SmsFailureReasonMirror.SEND_FAILED
    }

    /**
     * The stored contacts, re-read at escalation time rather than cached in the
     * coordinator: the user may have edited them since the incident opened.
     */
    private fun currentContacts(): List<EmergencyContact> =
        EmergencyContactsPolicy.ensureSinglePrimary(contactsStore?.contacts().orEmpty())

    private fun isSmsConsentGranted(): Boolean =
        contactsStore?.isSmsEscalationConsentGranted() == true

    private fun isCallConsentGranted(): Boolean =
        contactsStore?.isEscalationConsentGranted() == true
}