package com.resq.app.sms

/*
 * THE ONLY WAY RESQ SENDS A TEXT MESSAGE, AND WHY IT IS AN INTERFACE.
 *
 * ResQ may ask Android to send one SMS per incident from the user's own SIM to
 * the emergency contacts the user saved and consented to. That is a real
 * external effect - it costs the user money, it tells another human person that
 * something may be wrong, and it cannot be taken back - so it sits behind an
 * interface exactly like [com.resq.app.contacts.CallLauncher] does for calls:
 *
 *  - no automated test can ever reach a real radio. Every test injects a fake,
 *    so `ResQViewModel` unit tests never send a message to anyone;
 *  - the incident flow can assert exactly what WOULD be sent, how many times,
 *    and which recipient each message was for.
 *
 * WHAT THIS IS NOT. There is no cloud telephony provider here, no Bolna, no
 * server-side sending, no SMS gateway and no API key. A message goes from the
 * user's SIM through their carrier, which is also why the consent copy must
 * mention carrier charges.
 *
 * HONESTY CONTRACT. `send()` returning is NOT a delivery receipt. SmsManager is
 * asynchronous: a successful call only means the message was handed to the
 * telephony stack. Everything afterwards is reported through [SmsSendListener]
 * with the separate [SmsSendState] values, and no code path may describe a
 * message as delivered on the strength of the request alone.
 */

/** One message to one recipient. The body is never logged anywhere. */
data class SmsMessage(
    /** Dialable destination, exactly as the user saved it. Never logged. */
    val recipientNumber: String,
    /** The message text. Never logged. */
    val body: String,
)

/** Why a message could not be handed to the platform at all. */
enum class SmsFailureReason {
    /** SEND_SMS is not granted. Nothing was attempted. */
    PERMISSION_DENIED,

    /** The destination is not a dialable number. Nothing was attempted. */
    INVALID_RECIPIENT,

    /** The device has no SIM, so there is no number to send from. */
    NO_SIM,

    /** The platform could not send: no service, radio off, or the send failed. */
    SEND_FAILED,
}

/**
 * How far one message actually got.
 *
 * The ordering matters and is the whole point: [ATTEMPTED] is weaker than
 * [SENT_TO_CARRIER], which is weaker than [DELIVERY_CONFIRMED]. ResQ reports the
 * weakest state it can prove and never jumps ahead.
 */
sealed interface SmsSendState {

    /** ResQ asked the platform to send. Nothing has been confirmed. */
    data object Attempted : SmsSendState

    /**
     * The carrier accepted the message for delivery.
     *
     * This is the strongest claim most devices can actually make. It does NOT
     * mean the recipient's phone received or read it.
     */
    data class SentToCarrier(val reference: String) : SmsSendState

    /**
     * The platform reported a delivery receipt for [reference].
     *
     * Delivery reports are operator-dependent: many carriers and most
     * two-way-SMS setups never return one. Absence of this state means
     * "unknown", never "not delivered".
     */
    data class Delivered(val reference: String) : SmsSendState

    /** The send failed. The reason is specific enough to be acted on. */
    data class Failed(val reason: SmsFailureReason) : SmsSendState
}

/**
 * Progress of one message.
 *
 * Delivered as a [reference] that is unique per message attempt, so a late
 * callback from a previous incident can never be attributed to a new one.
 */
data class SmsSendUpdate(
    val reference: String,
    val recipientIndex: Int,
    val state: SmsSendState,
)

/** Receives the asynchronous progress of one message. */
fun interface SmsSendListener {
    fun onSmsUpdate(update: SmsSendUpdate)
}

/** What the device can do with SMS right now, as the platform reports it. */
enum class SmsCapability {

    /** Permission granted and a SIM is present: a message can be handed over. */
    READY,

    /** SEND_SMS is not granted. */
    PERMISSION_DENIED,

    /** No SIM, so there is no number to send from. */
    NO_SIM,
}

/**
 * The single seam between the incident flow and the radio.
 *
 * Every method is expected to be safe to call from any thread and to never
 * throw: a messaging platform that refuses must degrade to a reported state,
 * never to an exception escaping into the emergency flow.
 */
interface SmsSender {

    /** What the device can do right now. Re-read at escalation time. */
    fun capability(): SmsCapability

    /**
     * Hands [message] to the platform.
     *
     * Returns the synchronous outcome only: [SmsSendState.Attempted] when the
     * platform took the message, or [SmsSendState.Failed] when it did not. Any
     * later progress - and there may be none - arrives through [listener].
     *
     * @param reference a caller-chosen id unique to this attempt, echoed back on
     *   every callback so a late result cannot be mistaken for another message.
     */
    fun send(message: SmsMessage, reference: String, listener: SmsSendListener): SmsSendState
}