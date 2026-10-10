package com.resq.app.escalation

/*
 * THE DURABLE SIDE-EFFECT LEDGER.
 *
 * WHY THIS EXISTS. Escalation side effects (sending an SMS, placing a call) are
 * NOT idempotent: doing the same one twice texts a person twice, or rings them
 * twice. A plain in-memory Boolean cannot protect them, because Android may kill
 * the process at any moment - including between "decided to send" and "recorded
 * that it was sent". This interface is the app's answer to that.
 *
 * THE TRADE-OFF, STATED HONESTLY. The Android SMS and telephony APIs give no
 * exactly-once guarantee, so ResQ cannot promise exactly-once delivery. It picks
 * AT-MOST-ONCE per incident, and the reasoning is deliberate:
 *
 *  - a duplicate alert text costs the user money and startles a real person;
 *  - a MISSED alert is worse than a duplicate, but only when the incident is
 *    real - and for a real incident the window between the durable write and the
 *    dispatch is a few milliseconds, with the escalation re-armed whenever the
 *    app is next opened because the incident is still open in the engine.
 *
 * So: if the process dies BEFORE the durable mark, nothing was sent and the next
 * attempt may send (at-least-once). If it dies AFTER the mark but before the
 * platform call returns, the side effect is treated as done (at-most-once). The
 * engine's monotonic incident id guarantees a later, genuinely new incident is
 * never suppressed by a stale mark from a previous one.
 *
 * This interface is intentionally tiny and synchronous. It is consulted on the
 * incident path, where blocking on disk is preferable to a race.
 */
interface EscalationLedger {

    /**
     * Allocates the identity for a brand-new incident and returns it.
     *
     * Ids are strictly increasing and never reused, INCLUDING across process
     * restarts, which is what makes "already handled incident N" a safe question
     * to ask forever. A restart must never hand a new incident the id of an old
     * one whose marks are still on disk.
     */
    fun beginIncident(simulated: Boolean): Long

    /** True when this incident was started from Developer/Test Mode. */
    fun isIncidentSimulated(incidentId: Long): Boolean

    /** True when the alert text for [incidentId] has already been sent. */
    fun isSmsHandled(incidentId: Long): Boolean

    /**
     * Records that the alert text for [incidentId] has been handed off.
     *
     * Called BEFORE the platform is asked to send, never after, so a crash in the
     * middle of the call can only ever under-send, never double-send.
     */
    fun markSmsHandled(incidentId: Long)

    /** True when the primary-contact call for [incidentId] has already been tried. */
    fun isCallHandled(incidentId: Long): Boolean

    /** Records that the primary-contact call for [incidentId] has been tried. */
    fun markCallHandled(incidentId: Long)

    /** True when ResQ's own audible alert has been silenced for [incidentId]. */
    fun isAlertSilenced(incidentId: Long): Boolean

    /** Records that ResQ's audible alert has been silenced for [incidentId]. */
    fun markAlertSilenced(incidentId: Long)
}

/**
 * In-memory implementation for unit tests and for any build with no Context.
 *
 * Explicitly NOT durable, and documented as such at the class level: a test can
 * assert the coordination rules, but only the SharedPreferences implementation
 * can survive process death, and only that one is ever wired into the app.
 */
class InMemoryEscalationLedger : EscalationLedger {

    private var lastIncidentId = 0L
    private val simulated = mutableSetOf<Long>()
    private val smsHandled = mutableSetOf<Long>()
    private val callHandled = mutableSetOf<Long>()
    private val silenced = mutableSetOf<Long>()

    override fun beginIncident(simulated: Boolean): Long {
        val id = ++lastIncidentId
        if (simulated) this.simulated += id
        return id
    }

    override fun isIncidentSimulated(incidentId: Long): Boolean = incidentId in simulated

    override fun isSmsHandled(incidentId: Long): Boolean = incidentId in smsHandled

    override fun markSmsHandled(incidentId: Long) {
        smsHandled += incidentId
    }

    override fun isCallHandled(incidentId: Long): Boolean = incidentId in callHandled

    override fun markCallHandled(incidentId: Long) {
        callHandled += incidentId
    }

    override fun isAlertSilenced(incidentId: Long): Boolean = incidentId in silenced

    override fun markAlertSilenced(incidentId: Long) {
        silenced += incidentId
    }
}