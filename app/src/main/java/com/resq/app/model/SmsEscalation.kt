package com.resq.app.model

/*
 * WHAT RESQ ACTUALLY DID ABOUT THE TEXT ALERT FOR THE INCIDENT IN PROGRESS.
 *
 * It mirrors [PrimaryContactCall] deliberately: the emergency screen has exactly
 * one escalation story to tell, and "the call was placed" and "the message was
 * sent" must be reported with the same discipline.
 *
 * The honest ceiling of an SMS is deliberately LOWER than a call's: ResQ can
 * hand a message to the carrier, but whether it arrives - and whether anyone
 * reads it - is beyond its reach. There is therefore deliberately no
 * `Delivered` success state for a whole incident: at most it can be reported as
 * confirmed for every recipient, and even that only when the operator returned a
 * receipt. The failure and "unknown" states are first-class so the UI can never
 * quietly imply more than happened.
 */
sealed interface SmsEscalation {

    /** No automatic text alert was made (no escalation yet, or a test-mode flow). */
    data object NotAttempted : SmsEscalation

    /** Contacts exist but the user has not granted the SMS consent yet. */
    data object ConsentRequired : SmsEscalation

    /** No valid contact is configured, so there is nobody to message. */
    data object NoContactConfigured : SmsEscalation

    /** SMS escalation is on and a message was wanted, but SEND_SMS is missing. */
    data object SmsPermissionRequired : SmsEscalation

    /** The device has no SIM, so there is no number to send from. */
    data object NoSimAvailable : SmsEscalation

    /**
     * Every eligible recipient was handed to the carrier. This is NOT a delivery
     * receipt - see [PrimaryContactCall] for the same discipline applied to calls.
     */
    data class SentToCarrier(val recipientCount: Int) : SmsEscalation

    /**
     * Some recipients took the message and some did not. [failedRecipients] holds
     * the ones that failed so the screen can be honest about a partial success
     * rather than rounding it up to "sent".
     */
    data class PartiallySent(
        val sentRecipientCount: Int,
        val failedRecipients: List<String>,
    ) : SmsEscalation

    /** The platform refused every recipient. */
    data class CouldNotSend(val reason: SmsFailureReasonMirror) : SmsEscalation

    /**
     * Mirrors the send-failure reasons without importing the SMS package into the
     * UI-facing state model, so the screen does not need to know the radio's
     * vocabulary to explain itself.
     */
    enum class SmsFailureReasonMirror {
        PERMISSION_DENIED,
        INVALID_RECIPIENT,
        NO_SIM,
        SEND_FAILED,
    }
}