package com.resq.app.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/*
 * ANDROID IMPLEMENTATION OF THE AUDIBLE ALERT.
 *
 * Two mechanisms, deliberately:
 *
 *   1. an ALARM-stream alert tone, so a possible incident is audible even with
 *      the media volume down, and
 *   2. TextToSpeech, which speaks the actual warning.
 *
 * Both are best effort. Every call into the platform is wrapped, because none of
 * these failures may stop the incident flow:
 *
 *   - TextToSpeech may not be installed, may fail to initialise, or may lack the
 *     default language;
 *   - the device may have no alarm sound at all;
 *   - the volume may be zero, the phone may be muted, or another app may hold the
 *     audio route.
 *
 * When speech is unavailable the ongoing alert notification stays as the visible
 * fallback, and nothing in the UI is allowed to claim the user heard anything.
 *
 * Threading: TextToSpeech must be created and used from the main thread, so the
 * tone timeout uses a main-looper Handler and every public method is safe to call
 * from the ViewModel on the main thread. Nothing here blocks.
 */
class AndroidAlertAnnouncer(context: Context) : AlertAnnouncer {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Created lazily: the first cue pays the engine start-up cost, not app launch. */
    private var textToSpeech: TextToSpeech? = null
    private var speechReady = false

    /**
     * Speech is only marked unusable after the engine actually reported failure.
     * A cue that arrives while the engine is still starting is remembered and
     * spoken on init instead of being dropped.
     */
    private var speechFailed = false
    private var pendingCue: AlertCue? = null

    private var ringtone: Ringtone? = null
    private var released = false

    private val stopToneRunnable = Runnable { stopTone() }

    override fun announce(cue: AlertCue) {
        if (released) return
        startTone()
        if (speechFailed) return
        if (speechReady) {
            speak(cue)
        } else {
            // One slot only: a second cue before init finishes replaces the first,
            // so an incident can never queue up a backlog of messages.
            pendingCue = cue
            ensureEngine()
        }
    }

    override fun silence() {
        pendingCue = null
        stopTone()
        runCatching { textToSpeech?.stop() }
            .onFailure { error -> Log.w(LogTag, "Could not stop speech", error) }
    }

    override fun release() {
        if (released) return
        released = true
        silence()
        mainHandler.removeCallbacks(stopToneRunnable)
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { textToSpeech?.shutdown() }
            .onFailure { error -> Log.w(LogTag, "Could not shut down speech", error) }
        textToSpeech = null
        speechReady = false
    }

    // ------------------------------------------------------------------
    // Speech
    // ------------------------------------------------------------------

    private fun ensureEngine() {
        if (textToSpeech != null || speechFailed || released) return
        val created = runCatching {
            TextToSpeech(appContext, ::onEngineInitialised)
        }.getOrNull()
        if (created == null) {
            speechFailed = true
            logSpeechUnavailable("the engine could not be created")
            return
        }
        textToSpeech = created
    }

    private fun onEngineInitialised(status: Int) {
        if (released) return
        if (status != TextToSpeech.SUCCESS) {
            speechFailed = true
            logSpeechUnavailable("initialisation failed with status $status")
            return
        }
        val engine = textToSpeech ?: return
        val language = runCatching { engine.setLanguage(Locale.getDefault()) }.getOrNull()
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            // The engine works, it just cannot speak the device language. English
            // remains the safe default for the fixed prototype wording, and a
            // failure there downgrades to no speech rather than no alert.
            val english = runCatching { engine.setLanguage(Locale.US) }.getOrNull()
            if (english == TextToSpeech.LANG_MISSING_DATA || english == TextToSpeech.LANG_NOT_SUPPORTED) {
                speechFailed = true
                logSpeechUnavailable("neither the device language nor English is available")
                return
            }
        }
        runCatching {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
        }.onFailure { error -> Log.w(LogTag, "Could not set speech audio attributes", error) }
        runCatching { engine.setOnUtteranceProgressListener(progressListener) }
        speechReady = true

        val queued = pendingCue
        pendingCue = null
        if (queued != null) speak(queued)
    }

    private fun speak(cue: AlertCue) {
        val engine = textToSpeech ?: return
        val text = AlertMessages.forCue(cue)
        val spoken = runCatching {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, cue.name)
        }.getOrDefault(TextToSpeech.ERROR)
        if (spoken == TextToSpeech.ERROR) {
            logSpeechUnavailable("the engine refused to speak the message")
        }
    }

    /**
     * Speech errors are recorded, never surfaced: the alert notification is what
     * the user still has when the engine cannot talk.
     */
    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) = Unit

        @Deprecated("Deprecated in the platform, still called by older engines.")
        override fun onError(utteranceId: String?) = logSpeechUnavailable("playback failed")

        override fun onError(utteranceId: String?, errorCode: Int) =
            logSpeechUnavailable("playback failed with error $errorCode")
    }

    private fun logSpeechUnavailable(reason: String) {
        // No message text is logged - only the fact that speech could not run.
        Log.w(LogTag, "Spoken alert unavailable: $reason")
    }

    // ------------------------------------------------------------------
    // Alert tone
    // ------------------------------------------------------------------

    private fun startTone() {
        val tone = ringtone ?: createTone() ?: return
        ringtone = tone
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { tone.isLooping = true }
        }
        val started = runCatching { tone.play() }
            .onFailure { error -> Log.w(LogTag, "Could not play the alert tone", error) }
            .isSuccess
        if (!started) return
        // Bounded on purpose: the tone stops on its own even if nothing ever calls
        // silence(), so a missed cancellation cannot ring indefinitely.
        mainHandler.removeCallbacks(stopToneRunnable)
        mainHandler.postDelayed(stopToneRunnable, ToneDurationMillis)
    }

    private fun createTone(): Ringtone? = runCatching {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: return@runCatching null
        RingtoneManager.getRingtone(appContext, uri)?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        }
    }.getOrNull()

    private fun stopTone() {
        mainHandler.removeCallbacks(stopToneRunnable)
        val tone = ringtone ?: return
        runCatching { if (tone.isPlaying) tone.stop() }
            .onFailure { error -> Log.w(LogTag, "Could not stop the alert tone", error) }
    }

    private companion object {
        const val LogTag = "ResQAlert"

        /** Long enough to be noticed, short enough not to ring through the response. */
        const val ToneDurationMillis = 8_000L
    }
}
