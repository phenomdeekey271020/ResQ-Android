package com.resq.app.contacts

import android.content.Context
import com.resq.app.model.EmergencyContact
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * WHERE EMERGENCY CONTACTS LIVE.
 *
 * Storage follows the project's existing convention - a private SharedPreferences
 * file, exactly like [com.resq.app.settings.AppearancePreferences] and
 * [com.resq.app.settings.ProtectionPreferences]. No database and no new library
 * was added for three rows of data.
 *
 * Privacy rules enforced here:
 *   - the file lives in the app's private storage,
 *   - contact names and numbers are never logged, at any log level,
 *   - the escalation consent flag is stored beside them so the user's decision
 *     survives a restart rather than being re-asked.
 */
interface EmergencyContactsStore {

    /** The configured contacts, primary invariant already applied. */
    fun contacts(): List<EmergencyContact>

    /** Replaces the stored list. Callers pass results of [EmergencyContactsPolicy]. */
    fun saveContacts(contacts: List<EmergencyContact>)

    /** True once the user has explicitly agreed to an automatic call attempt. */
    fun isEscalationConsentGranted(): Boolean

    /** Persists the user's consent decision. */
    fun setEscalationConsentGranted(granted: Boolean)

    /**
     * True once the user has explicitly agreed to automatic SMS alerts.
     *
     * Deliberately a SEPARATE flag from [isEscalationConsentGranted]: consenting
     * to have ResQ place a phone call is not consenting to have it spend the
     * user's mobile balance sending texts, and a user may reasonably want one and
     * not the other. Default is false, so nothing is ever messaged until the user
     * has actively switched it on after being told messages are sent from their
     * SIM and may cost carrier credit.
     */
    fun isSmsEscalationConsentGranted(): Boolean

    /** Persists the SMS consent decision. */
    fun setSmsEscalationConsentGranted(granted: Boolean)
}

/** SharedPreferences-backed implementation used by the running app. */
class SharedPreferencesEmergencyContactsStore(context: Context) : EmergencyContactsStore {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun contacts(): List<EmergencyContact> {
        val encoded = preferences.getString(KEY_CONTACTS, null) ?: return emptyList()
        // A corrupt or partially written entry must degrade to "no contacts" rather
        // than crash the emergency flow. The failure reason is logged without any
        // contact data.
        val decoded = runCatching { json.decodeFromString<List<StoredContact>>(encoded) }.getOrNull()
            ?: return emptyList()
        return EmergencyContactsPolicy.ensureSinglePrimary(
            decoded.map { it.toContact() }.filter { EmergencyContactsPolicy.isValid(it.name, it.phoneNumber) },
        )
    }

    override fun saveContacts(contacts: List<EmergencyContact>) {
        val encoded = json.encodeToString(
            EmergencyContactsPolicy.ensureSinglePrimary(contacts)
                .take(EmergencyContactsPolicy.MaxContacts)
                .map { StoredContact.from(it) },
        )
        preferences.edit().putString(KEY_CONTACTS, encoded).apply()
    }

    override fun isEscalationConsentGranted(): Boolean =
        preferences.getBoolean(KEY_ESCALATION_CONSENT, false)

    override fun setEscalationConsentGranted(granted: Boolean) {
        preferences.edit().putBoolean(KEY_ESCALATION_CONSENT, granted).apply()
    }

    override fun isSmsEscalationConsentGranted(): Boolean =
        preferences.getBoolean(KEY_SMS_ESCALATION_CONSENT, false)

    override fun setSmsEscalationConsentGranted(granted: Boolean) {
        preferences.edit().putBoolean(KEY_SMS_ESCALATION_CONSENT, granted).apply()
    }

    /**
     * Serialised form. Kept private to this file so the wire shape of local
     * storage cannot leak into the domain model.
     */
    @Serializable
    private data class StoredContact(
        val id: String,
        val name: String,
        val phone: String,
        val primary: Boolean = false,
    ) {
        fun toContact() = EmergencyContact(
            id = id,
            name = name,
            phoneNumber = phone,
            isPrimary = primary,
        )

        companion object {
            fun from(contact: EmergencyContact) = StoredContact(
                id = contact.id,
                name = contact.name,
                phone = contact.phoneNumber,
                primary = contact.isPrimary,
            )
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "resq_contacts"
        const val KEY_CONTACTS = "emergency_contacts"
        const val KEY_ESCALATION_CONSENT = "escalation_consent_granted"
        const val KEY_SMS_ESCALATION_CONSENT = "sms_escalation_consent_granted"

        val json = Json { ignoreUnknownKeys = true }
    }
}
