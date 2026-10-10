package com.resq.app.alert

/*
 * AUDIBLE ALERT - THE ONLY EXTERNAL EFFECT OF THE INCIDENT FLOW.
 *
 * The flow decides *when* a cue is due; this package decides *how* it is played.
 * Keeping it behind an interface means the state-machine tests never touch a
 * speaker or a TTS engine, and the Android implementation can be replaced or
 * faked without changing a single transition rule.
 *
 * ResQ never claims the alert was heard. Sound can be muted, the volume can be
 * zero, an audio route can be busy or the platform can interrupt playback; when
 * that happens the ongoing notification remains as the visible fallback.
 */

/** Which audible cue an incident stage calls for. */
enum class AlertCue {

    /** The countdown started: a possible accident was detected. */
    POSSIBLE_ACCIDENT,

    /** The countdown ran out with no answer: ResQ is escalating. */
    NO_RESPONSE,
}

/**
 * Every line ResQ can speak.
 *
 * Configurable in code on purpose: the spoken wording never depends on a network
 * request, never comes from the backend and never contains contact details.
 * Change the strings here and both the spoken and the on-screen copy move
 * together.
 */
object AlertMessages {

    /**
     * First warning, played the moment the "are you okay?" countdown starts.
     */
    const val POSSIBLE_ACCIDENT: String =
        "Possible accident detected. Are you okay? " +
            "Tap I'm Okay to cancel the emergency alert, or tap I Need Help if you need assistance."

    /**
     * Second warning, played when the countdown expired without an explicit
     * answer and the emergency response is starting.
     */
    const val NO_RESPONSE: String =
        "No response detected. A possible accident or emergency may have occurred. " +
            "ResQ is starting your emergency response."

    /** Spoken text for [cue]. */
    fun forCue(cue: AlertCue): String = when (cue) {
        AlertCue.POSSIBLE_ACCIDENT -> POSSIBLE_ACCIDENT
        AlertCue.NO_RESPONSE -> NO_RESPONSE
    }
}

/**
 * Plays the audible alert and speaks the warning for a cue.
 *
 * Implementations must never throw: a missing TTS engine, no alarm sound on the
 * device or a refused audio focus must leave the rest of the incident flow
 * untouched. Every method has to be safe to call repeatedly and from any stage
 * of the incident.
 */
interface AlertAnnouncer {

    /**
     * Plays the alert tone and speaks [cue], replacing whatever was playing.
     * Calling it twice must never produce two overlapping messages.
     */
    fun announce(cue: AlertCue)

    /**
     * Stops the tone and any speech in progress. Called when the user cancels,
     * when the incident is reset and before a new cue is played.
     */
    fun silence()

    /** Releases the engine resources. Idempotent; the announcer is unusable after. */
    fun release()
}
