package com.resq.app.model

/*
 * The read-only verdict of the accident detection engine for one window of
 * sensor samples.
 *
 * [confidence] is a PROTOTYPE CONFIDENCE on a 0-100 scale. It is an explainable
 * weighted sum of the listed evidence, not a probability and not an output of a
 * trained model. Never render it as "87% probability of accident" - label it
 * "prototype confidence" so nobody treats it as a validated certainty.
 */

/** One sample of evidence inside the sliding window. */
data class DetectionEvidence(
    val accelerationMagnitude: Float = 0f,
    val gravityDeviation: Float = 0f,
    val gyroMagnitude: Float = 0f,
    val accelerationSpike: Boolean = false,
    val significantRotation: Boolean = false,
    val abnormalSamples: Int = 0,
    val windowSamples: Int = 0,
    val motionContext: Boolean = false,
)

/**
 * Immutable result of scoring a window.
 *
 * [reasons] is the human-readable audit trail of why this verdict was reached;
 * it is intentionally shown by Diagnostics so a reviewer can see the engine
 * thinking without reading the source.
 */
data class DetectionAssessment(
    val level: DetectionLevel = DetectionLevel.NORMAL,
    val confidence: Int = 0,
    val evidence: DetectionEvidence = DetectionEvidence(),
    val reasons: List<String> = emptyList(),
    val hasSensorData: Boolean = false,
    val timestampMillis: Long = NoSensorTimestamp,
) {
    /** The same prototype confidence expressed the way the score tile shows it. */
    val confidenceFraction: Float get() = confidence / 100f
}

/** Short caption for the prototype confidence, e.g. "Prototype confidence 72/100". */
val DetectionAssessment.confidenceLabel: String
    get() = "Prototype confidence $confidence/100"

/**
 * Flattened evidence strings for diagnostics. Order is fixed so the output is
 * deterministic and diffable.
 */
val DetectionAssessment.reasonSummary: String
    get() = if (reasons.isEmpty()) "No evidence" else reasons.joinToString("; ")