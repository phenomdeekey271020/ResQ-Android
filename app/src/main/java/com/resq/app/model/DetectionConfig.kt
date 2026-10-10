package com.resq.app.model

/*
 * EVERY prototype heuristic used by the accident detection engine lives here.
 *
 * WARNING - these numbers are PROTOTYPE HEURISTICS for a hackathon demo. They
 * were chosen by hand so the behaviour is easy to reason about, explain and
 * tune on a desk. They are NOT validated crash thresholds, they are NOT derived
 * from crash-test data, and they carry no scientific meaning whatsoever. Never
 * present them as medically or automotive validated, and never let a value from
 * here be interpreted as a real probability.
 *
 * Keeping them in one data class (instead of scattering literals through the
 * detector) means a test can swap in a stricter or looser set without touching
 * the algorithm, and a reviewer can audit the whole decision surface at once.
 */
data class DetectionConfig(

    /**
     * PROTOTYPE HEURISTIC - the reference gravity the accelerometer is compared
     * against. At rest the sensor measures ~9.8 m/s², so this is the neutral
     * point of the acceleration evidence.
     */
    val standardGravityMetersPerSecond: Float = 9.80665f,

    // ------------------------------------------------------------------
    // Temporal window
    // ------------------------------------------------------------------

    /**
     * PROTOTYPE HEURISTIC - how many recent samples are remembered. Large
     * enough to require agreement between samples, small enough to stay inside
     * the sampling rate Android delivers at SENSOR_DELAY_UI.
     */
    val windowSizeSamples: Int = 12,

    /**
     * PROTOTYPE HEURISTIC - a gap longer than this starts a fresh window. It
     * stops evidence from unrelated moments minutes apart being summed together
     * after the app was backgrounded or a sensor was restarted.
     */
    val maxSampleGapMillis: Long = 750L,

    // ------------------------------------------------------------------
    // Acceleration evidence
    // ------------------------------------------------------------------

    /**
     * PROTOTYPE HEURISTIC - deviation from gravity (in m/s²) at which a single
     * sample is called an acceleration spike. Roughly 0.8 g of deviation.
     * A spike alone is evidence only - it can never raise the detection level.
     */
    val spikeGravityDeviation: Float = 8.0f,

    /**
     * PROTOTYPE HEURISTIC - deviation from gravity at which the acceleration
     * evidence is saturated. Roughly 2.5 g of deviation.
     */
    val saturatedGravityDeviation: Float = 25.0f,

    /**
     * PROTOTYPE HEURISTIC - deviation (in m/s²) at which a sample is counted as
     * "abnormal" for the sustained-evidence term. Roughly 0.7 g, deliberately
     * above the deceleration of an ordinary hard brake from a seated phone.
     */
    val abnormalGravityDeviation: Float = 7.0f,

    // ------------------------------------------------------------------
    // Rotation evidence
    // ------------------------------------------------------------------

    /**
     * PROTOTYPE HEURISTIC - angular rate (rad/s) at which rotation counts as
     * significant. Handheld walking produces a small fraction of this.
     */
    val significantRotationRadiansPerSecond: Float = 1.5f,

/**
     * PROTOTYPE HEURISTIC - angular rate at which the rotation evidence is
     * saturated, i.e. a full violent tumble of the device.
     */
    val saturatedRotationRadiansPerSecond: Float = 6.0f,

    /**
     * PROTOTYPE HEURISTIC - ceiling applied when no rotation evidence is present.
     *
     * This is the single most important false-positive guard in the engine: a
     * straight-line deceleration looks identical whether it is a collision or a
     * panicked brake stop, so acceleration alone is never allowed to reach the
     * alert bands. Set it below [possibleConfidence] to keep that rule.
     */
    val maxConfidenceWithoutRotation: Int = 44,

    // ------------------------------------------------------------------
    // Sustained evidence
    // ------------------------------------------------------------------

    /**
     * PROTOTYPE HEURISTIC - number of consecutive abnormal samples in the
     * window before the engine is even allowed to leave NORMAL. This is the rule
     * that stops one noisy sample from becoming a collision.
     */
    val minAbnormalSamplesForAlert: Int = 3,

    /**
     * PROTOTYPE HEURISTIC - number of abnormal samples at which the sustained
     * evidence is saturated.
     */
    val saturatedAbnormalSamples: Int = 8,

    // ------------------------------------------------------------------
    // Context evidence (optional, never decisive on its own)
    // ------------------------------------------------------------------

    /**
     * PROTOTYPE HEURISTIC - ground speed (m/s) above which "the device was in a
     * vehicle" is assumed for the context term. About 29 km/h. Location is only
     * consulted when a fresh, non-stale fix reports it.
     */
    val contextSpeedMetersPerSecond: Float = 8.0f,

    // ------------------------------------------------------------------
    // Prototype confidence weights (must sum to 100)
    // ------------------------------------------------------------------

    val accelerationEvidenceWeight: Int = 45,
    val rotationEvidenceWeight: Int = 25,
    val sustainedEvidenceWeight: Int = 20,
    val contextEvidenceWeight: Int = 10,

    // ------------------------------------------------------------------
    // Prototype confidence bands
    // ------------------------------------------------------------------

    /**
     * PROTOTYPE HEURISTIC - prototype confidence at or above which a window that
     * has enough abnormal samples is called POSSIBLE.
     */
    val possibleConfidence: Int = 45,

    /**
     * PROTOTYPE HEURISTIC - prototype confidence at or above which a window that
     * has enough abnormal samples is called HIGH_CONFIDENCE.
     */
    val highConfidenceConfidence: Int = 70,

    // ------------------------------------------------------------------
    // Flow behaviour
    // ------------------------------------------------------------------

    /**
     * PROTOTYPE HEURISTIC - quiet period after an incident is resolved before
     * detection can raise another one, so one crash is not counted twice.
     */
    val cooldownMillis: Long = 5_000L,

    /**
     * How long the unconfirmed-safety window stays open before ResQ escalates by
     * itself, in seconds.
     *
     * This is the second timeout of the incident: the "are you okay?" countdown
     * ([alertCountdownSeconds], or the per-level 10 s / 5 s) only decides that no
     * answer came. This one decides that the absence of an answer is itself the
     * emergency, so the flow never waits indefinitely on a screen nobody is
     * looking at. The user can still cancel the whole thing from that screen.
     */
    val confirmationWindowSeconds: Int = 10,

    /**
     * PROTOTYPE HEURISTIC - the score the Diagnostics tile shows for the
     * simulated hard-braking control. Presentation only; the hard-braking
     * samples themselves are evaluated by the same detector as real data and
     * deliberately do NOT reach an alert level.
     */
    val hardBrakingDisplayScore: Float = 0.46f,

    /**
     * Optional flat override for the "are you okay?" alert window. When unset
     * the flow uses the per-level values (10 s for POSSIBLE, 5 s for HIGH),
     * so existing behaviour and tests are unchanged.
     */
    var alertCountdownSeconds: Int? = null,
)

/** Total of every confidence weight. Used to document the scoring formula. */
val DetectionConfig.confidenceWeightTotal: Int
    get() = accelerationEvidenceWeight +
        rotationEvidenceWeight +
        sustainedEvidenceWeight +
        contextEvidenceWeight