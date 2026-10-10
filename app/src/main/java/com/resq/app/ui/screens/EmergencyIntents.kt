package com.resq.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.resq.app.contacts.SystemCallLauncher

/*
 * Small, side-effect-free-ish helpers that turn emergency state into an Android
 * intent. Kept out of the composables so the UI stays declarative and the URL
 * construction can be unit tested without a device.
 *
 * Neither helper invents data: a call needs a real provider number, and
 * directions need a real resource position. When either is missing the action is
 * simply not offered by the screen.
 */

/**
 * Opens the system dialer with the given number. ACTION_DIAL never places a
 * call on its own - the user still has to press call. Numbers come from the
 * existing state: a phone number returned with a resource, or the regional
 * emergency number. A resource without a returned number gets no call action
 * at all.
 */
fun dialNumber(context: Context, number: String) {
    // One implementation only: the same launcher the automatic escalation path
    // uses, so a user-tapped call and an escalation-opened one behave identically.
    //
    // ACTION_DIAL, never ACTION_CALL, on this path. The user is present and
    // already chose to make this call, so there is nothing to gain from taking
    // the confirmation away from them - and it keeps the manual actions working
    // even when CALL_PHONE has been denied.
    SystemCallLauncher(context).openDialer(number)
}

/** The Google Maps app package, tried first when installed. */
private const val GoogleMapsPackage = "com.google.android.apps.maps"

/**
 * Builds the Google Maps directions URL for the DESTINATION coordinates.
 *
 * Format: `https://www.google.com/maps/dir/?api=1&destination=LATITUDE,LONGITUDE`
 *
 * Returns null whenever a trustworthy destination cannot be built, which is the
 * signal for the UI to hide/disable Directions instead of guessing:
 *  - either coordinate missing,
 *  - a non-finite number (NaN / Infinity),
 *  - a value outside the valid latitude/longitude range,
 *  - the (0, 0) sentinel that providers use for "no position".
 *
 * Pure Kotlin: no Android types, so it is covered by plain JVM unit tests and
 * the UI can ask it whether a destination exists at all.
 */
internal fun directionsUrlFor(latitude: Double?, longitude: Double?): String? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    if (!lat.isFinite() || !lon.isFinite()) return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    if (lat == 0.0 && lon == 0.0) return null
    return "https://www.google.com/maps/dir/?api=1&destination=$lat,$lon"
}

/**
 * Opens turn-by-turn directions to the resource's own coordinates.
 *
 * The URL above is an https Google Maps link, so it is also the browser
 * fallback. Google Maps is preferred when it is installed (an explicit package
 * intent), and anything that can handle the link - a browser - is used
 * otherwise. Launch failures are swallowed rather than crashing the emergency
 * screen.
 *
 * @return true when an activity was launched, false when no destination could be
 *   built or nothing on the device could open it. The caller uses this only for
 *   honesty in the UI; the user's own position is never used as a destination.
 */
fun openDirections(context: Context, latitude: Double?, longitude: Double?): Boolean {
    val url = directionsUrlFor(latitude, longitude) ?: return false
    val uri = Uri.parse(url)
    return launchMapsThenFallback { packageName: String? ->
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (packageName != null) intent.setPackage(packageName)
        launchView(context, intent)
    }
}

/**
 * The launch order, split out from Android so it is unit testable: Google Maps
 * first, then any viewer (a browser) when Maps is missing or refuses the link.
 * Returns true as soon as one attempt succeeds and never tries more than one
 * after success.
 */
internal inline fun launchMapsThenFallback(attempt: (String?) -> Boolean): Boolean =
    attempt(GoogleMapsPackage) || attempt(null)

private fun launchView(context: Context, intent: Intent): Boolean {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: RuntimeException) {
        // OEM builds have been seen to throw something other than
        // ActivityNotFoundException out of startActivity for an unresolvable
        // link. A failed directions action must never crash the emergency
        // screen; it degrades to the same honest "nothing could open it" signal.
        false
    }
}

/**
 * Tells the user, once, that nothing on this device could open the directions
 * link. Without it a failed launch is a silent no-op in the middle of an
 * emergency, which reads as a broken button rather than a missing app.
 */
fun showDirectionsUnavailableToast(context: Context) {
    android.widget.Toast.makeText(
        context,
        "No app available to show directions",
        android.widget.Toast.LENGTH_LONG,
    ).show()
}
