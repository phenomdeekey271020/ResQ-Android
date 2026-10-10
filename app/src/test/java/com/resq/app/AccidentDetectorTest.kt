package com.resq.app

import com.resq.app.detection.AccidentDetector
import com.resq.app.detection.SimulationSamples
import com.resq.app.model.AccelerometerState
import com.resq.app.model.DetectionAssessment
import com.resq.app.model.DetectionConfig
import com.resq.app.model.DetectionLevel
import com.resq.app.model.LocationAvailability
import com.resq.app.model.LocationState
import com.resq.app.model.SensorAvailability
import com.resq.app.model.SensorState
import com.resq.app.model.confidenceWeightTotal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the prototype detection engine.
 *
 * Every sample is synthetic and deterministic - no physical hardware and no real
 * crash is involved. These tests describe hand-picked heuristics, not validated
 * crash physics.
 */
class AccidentDetectorTest {

    private val config = DetectionConfig()
    private val detector = AccidentDetector(config)

    // ------------------------------------------------------------------
    // C. Normal movement
    // ------------------------------------------------------------------

    @Test
    fun normalMovement_staysNormal() {
        val assessment = feed(SimulationSamples.normal())

        assertEquals(DetectionLevel.NORMAL, assessment.level)
        assertTrue(assessment.confidence < config.possibleConfidence)
        assertFalse(assessment.evidence.accelerationSpike)
        assertFalse(assessment.evidence.significantRotation)
    }

    @Test
    fun stationaryPhone_staysNormal() {
        val assessment = feed(List(10) { index -> accelerometerOnly(0f, 0f, 9.81f, index * 40L) })

        assertEquals(DetectionLevel.NORMAL, assessment.level)
        assertEquals(0, assessment.confidence)
    }

    // ------------------------------------------------------------------
    // D. A single acceleration spike
    // ------------------------------------------------------------------

    @Test
    fun singleAccelerationSpike_isNeverAnAccident() {
        val resting = SimulationSamples.normal(1).first()
        val spike = SimulationSamples.possibleCollision(1).first()

        val assessment = detector.evaluate(resting, resting.accelerometer.timestampMillis)
        val afterSpike = detector.evaluate(spike, spike.accelerometer.timestampMillis)

        // The spike is visible as evidence ...
        assertTrue(afterSpike.evidence.accelerationSpike)
        assertTrue(afterSpike.confidence > 0)
        // ... but one abnormal sample can never become an accident.
        assertEquals(1, afterSpike.evidence.abnormalSamples)
        assertTrue(1 < config.minAbnormalSamplesForAlert)
        assertEquals(DetectionLevel.NORMAL, afterSpike.level)
        assertTrue(afterSpike.reasons.any { it.contains("single spike is ignored") })
    }

    // ------------------------------------------------------------------
    // E. Acceleration plus rotation
    // ------------------------------------------------------------------

    @Test
    fun accelerationWithRotation_scoresHigherThanAccelerationAlone() {
        val withRotation = feed(SimulationSamples.possibleCollision(5))
        val withoutRotation = feed(SimulationSamples.accelerationWithoutGyroscope(5))

        assertTrue(withRotation.confidence > withoutRotation.confidence)
        assertTrue(withRotation.evidence.significantRotation)
        assertFalse(withoutRotation.evidence.significantRotation)
    }

    @Test
    fun rotationWithoutAnAccelerometer_failsSafe() {
        val assessment = feed(SimulationSamples.rotationWithoutAccelerometer(6))

        assertEquals(DetectionLevel.NORMAL, assessment.level)
        assertEquals(0, assessment.confidence)
        assertFalse(assessment.hasSensorData)
    }

    // ------------------------------------------------------------------
    // F. Several abnormal samples
    // ------------------------------------------------------------------

    @Test
    fun repeatedModerateImpact_reachesPossible() {
        val assessment = feed(SimulationSamples.possibleCollision())

        assertEquals(DetectionLevel.POSSIBLE, assessment.level)
        assertTrue(assessment.confidence in config.possibleConfidence until config.highConfidenceConfidence)
    }

    @Test
    fun sustainedViolentImpact_reachesHighConfidence() {
        val assessment = feed(SimulationSamples.highConfidenceCollision())

        assertEquals(DetectionLevel.HIGH_CONFIDENCE, assessment.level)
        assertTrue(assessment.confidence >= config.highConfidenceConfidence)
    }

    @Test
    fun detectionIsDeterministicForTheSameSamples() {
        val first = feed(SimulationSamples.possibleCollision())
        val second = feed(SimulationSamples.possibleCollision())

        assertEquals(first, second)
    }

    // ------------------------------------------------------------------
    // K. Hard braking
    // ------------------------------------------------------------------

    @Test
    fun hardBraking_neverBecomesAnAccident() {
        val assessment = feed(SimulationSamples.hardBraking(12))

        assertEquals(DetectionLevel.NORMAL, assessment.level)
        assertEquals(0, assessment.confidence)
        assertEquals(0, assessment.evidence.abnormalSamples)
    }

    // ------------------------------------------------------------------
    // L. Missing or unavailable sensor data
    // ------------------------------------------------------------------

    @Test
    fun missingAccelerometer_failsSafeWithoutConfidence() {
        val assessment = detector.evaluate(SensorState(), nowMillis = 1_000L)

        assertEquals(DetectionLevel.NORMAL, assessment.level)
        assertEquals(0, assessment.confidence)
        assertFalse(assessment.hasSensorData)
        assertTrue(assessment.reasons.single().contains("Accelerometer"))
    }

    @Test
    fun unavailableGyroscope_doesNotBlockAccelerationEvidence() {
        val withGyro = feed(SimulationSamples.possibleCollision(5))
        val withoutGyro = feed(SimulationSamples.accelerationWithoutGyroscope(5))

        assertEquals(DetectionLevel.POSSIBLE, withGyro.level)
        assertTrue(withoutGyro.confidence > 0)
        assertEquals(DetectionLevel.NORMAL, withoutGyro.level)
    }

    @Test
    fun aLostAccelerometerMidStreamClearsTheWindow() {
        feed(SimulationSamples.highConfidenceCollision(3))

        val missing = detector.evaluate(SensorState(), nowMillis = 1_000L)
        val resumed = feed(SimulationSamples.highConfidenceCollision(8))

        assertEquals(DetectionLevel.NORMAL, missing.level)
        assertEquals(DetectionLevel.HIGH_CONFIDENCE, resumed.level)
    }

    // ------------------------------------------------------------------
    // M. Stale and missing location
    // ------------------------------------------------------------------

    @Test
    fun staleLocationContributesNoEvidence() {
        val assessment = feedWithLocation(
            SimulationSamples.normal(8),
            SimulationSamples.staleLastKnownFix(),
        )

        assertEquals(DetectionLevel.NORMAL, assessment.level)
        assertFalse(assessment.evidence.motionContext)
    }

    @Test
    fun freshMovingFixOnlyAddsContextEvidence() {
        val assessment = feedWithLocation(
            SimulationSamples.possibleCollision(5),
            SimulationSamples.movingAtRoadSpeed(),
        )

        assertTrue(assessment.evidence.motionContext)
        // Context alone never escalates: the level comes from motion + rotation.
        assertEquals(DetectionLevel.POSSIBLE, assessment.level)
    }

    @Test
    fun locationWithSpeedButNoPermissionIsIgnored() {
        val withoutPermission = SimulationSamples.movingAtRoadSpeed().copy(
            availability = LocationAvailability.WAITING_FOR_PERMISSION,
        )
        val assessment = feedWithLocation(SimulationSamples.normal(8), withoutPermission)

        assertFalse(assessment.evidence.motionContext)
        assertEquals(DetectionLevel.NORMAL, assessment.level)
    }

    // ------------------------------------------------------------------
    // False positive safety and temporal behaviour
    // ------------------------------------------------------------------

    @Test
    fun noisyStationarySamples_neverEscalate() {
        val noisy = List(30) { index ->
            val wobble = (index % 5) * 0.05f
            accelerometerOnly(wobble, -wobble, 9.81f + wobble, index * 40L)
        }

        assertEquals(DetectionLevel.NORMAL, feed(noisy).level)
    }

    @Test
    fun twoAbnormalSamplesAreStillNotEnough() {
        val samples = SimulationSamples.highConfidenceCollision(2)
        val assessment = feed(samples)

        assertEquals(2, assessment.evidence.abnormalSamples)
        assertEquals(DetectionLevel.NORMAL, assessment.level)
    }

    @Test
    fun windowIsBoundedByConfiguration() {
        val assessment = feed(List(40) { SimulationSamples.highConfidenceCollision(1).first() })

        assertTrue(assessment.evidence.windowSamples <= config.windowSizeSamples)
    }

    @Test
    fun aLongGapBetweenSamplesStartsAFreshWindow() {
        feed(SimulationSamples.highConfidenceCollision(config.minAbnormalSamplesForAlert))
        val late = SimulationSamples.highConfidenceCollision(1).first().accelerometer.copy(
            timestampMillis = config.maxSampleGapMillis * 10,
        )

        val assessment = detector.evaluate(
            SensorState(accelerometer = late),
            nowMillis = late.timestampMillis,
        )

        assertEquals(1, assessment.evidence.abnormalSamples)
        assertEquals(DetectionLevel.NORMAL, assessment.level)
    }

    @Test
    fun outOfOrderSamplesAreIgnored() {
        feed(SimulationSamples.highConfidenceCollision(4))
        val first = SimulationSamples.highConfidenceCollision(1).first()

        val assessment = detector.evaluate(
            SensorState(accelerometer = first.accelerometer.copy(timestampMillis = 0L)),
            nowMillis = 0L,
        )

        assertEquals(4, assessment.evidence.windowSamples)
    }

    @Test
    fun resetForgetsTheWindow() {
        feed(SimulationSamples.highConfidenceCollision(config.minAbnormalSamplesForAlert))
        detector.reset()

        val next = SimulationSamples.highConfidenceCollision(1).first()
        val assessment = detector.evaluate(next, next.accelerometer.timestampMillis)

        assertEquals(1, assessment.evidence.abnormalSamples)
        assertEquals(DetectionLevel.NORMAL, assessment.level)
    }

    @Test
    fun aCustomConfigRaisesTheBar() {
        val strict = AccidentDetector(config.copy(minAbnormalSamplesForAlert = 6))
        val samples = SimulationSamples.possibleCollision(5)

        var assessment = DetectionAssessment()
        samples.forEach { sample -> assessment = strict.evaluate(sample, 0L) }

        assertEquals(DetectionLevel.NORMAL, assessment.level)
    }

    @Test
    fun assessmentAlwaysExplainsItself() {
        val assessment = feed(SimulationSamples.highConfidenceCollision())

        assertTrue(assessment.reasons.isNotEmpty())
        assertTrue(assessment.reasons.any { it.contains("prototype") })
    }

    @Test
    fun confidenceIsAlwaysInsideTheWeightTotal() {
        val levels = listOf(
            SimulationSamples.normal(),
            SimulationSamples.hardBraking(),
            SimulationSamples.possibleCollision(),
            SimulationSamples.highConfidenceCollision(),
        )

        levels.forEach { samples ->
            val assessment = feed(samples)
            assertTrue(assessment.confidence in 0..config.confidenceWeightTotal)
            assertEquals(assessment.confidence / 100f, assessment.confidenceFraction, 0.0001f)
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Replays a sequence into a fresh window and returns the final verdict. */
    private fun feed(samples: List<SensorState>): DetectionAssessment {
        detector.reset()
        var assessment = DetectionAssessment()
        samples.forEach { sample ->
            assessment = detector.evaluate(sample, sample.accelerometer.timestampMillis)
        }
        return assessment
    }

    private fun feedWithLocation(
        samples: List<SensorState>,
        location: LocationState,
    ): DetectionAssessment =
        feed(samples.map { it.copy(location = location) })

    /** A bare accelerometer reading with no gyroscope, mimicking a partial stream. */
    private fun accelerometerOnly(
        x: Float,
        y: Float,
        z: Float,
        timestampMillis: Long,
    ): SensorState = SensorState(
        accelerometer = AccelerometerState(
            availability = SensorAvailability.READY,
            x = x,
            y = y,
            z = z,
            timestampMillis = timestampMillis,
        ),
    )
}