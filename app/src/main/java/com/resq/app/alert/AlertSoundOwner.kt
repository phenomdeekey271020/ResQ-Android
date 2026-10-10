package com.resq.app.alert

import android.content.Context

/*
 * THE PROCESS-WIDE OWNER OF THE AUDIBLE ALERT.
 *
 * WHY THIS MOVED OUT OF THE VIEWMODEL. The announcer used to be created by
 * MainActivity and released by ResQViewModel.onCleared(). That is incompatible
 * with silencing the alert as part of escalation: the escalation now happens in a
 * component that outlives the Activity, so when the Activity died the announcer
 * died with it and there was nothing left to silence - and no single owner of the
 * sound at all. Owning it here means the alert tone and the spoken warning
 * survive Activity destruction, which is exactly what the screen-off scenario
 * needs.
 *
 * `release()` is deliberately a no-op. This object lives for the whole process,
 * so releasing the shared engine from one component would break every other
 * component using it; Android reclaims the audio resources with the process
 * anyway. Silence is explicit and is issued by whoever ends an incident.
 */
object AlertSoundOwner {

    @Volatile
    private var instance: AlertAnnouncer? = null

    /** The shared announcer, created on first use. Never null. */
    fun announcer(context: Context): AlertAnnouncer {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: AndroidAlertAnnouncer(context).also { instance = it }
        }
    }
}