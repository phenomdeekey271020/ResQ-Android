package com.resq.app.sms

import com.resq.app.contacts.EmergencyContactsPolicy
import com.resq.app.model.EmergencyContact

/*
 * WHO MAY RECEIVE AN AUTOMATIC ALERT TEXT.
 *
 * Pure Kotlin, no Android and no radio, so every exclusion below is unit tested.
 * The rules are deliberately strict, and each one exists because getting it wrong
 * sends a message to the wrong human being:
 *
 *  - a contact must be dialable, exactly as the app already requires when it is
 *    saved. A hand-edited or corrupted store must not put a malformed string in
 *    front of the SMS API;
 *  - the emergency number is EXCLUDED. ResQ's one automatic action for 112 stays
 *    the manual button on the emergency screen, and an automatic alert must never
 *    text an emergency service without a person deciding to;
 *  - duplicates are collapsed by digits, so a store holding the same person
 *    twice cannot cause them to be messaged twice.
 *
 * Note what is NOT here: no check for whether the contact is "important", no
 * ranking, and no choice of a single favourite. A text alert is far less
 * intrusive than a ringing phone call - it is silent and does not consume the
 * user's attention - so every contact the user saved and consented to may be
 * told, once. The one-per-incident budget that stops it becoming a flood lives on
 * the engine, not here.
 */
object SmsRecipientsPolicy {

    /**
     * The regional emergency number ResQ shows on the emergency screen.
     *
     * Re-declared here on purpose: this policy must be decidable without the UI
     * layer, and the number below is asserted equal to the screen's own constant
     * by a unit test, so the two can never drift apart silently.
     */
    const val EmergencyNumber = "112"

    /**
     * Contacts that may be messaged automatically, in list order and with
     * duplicates removed.
     */
    fun eligible(contacts: List<EmergencyContact>): List<EmergencyContact> {
        val seen = mutableSetOf<String>()
        return contacts.filter { contact ->
            val digits = EmergencyContactsPolicy.phoneDigits(contact.phoneNumber)
            val usable = EmergencyContactsPolicy.isValidPhone(contact.phoneNumber)
            val notEmergencyNumber = digits != EmergencyContactsPolicy.phoneDigits(EmergencyNumber)
            usable && notEmergencyNumber && seen.add(digits)
        }
    }

    /**
     * True when [phoneNumber] may never be messaged automatically. Exposed so the
     * reason a recipient was skipped can be asserted directly.
     */
    fun isEmergencyNumber(phoneNumber: String): Boolean =
        EmergencyContactsPolicy.phoneDigits(phoneNumber) ==
            EmergencyContactsPolicy.phoneDigits(EmergencyNumber)
}