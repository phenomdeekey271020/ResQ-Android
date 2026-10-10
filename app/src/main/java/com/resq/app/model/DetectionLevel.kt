package com.resq.app.model

/**
 * What the detection engine believes is happening, based only on the evidence it
 * has scored. This is deliberately NOT an incident: the engine can never raise
 * an emergency, it only describes how strongly the signals agree.
 */
enum class DetectionLevel {
    NORMAL,
    POSSIBLE,
    HIGH_CONFIDENCE,
}

/** Diagnostics caption for a detection level. */
val DetectionLevel.label: String
    get() = when (this) {
        DetectionLevel.NORMAL -> "Normal"
        DetectionLevel.POSSIBLE -> "Possible accident"
        DetectionLevel.HIGH_CONFIDENCE -> "High confidence accident"
    }

/**
 * Maps the engine's verdict onto the confidence the incident countdown already
 * understands, so no second confidence type is introduced.
 */
val DetectionLevel.confidence: DetectionConfidence?
    get() = when (this) {
        DetectionLevel.NORMAL -> null
        DetectionLevel.POSSIBLE -> DetectionConfidence.POSSIBLE
        DetectionLevel.HIGH_CONFIDENCE -> DetectionConfidence.HIGH
    }