package com.resq.app.model

/**
 * Incident lifecycle.
 *
 * NONE -> no alert on screen.
 * POSSIBLE_IMPACT -> an impact was detected and the response countdown runs.
 * CONFIRMATION -> the window closed without an answer; safety is unconfirmed.
 */
sealed interface IncidentState {

    data object None : IncidentState

    data class PossibleImpact(
        val secondsRemaining: Int,
        val confidence: DetectionConfidence,
        /**
         * The full length of this countdown, so the UI ring can draw the real
         * proportion. Defaults to the per-level window; the ViewModel overrides
         * it when the user configured a flat alert countdown in Settings,
         * otherwise a 60 s window would render as a ring pinned at 100 % for
         * fifty seconds and then drain only over the last ten.
         */
        val totalSeconds: Int = confidence.countdownSeconds,
    ) : IncidentState

    data object Confirmation : IncidentState
}

/** Length of the "are you okay?" window for a given detection confidence. */
val DetectionConfidence.countdownSeconds: Int
    get() = when (this) {
        DetectionConfidence.POSSIBLE -> 10
        DetectionConfidence.HIGH -> 5
    }
