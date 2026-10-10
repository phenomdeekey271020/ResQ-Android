package com.resq.app.model

/**
 * Single source of truth for every ResQ screen: current destination plus the
 * typed protection, incident, emergency, telemetry, sensor and contact states.
 *
 * The screens stay stateless - they render this and emit callbacks - which
 * keeps the state machine testable outside of Compose.
 *
 * [telemetry] carries the simulated protection pipeline, [sensors] carries real
 * device hardware. They are intentionally separate so a simulation can never be
 * mistaken for a measurement.
 *
 * [detection] is the read-only verdict of the accident detection engine over the
 * real samples in [sensors]. It is an assessment only - it never carries an
 * emergency, which is the [incident] state machine's job.
 */
data class ResQUiState(
    val screen: AppScreen = AppScreen.HOME,
    val protection: ProtectionState = ProtectionState.ACTIVE,
    val incident: IncidentState = IncidentState.None,

    /**
     * Seconds left in the unconfirmed-safety (no-response) window, or null when
     * that window is not running. The window is what turns "nobody answered" into
     * an emergency on its own, so the screen can show how long is left to cancel.
     */
    val confirmationSecondsRemaining: Int? = null,
    val emergency: EmergencyState = EmergencyState.Inactive,
    val telemetry: TelemetryState = TelemetryState(),
    val sensors: SensorState = SensorState(),
    val detection: DetectionAssessment = DetectionAssessment(),
    /**
     * The position captured the moment the accident flow entered EMERGENCY, or
     * null whenever it has not.
     *
     * It is local state, not dispatch: the snapshot is used to search for
     * nearby emergency resources on the configured ResQ backend, and it is
     * never sent to emergency services or any third party. The snapshot carries
     * its own freshness verdict so a stale last-known fix can be shown to the
     * user without ever being presented as the current position.
     */
    val emergencyLocation: EmergencyLocationSnapshot? = null,

    /**
     * Progress of the nearby-resource search started when the flow entered the
     * resource-discovery stage of the emergency. Kept beside [emergency] rather
     * than inside it so repeated engine snapshots cannot reset a search that is
     * already in flight.
     */
    val resourceDiscovery: ResourceDiscoveryState = ResourceDiscoveryState.Idle,

    /**
     * Progress of the explicit location-recovery attempt behind the "Refresh
     * location" action.
     *
     * Kept separate from [resourceDiscovery] because the two answer different
     * questions: discovery says what happened to the SEARCH, this says whether
     * ResQ is currently WAITING for a position. Collapsing them is what let the
     * screen sit on an indistinguishable "nothing is happening" state forever.
     */
    val locationRecovery: LocationRecoveryState = LocationRecoveryState.Idle,

    /**
     * The user's trusted emergency contacts, configured before an incident and
     * stored only on this device. The emergency screen lists them so a call is
     * always one tap away, whether or not the automatic attempt worked.
     */
    val contacts: List<EmergencyContact> = emptyList(),

    /**
     * Explicit user consent for ResQ to open the dialer for the primary contact
     * when an emergency escalates. Without it, escalation never calls anyone by
     * itself and the emergency screen offers the call as a user action instead.
     */
    val escalationConsent: Boolean = false,

    /**
     * Whether the CALL_PHONE runtime permission is currently granted.
     *
     * Recorded rather than queried at each use, so the UI can tell the user what
     * their configuration will actually do - place a real call, or open a dialer
     * - instead of leaving them to find out during an emergency. Consent without
     * this permission means "dialer", and saying so plainly is the whole point
     * of the informed-consent requirement.
     */
    val callPermissionGranted: Boolean = false,

    /**
     * What ResQ actually did about the primary-contact call for the incident in
     * progress. Always truthful: a launched dialer is never reported as a
     * connected call, and an attempt is never made twice for one incident.
     */
    val primaryContactCall: PrimaryContactCall = PrimaryContactCall.NotAttempted,

    /**
     * Explicit, SEPARATE consent for ResQ to send one alert text per incident
     * from the user's SIM to their saved contacts.
     *
     * Off by default and independent of [escalationConsent]: agreeing to a phone
     * call is not agreeing to spend mobile credit on texts, and the copy the user
     * accepts says so. Nothing is ever messaged until this is true.
     */
    val smsEscalationConsent: Boolean = false,

    /**
     * Whether the SEND_SMS runtime permission is currently granted.
     *
     * Recorded for the same reason as [callPermissionGranted]: the Settings
     * screen must be able to tell the user what their configuration would
     * actually do rather than leaving them to find out during an emergency.
     */
    val smsPermissionGranted: Boolean = false,

    /**
     * What ResQ actually did about the alert text for the incident in progress.
     *
     * Truthful by construction: a message handed to the carrier is reported as
     * "sent to carrier", never as delivered, and a message that failed is
     * reported as failed.
     */
    val smsEscalation: SmsEscalation = SmsEscalation.NotAttempted,
) {
    /**
     * What an escalation would ACTUALLY do with the current configuration.
     *
     * Derived, never stored, and deliberately more conservative than
     * [escalationCapability]: consent without the permission cannot text anyone,
     * and the UI must say so plainly rather than implying SMS is armed.
     */
    val smsEscalationCapability: SmsEscalationCapability
        get() = when {
            contacts.isEmpty() -> SmsEscalationCapability.NO_CONTACT
            !smsEscalationConsent -> SmsEscalationCapability.CONSENT_OFF
            !smsPermissionGranted -> SmsEscalationCapability.PERMISSION_MISSING
            else -> SmsEscalationCapability.SMS_READY
        }

    /**
     * The one readiness answer for protection, onboarding and escalation - three
     * distinct situations that must never be collapsed into a single Boolean:
     *
     *  - [ProtectionReadiness.CONTACT_SETUP_INCOMPLETE]: no valid contact is
     *    configured, so automatic escalation is unavailable. This is the state
     *    first-launch contact setup offers to fix, and the state that earns the
     *    persistent warning if the user skips it.
     *  - [ProtectionReadiness.CONSENT_DISABLED]: a contact exists but the user
     *    has not consented to any automatic call attempt. Escalation stays
     *    silent by design until consent is explicitly granted.
     *  - [ProtectionReadiness.READY]: a valid primary contact exists AND
     *    consent was granted - the only state in which an incident may attempt
     *    the automatic primary-contact call.
     *
     * Derived, never stored: it can therefore never drift from the contacts or
     * the consent flag it describes.
     */
    val protectionReadiness: ProtectionReadiness
        get() = when {
            contacts.isEmpty() -> ProtectionReadiness.CONTACT_SETUP_INCOMPLETE
            !escalationConsent -> ProtectionReadiness.CONSENT_DISABLED
            else -> ProtectionReadiness.READY
        }

    /**
     * What an escalation will ACTUALLY do with the current configuration.
     *
     * This is the honest answer to "will ResQ call my contact for me?", and it
     * is derived rather than stored so it can never drift from consent,
     * permission and contact state. It is deliberately conservative: anything
     * unproven degrades to the dialer.
     */
    val escalationCapability: EscalationCapability
        get() = when {
            contacts.isEmpty() -> EscalationCapability.NO_CONTACT
            !escalationConsent -> EscalationCapability.CONSENT_OFF
            !callPermissionGranted -> EscalationCapability.PERMISSION_MISSING
            else -> EscalationCapability.DIRECT_CALL
        }
}

/**
 * What the app can genuinely do when an emergency escalates to a contact.
 *
 * An enum rather than a boolean because "will it call" has four honest answers,
 * and collapsing them into one flag is how users end up believing they have
 * automatic calling when they only have a dialer shortcut.
 */
enum class EscalationCapability {

    /** A primary contact is configured and ResQ will place a real call. */
    DIRECT_CALL,

    /** Consented, but CALL_PHONE is not granted: the dialer opens instead. */
    PERMISSION_MISSING,

    /** The user has not consented to any automatic contact call. */
    CONSENT_OFF,

    /** No trusted contact is configured at all. */
    NO_CONTACT,
}

/** See [ResQUiState.protectionReadiness]. */
enum class ProtectionReadiness {
    CONTACT_SETUP_INCOMPLETE,
    CONSENT_DISABLED,
    READY,
}

/**
 * What the app can genuinely do with an alert text right now. See
 * [ResQUiState.smsEscalationCapability].
 *
 * Four honest answers rather than one boolean, for the same reason
 * [EscalationCapability] exists: a user who believes ResQ will text their
 * contacts, when it can only do so once they have also granted the permission,
 * is a user who finds out at the worst possible moment.
 */
enum class SmsEscalationCapability {

    /** Consent granted and SEND_SMS held: a message can be sent from the SIM. */
    SMS_READY,

    /** Consented, but SEND_SMS is not granted. */
    PERMISSION_MISSING,

    /** The user has not consented to automatic alert texts. */
    CONSENT_OFF,

    /** No trusted contact is configured at all. */
    NO_CONTACT,
}
