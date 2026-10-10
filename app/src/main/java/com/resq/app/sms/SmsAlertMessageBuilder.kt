package com.resq.app.sms

import com.resq.app.model.EmergencyLocationSnapshot
import com.resq.app.model.FixFreshness
import com.resq.app.model.LocationFixCachePolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * THE TEXT OF THE ONE MESSAGE RESQ SENDS. Pure Kotlin, no Android.
 *
 * It is a separate file so every rule below can be unit tested without a radio:
 *
 *  - what the recipient needs to know: WHAT happened, WHEN it happened, and WHAT
 *    TO DO. A message that says only "ResQ detected an accident" is useless to
 *    someone deciding whether to call an ambulance.
 *
 *  - the location rules, which are the safety-critical ones:
 *      1. a link is included ONLY when real, plausible coordinates exist. There
 *         is no default position and no (0, 0) fallback;
 *      2. a fresh fix is labelled as current;
 *      3. a cached or aged fix is labelled as LAST KNOWN, with its age, and
 *         never as where the user is now;
 *      4. no usable position means NO location line at all - the alert is still
 *         sent. A missing coordinate must never delay an emergency message.
 *
 *  - the message states plainly that the user may be unable to respond, which is
 *    what the recipient needs in order to act rather than wait for a call-back.
 *
 * Phone numbers and coordinates appear in the message body by design - it is
 * addressed to a person the user chose - but none of this is ever logged.
 */
object SmsAlertMessageBuilder {

    /** Rounded to whole degrees - far finer than any phone's GPS error. */
    private const val CoordinatePrecision = 5

    /**
     * The map link ResQ embeds.
     *
     * A plain `https://maps.google.com/?q=` link works from any phone's browser,
     * needs no app installed and no key. It reveals nothing about the user to a
     * third party unless the recipient actually opens it.
     */
    private const val MapLinkFormat = "https://maps.google.com/?q=%.5f,%.5f"

    /**
     * Builds the body for one incident.
     *
     * @param incidentType short, non-alarming description of what was detected.
     * @param incidentAtMillis when the incident was raised; formatted locally.
     * @param location the best position available, or null. Never required.
     */
    fun build(
        incidentType: String,
        incidentAtMillis: Long,
        location: EmergencyLocationSnapshot?,
    ): String = buildString {
        appendLine("ResQ alert: $incidentType")
        appendLine("Detected at ${formatTime(incidentAtMillis)}.")
        appendLine("The person may be unable to respond. Please contact them and, " +
            "if you are concerned, call the local emergency services.")
        append("If they do not answer, check on them in person.")
        appendLine()
        val position = locationLine(location)
        if (position != null) {
            appendLine()
            appendLine(position)
        }
    }

    /**
     * The location paragraph, or null when there is nothing honest to say.
     *
     * The plausibility check is repeated here, not just relied upon upstream: this
     * is the last point before a possibly-wrong coordinate is put in front of a
     * human who may act on it, so the (0, 0) sentinel is rejected here too.
     */
    internal fun locationLine(location: EmergencyLocationSnapshot?): String? {
        val latitude = location?.latitude ?: return null
        val longitude = location?.longitude ?: return null
        if (!LocationFixCachePolicy.isPlausiblePosition(latitude, longitude)) return null
        val link = String.format(Locale.US, MapLinkFormat, latitude, longitude)
        return when (location.freshness) {
            FixFreshness.FRESH -> "Location (current): $link"
            FixFreshness.STALE -> {
                // Historical context only. The wording must make it impossible to
                // read as "the person is here right now".
                val age = describeAge(location.ageMillis)
                "Last known location, $age - may be out of date, not the current position: $link"
            }

            FixFreshness.NO_FIX -> null
        }
    }

    /** Human age of a historical fix, or an honest "unknown" when there is none. */
    private fun describeAge(ageMillis: Long?): String {
        val age = ageMillis ?: return "time unknown"
        if (age < 0) return "time unknown"
        val minutes = age / 60_000L
        return when {
            minutes < 1 -> "seconds ago"
            minutes < 60 -> "$minutes min ago"
            else -> "${minutes / 60} h ago"
        }
    }

    /**
     * Local wall-clock time of the incident.
     *
     * `java.time` would be tidier but needs API 26 or desugaring; this app has
     * minSdk 24, so [SimpleDateFormat] is the compatible choice. Locale.US with
     * an explicit 24-hour pattern so the string is unambiguous for a recipient
     * anywhere in the world.
     */
    private fun formatTime(timestampMillis: Long): String =
        SimpleDateFormat("d MMM yyyy 'at' HH:mm", Locale.US).format(Date(timestampMillis))
}