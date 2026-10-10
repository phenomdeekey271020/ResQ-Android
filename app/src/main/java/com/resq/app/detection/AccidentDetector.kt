package com.resq.app.detection

import com.resq.app.model.AccelerometerState
import com.resq.app.model.DetectionAssessment
import com.resq.app.model.DetectionConfig
import com.resq.app.model.DetectionEvidence
import com.resq.app.model.DetectionLevel
import com.resq.app.model.GyroscopeState
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationState
import com.resq.app.model.SensorAvailability
import com.resq.app.model.SensorState
import com.resq.app.model.confidenceWeightTotal
import kotlin.math.roundToInt

/*
 * PROTOTYPE ACCIDENT DETECTION ENGINE.
 *
 * It is a pure function of the samples it is handed plus a sliding window of the
 * recent past: no timers, no Android APIs, no UI, no network, and it can never
 * reach an emergency. The best it can do is return POSSIBLE or HIGH_CONFIDENCE,
 * and it does that only after several independent signals agree.
 *
 * It is a hackathon prototype built from hand-picked heuristics. It is NOT a
 * validated crash detector and its confidence value is not a probability.
 */

/** One scored sample held in the sliding window. */
private data class MotionSample(
    val timestampMillis: Long,
    val accelerationMagnitude: Float,
    val gravityDeviation: Float,
    val gyroMagnitude: Float?,
)

/**
 * Consumes real sensor samples one at a time and produces a [DetectionAssessment]
 * per sample.
 *
 * The class is deliberately stateful (it has to remember a short window) but it
 * stays deterministic: the same samples in the same order always yield the same
 * assessment, which is what makes it testable without hardware.
 */
class AccidentDetector(
    private val config: DetectionConfig = DetectionConfig(),
) {

    private val window = ArrayDeque<MotionSample>()

    /**
     * Scores one sample. [sensors] is the latest full snapshot from the Task 3
     * sensor layer - the detector holds no Android reference of its own.
     *
     * Missing or unavailable channels are handled as evidence that simply does
     * not exist; they never raise the level.
     */
    fun evaluate(sensors: SensorState, nowMillis: Long): DetectionAssessment {
        val accelerometer = sensors.accelerometer
        if (!accelerometer.hasReading) {
            window.clear()
            return assessment(
                level = DetectionLevel.NORMAL,
                nowMillis = nowMillis,
                hasSensorData = false,
                reasons = listOf(noAccelerometerReason(sensors.accelerometer)),
            )
        }

        add(accelerometer, sensors.gyroscope)
        return assess(sensors.location, nowMillis)
    }

    /** Forgets the window. Called on reset and when collection is torn down. */
    fun reset() {
        window.clear()
    }

    // ------------------------------------------------------------------
    // Window management
    // ------------------------------------------------------------------

    private fun add(accelerometer: AccelerometerState, gyroscope: GyroscopeState) {
        val timestamp = accelerometer.timestampMillis
        val previous = window.lastOrNull()
        if (previous != null) {
            if (timestamp < previous.timestampMillis) return // out-of-order sample
            if (timestamp - previous.timestampMillis > config.maxSampleGapMillis) window.clear()
        }
        val magnitude = accelerationMagnitude(accelerometer.x, accelerometer.y, accelerometer.z)
        window.addLast(
            MotionSample(
                timestampMillis = timestamp,
                accelerationMagnitude = magnitude,
                gravityDeviation = gravityDeviation(magnitude, config.standardGravityMetersPerSecond),
                gyroMagnitude = if (gyroscope.hasReading) {
                    gyroMagnitude(gyroscope.x, gyroscope.y, gyroscope.z)
                } else {
                    null
                },
            ),
        )
        while (window.size > config.windowSizeSamples) window.removeFirst()
    }

    // ------------------------------------------------------------------
    // Scoring
    // ------------------------------------------------------------------

    private fun assess(location: LocationState, nowMillis: Long): DetectionAssessment {
        val samples = window.toList()
        val peakDeviation = samples.maxOf { it.gravityDeviation }
        val peakRotation = samples.mapNotNull { it.gyroMagnitude }.maxOrNull() ?: 0f
        val abnormalSamples = samples.count { it.gravityDeviation >= config.abnormalGravityDeviation }
        val motionContext = isMoving(location)

        // Prototype confidence: a transparent weighted sum. Acceleration, rotation
        // and the number of agreeing abnormal samples all contribute; context is a
        // small optional top-up. There is no trained model anywhere in this path.
        val accelerationPoints =
            normalizedEvidence(peakDeviation, config.spikeGravityDeviation, config.saturatedGravityDeviation) *
                config.accelerationEvidenceWeight
        val rotationPoints =
            normalizedEvidence(peakRotation, config.significantRotationRadiansPerSecond, config.saturatedRotationRadiansPerSecond) *
                config.rotationEvidenceWeight
        val sustainedPoints =
            normalizedEvidence(
                abnormalSamples.toFloat(),
                config.minAbnormalSamplesForAlert.toFloat(),
                config.saturatedAbnormalSamples.toFloat(),
            ) * config.sustainedEvidenceWeight
        val contextPoints = if (motionContext) config.contextEvidenceWeight else 0

        val rawPoints = accelerationPoints + rotationPoints + sustainedPoints + contextPoints

        // Without any rotation evidence the raw sum is capped below the alert band.
        // A straight-line deceleration is indistinguishable from a hard brake stop,
        // so acceleration on its own may never raise an accident - see
        // DetectionConfig.maxConfidenceWithoutRotation.
        val cappedPoints = if (rotationPoints <= 0f) {
            minOf(rawPoints, config.maxConfidenceWithoutRotation.toFloat())
        } else {
            rawPoints
        }

        val confidence = cappedPoints
            .roundToInt()
            .coerceIn(0, config.confidenceWeightTotal)

        val level = when {
            // The gate that keeps a single spike - or a single hard brake - from
            // ever becoming an incident. Enough samples must agree first.
            abnormalSamples < config.minAbnormalSamplesForAlert -> DetectionLevel.NORMAL
            confidence >= config.highConfidenceConfidence -> DetectionLevel.HIGH_CONFIDENCE
            confidence >= config.possibleConfidence -> DetectionLevel.POSSIBLE
            else -> DetectionLevel.NORMAL
        }

        val evidence = DetectionEvidence(
            accelerationMagnitude = samples.last().accelerationMagnitude,
            gravityDeviation = peakDeviation,
            gyroMagnitude = peakRotation,
            accelerationSpike = samples.any { it.gravityDeviation >= config.spikeGravityDeviation },
            significantRotation = peakRotation >= config.significantRotationRadiansPerSecond,
            abnormalSamples = abnormalSamples,
            windowSamples = samples.size,
            motionContext = motionContext,
        )

        return assessment(
            level = level,
            confidence = confidence,
            evidence = evidence,
            nowMillis = nowMillis,
            hasSensorData = true,
            reasons = reasons(evidence, level, abnormalSamples),
        )
    }

    /**
     * Only a fresh, reported speed counts as context. A missing, denied or stale
     * fix simply contributes nothing - it can never add or remove confidence on
     * its own.
     */
    private fun isMoving(location: LocationState): Boolean =
        location.availability == LocationAvailability.READY &&
            (location.speedMetersPerSecond ?: 0f) >= config.contextSpeedMetersPerSecond

    // ------------------------------------------------------------------
    // Reasons
    // ------------------------------------------------------------------

    private fun reasons(
        evidence: DetectionEvidence,
        level: DetectionLevel,
        abnormalSamples: Int,
    ): List<String> = buildList {
        add(
            "Gravity deviation ${evidence.gravityDeviation.round1()} m/s² " +
                "over ${evidence.abnormalSamples}/${evidence.windowSamples} samples",
        )
        add(
            if (evidence.accelerationSpike) {
                "Acceleration spike detected (threshold is a prototype heuristic)"
            } else {
                "No acceleration spike"
            },
        )
        add(
            if (evidence.significantRotation) {
                "Significant rotation ${evidence.gyroMagnitude.round1()} rad/s"
            } else {
                "No significant rotation"
            },
        )
        add(if (evidence.motionContext) "Moving, GPS reports speed" else "No reliable speed context")
        if (abnormalSamples < config.minAbnormalSamplesForAlert) {
            add(
                "Needs ${config.minAbnormalSamplesForAlert} abnormal samples before an " +
                    "accident can be reported - a single spike is ignored",
            )
        }
        add("Level $level from prototype heuristics, not a validated detector")
    }

    private fun noAccelerometerReason(accelerometer: AccelerometerState): String =
        when (accelerometer.availability) {
            SensorAvailability.READY -> "No accelerometer sample yet"
            SensorAvailability.WAITING ->
                "Accelerometer is still warming up - detection inactive"
            SensorAvailability.UNAVAILABLE ->
                "No accelerometer on this device - detection inactive"
        }

    private fun assessment(
        level: DetectionLevel,
        confidence: Int = 0,
        evidence: DetectionEvidence = DetectionEvidence(),
        nowMillis: Long,
        hasSensorData: Boolean,
        reasons: List<String>,
    ) = DetectionAssessment(
        level = level,
        confidence = confidence,
        evidence = evidence,
        reasons = reasons,
        hasSensorData = hasSensorData,
        timestampMillis = nowMillis,
    )
}

private fun Float.round1(): Float = (this * 10f).roundToInt() / 10f