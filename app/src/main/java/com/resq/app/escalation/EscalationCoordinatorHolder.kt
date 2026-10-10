package com.resq.app.escalation

import android.content.Context
import com.resq.app.alert.AlertAnnouncer
import com.resq.app.alert.AndroidAlertAnnouncer
import com.resq.app.contacts.EmergencyContactsStore
import com.resq.app.contacts.SharedPreferencesEmergencyContactsStore
import com.resq.app.contacts.SystemCallLauncher
import com.resq.app.protection.ProtectionEngine
import com.resq.app.protection.ProtectionEngineHolder
import com.resq.app.sms.AndroidSmsSender
import com.resq.app.sms.SmsSender

/*
 * WHO OWNS THE ESCALATION COORDINATOR, AND FOR HOW LONG.
 *
 * LIFECYCLE: the process. The instance is created on first use and never torn
 * down, exactly like [ProtectionEngineHolder]. It registers itself as an engine
 * listener once and stays registered.
 *
 * THE CRITICAL PART - who creates it. [com.resq.app.service.ProtectionForegroundService]
 * asks for it in onCreate. That is deliberate and is the entire fix for the
 * lifecycle bug: the service is started by an explicit user tap when protection
 * is enabled and runs with stopWithTask=false, so it - not the Activity - is the
 * component that is reliably alive while an incident can escalate. Creating the
 * coordinator from MainActivity instead would leave the exact same gap, because
 * the Activity is what gets destroyed.
 *
 * The UI then merely READS the coordinator's outcome to render truthful copy. It
 * no longer decides whether anything is sent.
 */
object EscalationCoordinatorHolder {

    @Volatile
    private var instance: EmergencyEscalationCoordinator? = null

    /**
     * The process-wide coordinator, created on first use.
     *
     * Thread-safe and idempotent: the service and the Activity both call this and
     * get the same instance, so the escalation side effects exist exactly once per
     * process however many components ask for them.
     */
    fun coordinator(context: Context): EmergencyEscalationCoordinator {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: build(
                engine = ProtectionEngineHolder.engine(context),
                smsSender = AndroidSmsSender(context),
                callLauncher = SystemCallLauncher(context),
                contactsStore = SharedPreferencesEmergencyContactsStore(context),
                // The SAME process-wide announcer the UI uses, which is what lets
                // the coordinator silence the audible alert without the Activity
                // being alive and without two speech engines fighting each other.
                alertAnnouncer = com.resq.app.alert.AlertSoundOwner.announcer(context),
            ).also {
                // Registered LAST, after every dependency is in place, so the very
                // first snapshot can never reach a half-built coordinator.
                it.start()
                instance = it
            }
        }
    }

    /**
     * Test seam: installs a coordinator built from fakes, so a unit test can drive
     * the real escalation rules with no Android dependency at all.
     *
     * Returns the previous instance so a test can restore it. Production code
     * never calls this.
     */
    fun installForTest(coordinator: EmergencyEscalationCoordinator?): EmergencyEscalationCoordinator? {
        synchronized(this) {
            val previous = instance
            previous?.stop()
            instance = coordinator
            coordinator?.start()
            return previous
        }
    }

    private fun build(
        engine: ProtectionEngine,
        smsSender: SmsSender,
        callLauncher: com.resq.app.contacts.CallLauncher,
        contactsStore: EmergencyContactsStore,
        alertAnnouncer: AlertAnnouncer,
    ): EmergencyEscalationCoordinator = EmergencyEscalationCoordinator(
        engine = engine,
        smsSender = smsSender,
        callLauncher = callLauncher,
        contactsStore = contactsStore,
        alertAnnouncer = alertAnnouncer,
    )
}
