package com.resq.app.escalation

import android.content.Context

/*
 * THE DURABLE LEDGER, ON DISK.
 *
 * SharedPreferences, exactly like [com.resq.app.settings.ProtectionPreferences],
 * [com.resq.app.contacts.SharedPreferencesEmergencyContactsStore] and
 * [com.resq.app.data.sensors.SharedPreferencesLocationFixStore]. No new library,
 * no database, and consistent with how the rest of ResQ already persists state.
 *
 * It is private to the app, it holds NO phone numbers, NO coordinates and NO
 * message text - only incident ids and booleans - so there is nothing sensitive
 * in it even if the device is imaged.
 *
 * WHY THE IDS ARE STORED AND NOT JUST COUNTED: an in-memory counter restarts at
 * zero when the process dies, which would let a new incident be handed an id
 * that an old, still-persisted mark refers to. Persisting the counter is what
 * makes "has this incident already been handled" a safe question across restarts.
 */
/*
 * Lint advises apply() over commit() everywhere in this file. That advice is
 * deliberately NOT taken: apply() writes asynchronously, so a process killed a
 * moment after the mark - which is exactly the case this class exists to survive -
 * would lose it and the incident could escalate a second time. commit() costs a
 * few milliseconds on a path that runs at most a handful of times per incident.
 */
@Suppress("ApplySharedPref")
class SharedPreferencesEscalationLedger(context: Context) : EscalationLedger {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun beginIncident(simulated: Boolean): Long {
        // Synchronous commit, not apply(): the id must be on disk before any
        // side effect is attempted, because the whole point of this class is to
        // survive the process dying a moment later. The cost is a few
        // milliseconds on a path that happens once per real incident.
        val next = preferences.getLong(KEY_LAST_INCIDENT_ID, 0L) + 1L
        preferences.edit()
            .putLong(KEY_LAST_INCIDENT_ID, next)
            .putBoolean(simulatedKey(next), simulated)
            .commit()
        return next
    }

    override fun isIncidentSimulated(incidentId: Long): Boolean =
        preferences.getBoolean(simulatedKey(incidentId), false)

    override fun isSmsHandled(incidentId: Long): Boolean =
        preferences.getBoolean(smsKey(incidentId), false)

    override fun markSmsHandled(incidentId: Long) {
        preferences.edit().putBoolean(smsKey(incidentId), true).commit()
    }

    override fun isCallHandled(incidentId: Long): Boolean =
        preferences.getBoolean(callKey(incidentId), false)

    override fun markCallHandled(incidentId: Long) {
        preferences.edit().putBoolean(callKey(incidentId), true).commit()
    }

    override fun isAlertSilenced(incidentId: Long): Boolean =
        preferences.getBoolean(silenceKey(incidentId), false)

    override fun markAlertSilenced(incidentId: Long) {
        preferences.edit().putBoolean(silenceKey(incidentId), true).commit()
    }

    private fun simulatedKey(id: Long) = "incident_${id}_simulated"
    private fun smsKey(id: Long) = "incident_${id}_sms"
    private fun callKey(id: Long) = "incident_${id}_call"
    private fun silenceKey(id: Long) = "incident_${id}_silenced"

    private companion object {
        const val PREFERENCES_NAME = "resq_escalation_ledger"
        const val KEY_LAST_INCIDENT_ID = "last_incident_id"
    }
}