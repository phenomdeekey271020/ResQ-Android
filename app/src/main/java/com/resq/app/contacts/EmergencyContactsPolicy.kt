package com.resq.app.contacts

import com.resq.app.model.EmergencyContact

/*
 * THE RULES OF THE EMERGENCY CONTACT LIST.
 *
 * Pure Kotlin, no Android and no storage, so every rule below - the cap, the
 * duplicate check, the single-primary invariant and the safe fallback - is unit
 * testable and identical wherever contacts are edited.
 *
 * Nothing here talks to the network or logs a phone number.
 */
object EmergencyContactsPolicy {

    /** MVP limit: up to three trusted contacts. */
    const val MaxContacts = 3

    /** Shortest dialable number ResQ will accept (local numbers, no country code). */
    private const val MinDigits = 7

    /** Longest dialable number: the E.164 maximum. */
    private const val MaxDigits = 15

    /**
     * True when [raw] can be dialed. Accepts an optional leading `+` plus digits
     * with cosmetic spacing, dashes and parentheses. Rejects anything else, so a
     * note ("call mum") or a partial number never becomes a dial target.
     */
    fun isValidPhone(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        val plusCount = trimmed.count { it == '+' }
        if (plusCount > 1) return false
        if (plusCount == 1 && !trimmed.startsWith("+")) return false
        val allowed = trimmed.all { character ->
            character.isDigit() || character == '+' || character == ' ' ||
                character == '-' || character == '(' || character == ')'
        }
        if (!allowed) return false
        return phoneDigits(trimmed).length in MinDigits..MaxDigits
    }

    /** True when [raw] is a usable display name. */
    fun isValidName(raw: String): Boolean = raw.trim().isNotEmpty()

    /** True when [raw] is a contact ResQ would accept and store. */
    fun isValid(name: String, phoneNumber: String): Boolean =
        isValidName(name) && isValidPhone(phoneNumber)

    /** Digits only, for length checks and duplicate detection. */
    fun phoneDigits(raw: String): String = raw.filter(Char::isDigit)

    /**
     * True when another contact already uses the same number. Comparison is on
     * digits only, so "+91 98765 43210" and "9876543210" are recognised as the
     * same person. [excludingId] lets an edit keep its own number.
     */
    fun isDuplicate(
        existing: List<EmergencyContact>,
        phoneNumber: String,
        excludingId: String? = null,
    ): Boolean {
        val candidate = phoneDigits(phoneNumber)
        if (candidate.isEmpty()) return false
        return existing.any { contact ->
            contact.id != excludingId && phoneDigits(contact.phoneNumber) == candidate
        }
    }

    /**
     * Adds [contact], or replaces the contact with the same id.
     *
     * Enforces the three-contact cap (a fourth is refused, the list is returned
     * unchanged) and restores the single-primary invariant afterwards.
     */
    fun upsert(existing: List<EmergencyContact>, contact: EmergencyContact): List<EmergencyContact> {
        val isUpdate = existing.any { it.id == contact.id }
        if (!isUpdate && existing.size >= MaxContacts) return existing
        val without = existing.filterNot { it.id == contact.id }
        return ensureSinglePrimary(without + contact)
    }

    /** Removes [id] and re-establishes the primary invariant. */
    fun remove(existing: List<EmergencyContact>, id: String): List<EmergencyContact> =
        ensureSinglePrimary(existing.filterNot { it.id == id })

    /** Makes [id] the primary contact. Unknown ids leave the list untouched. */
    fun withPrimary(existing: List<EmergencyContact>, id: String): List<EmergencyContact> =
        if (existing.none { it.id == id }) {
            existing
        } else {
            existing.map { it.copy(isPrimary = it.id == id) }
        }

    /**
     * Guarantees at most one primary contact and never zero when the list is not
     * empty, so a stored list can never leave the escalation without a candidate.
     */
    fun ensureSinglePrimary(existing: List<EmergencyContact>): List<EmergencyContact> {
        if (existing.isEmpty()) return existing
        val primaries = existing.filter { it.isPrimary }
        return when {
            primaries.size == 1 -> existing
            primaries.size > 1 -> {
                val chosen = primaries.first().id
                existing.map { it.copy(isPrimary = it.id == chosen) }
            }

            else -> existing.mapIndexed { index, contact ->
                contact.copy(isPrimary = index == 0)
            }
        }
    }

    /**
     * The contact an automatic escalation calls: the chosen primary, or the first
     * configured contact when the user never picked one. Null only when there are
     * no contacts at all - the safe "nothing to call" answer.
     */
    fun primary(existing: List<EmergencyContact>): EmergencyContact? =
        existing.firstOrNull { it.isPrimary } ?: existing.firstOrNull()
}
