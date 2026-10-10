package com.resq.app.model

/**
 * A trusted emergency contact, configured by the user BEFORE an incident.
 *
 * Privacy: [phoneNumber] is stored only on this device, is never written to
 * Logcat, is never included in diagnostics output and is never sent to the ResQ
 * backend or to any third party. ResQ uses it for one thing only - opening the
 * system dialer for the primary contact when an emergency escalates.
 */
data class EmergencyContact(
    /** Stable local identifier, used for edits and deletes. */
    val id: String,
    val name: String,
    /** Dialable number as the user typed it. Never logged. */
    val phoneNumber: String,
    /**
     * The contact an automatic escalation prefers. At most one contact is ever
     * primary; when the user has not chosen, the first configured contact is the
     * fallback (see [com.resq.app.contacts.EmergencyContactsPolicy.primary]).
     */
    val isPrimary: Boolean = false,
)

/**
     * What ResQ actually did about calling the primary contact when the emergency
     * escalated.
     *
     * This exists so the emergency screen can be truthful. ResQ can place a real
     * call when it holds CALL_PHONE and the user consented, or open the dialer
     * otherwise - but it can NEVER know whether anyone answered, so there is
     * deliberately no "connected" or "answered" value here.
     */
sealed interface PrimaryContactCall {

    /** No automatic attempt was made (no escalation yet, or a test-mode flow). */
    data object NotAttempted : PrimaryContactCall

    /** Contacts exist but the user has not granted escalation consent yet. */
    data object ConsentRequired : PrimaryContactCall

    /** No contact is configured, so there is nothing to call. */
    data object NoContactConfigured : PrimaryContactCall

    /**
     * The strongest supported escalation succeeded: ResQ asked Android to place a
     * call directly to the primary contact and the platform accepted the intent.
     *
     * It does NOT mean the call connected, rang, or was answered - ResQ has no
     * way to know any of that and must never imply it. See [CallPlaced] copy.
     */
    data class CallPlaced(val contactName: String) : PrimaryContactCall

    /**
     * The system dialer was asked to open pre-filled with the primary contact's
     * number. It does NOT mean the call was placed, answered or connected.
     */
    data class DialerOpened(val contactName: String) : PrimaryContactCall

    /** The dialer could not be started (no handler, or the platform refused). */
    data object CouldNotStart : PrimaryContactCall

    /**
     * Automatic calling is switched on and a direct call was wanted, but the
     * CALL_PHONE permission is missing or was revoked. The UI offers the exact
     * way to grant it rather than silently degrading.
     */
    data object CallPermissionRequired : PrimaryContactCall

    /**
     * The automatic attempt could not be made right now - the app was in the
     * background or the screen was locked, where Android may refuse to start the
     * call UI. The emergency screen and the notification both keep an explicit
     * manual action, so this is a dead end for nobody.
     */
    data object ManualCallRequired : PrimaryContactCall
}
