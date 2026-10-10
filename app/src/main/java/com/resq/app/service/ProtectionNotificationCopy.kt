package com.resq.app.service

import com.resq.app.detection.AccidentFlowState
import com.resq.app.protection.ProtectionSnapshot

/*
 * Truthful wording for the ongoing foreground-service notification.
 *
 * Kept as a pure function of the current [ProtectionSnapshot] so it can be unit
 * tested without Android, and so a reviewer can audit every sentence the app
 * can say about an incident in one place.
 *
 * Rules enforced here:
 *  - while nothing is wrong the notification only claims that ResQ is monitoring;
 *  - "possible incident" is only ever used when the prototype detector actually
 *    reported POSSIBLE or HIGH_CONFIDENCE;
 *  - nothing here ever claims that an emergency service, hospital or contact was
 *    contacted - ResQ does not dispatch anything.
 */

/** Title and body shown in the ongoing protection notification. */
data class ProtectionNotificationCopy(val title: String, val text: String)

/**
 * Copy for the high-importance incident notification, or null when no incident
 * notification should exist.
 *
 * This is the visible fallback for the audible alert: speech can fail (no TTS
 * engine, no audio route, volume at zero, the phone muted), so an unresolved
 * incident always also produces a notification. The wording is the same as the
 * ongoing notification's, taken from the same function, so the two can never
 * disagree about what is happening.
 */
fun incidentAlertCopy(snapshot: ProtectionSnapshot): ProtectionNotificationCopy? = when {
    !snapshot.monitoring -> null
    snapshot.flow == AccidentFlowState.Normal -> null
    else -> protectionNotificationCopy(snapshot)
}

/** Text for [snapshot], or null when no protection notification should exist. */
fun protectionNotificationCopy(snapshot: ProtectionSnapshot): ProtectionNotificationCopy? = when {
    !snapshot.monitoring -> null

    snapshot.flow is AccidentFlowState.Emergency -> ProtectionNotificationCopy(
        title = "ResQ incident - open the app",
        text = "The emergency screen is open in ResQ. Nothing has been called or sent.",
    )

    snapshot.flow == AccidentFlowState.Confirmation -> ProtectionNotificationCopy(
        title = "ResQ - please confirm you are safe",
        text = "A possible incident was detected and no answer was given.",
    )

    snapshot.flow is AccidentFlowState.PossibleAccident -> ProtectionNotificationCopy(
        title = "ResQ - possible incident detected",
        text = "The prototype detector flagged a possible impact. Open ResQ.",
    )

    else -> ProtectionNotificationCopy(
        title = "ResQ Protection Active",
        text = "Monitoring for possible incidents",
    )
}