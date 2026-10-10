package com.resq.app

import com.resq.app.detection.accelerationMagnitude
import com.resq.app.detection.gravityDeviation
import com.resq.app.detection.gyroMagnitude
import com.resq.app.detection.normalizedEvidence
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure arithmetic behind the detection engine. Nothing here touches Android or a
 * physical sensor - the values are synthetic and fixed so the results are exact.
 */
class DetectionMathTest {

    @Test
    fun accelerationMagnitude_restingPhoneIsAboutGravity() {
        assertEquals(9.8f, accelerationMagnitude(0f, 0f, 9.8f), Tolerance)
    }

    @Test
    fun accelerationMagnitude_isZeroForNoReading() {
        assertEquals(0f, accelerationMagnitude(0f, 0f, 0f), Tolerance)
    }

    @Test
    fun accelerationMagnitude_isIndependentOfAxisOrientation() {
        val flat = accelerationMagnitude(0f, 0f, 9.81f)
        val upright = accelerationMagnitude(9.81f, 0f, 0f)
        val sideways = accelerationMagnitude(0f, -9.81f, 0f)

        assertEquals(flat, upright, Tolerance)
        assertEquals(flat, sideways, Tolerance)
    }

    @Test
    fun accelerationMagnitude_usesAllThreeAxes() {
        // 3-4-5 triangle.
        assertEquals(5f, accelerationMagnitude(3f, 4f, 0f), Tolerance)
    }

    @Test
    fun accelerationMagnitude_handlesNegativeAxes() {
        assertEquals(
            accelerationMagnitude(3f, 4f, 0f),
            accelerationMagnitude(-3f, -4f, 0f),
            Tolerance,
        )
    }

    @Test
    fun accelerationMagnitude_doesNotOverflowOnLargeValues() {
        val magnitude = accelerationMagnitude(30000f, 40000f, 50000f)
        assertEquals(70710.68f, magnitude, 1f)
    }

    @Test
    fun gyroMagnitude_isZeroWhenStill() {
        assertEquals(0f, gyroMagnitude(0f, 0f, 0f), Tolerance)
    }

    @Test
    fun gyroMagnitude_isIndependentOfAxisOrientation() {
        assertEquals(2.5f, gyroMagnitude(0f, 2f, 1.5f), Tolerance)
    }

    @Test
    fun gravityDeviation_isZeroAtRestAndSymmetricAroundGravity() {
        assertEquals(0f, gravityDeviation(9.80665f, 9.80665f), Tolerance)
        assertEquals(4.2f, gravityDeviation(14.00665f, 9.80665f), 0.01f)
        assertEquals(4.2f, gravityDeviation(5.60665f, 9.80665f), 0.01f)
    }

    @Test
    fun normalizedEvidence_rampsBetweenStartAndFull() {
        assertEquals(0f, normalizedEvidence(5f, 8f, 25f), Tolerance)
        assertEquals(0.5f, normalizedEvidence(16.5f, 8f, 25f), Tolerance)
        assertEquals(1f, normalizedEvidence(80f, 8f, 25f), Tolerance)
        assertEquals(0f, normalizedEvidence(-5f, 8f, 25f), Tolerance)
    }

    @Test
    fun normalizedEvidence_degenerateRangeDoesNotDivideByZero() {
        assertEquals(1f, normalizedEvidence(6f, 6f, 6f), Tolerance)
        assertEquals(0f, normalizedEvidence(5f, 6f, 6f), Tolerance)
    }

    private companion object {
        const val Tolerance = 0.001f
    }
}