package com.resq.app.detection

import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Pure, Android-free helpers for turning three sensor axes into the two numbers
 * the detector actually reasons about. No thresholds live here - every one of
 * them belongs to DetectionConfig.
 */

/** Euclidean magnitude of an accelerometer sample in m/s², gravity included. */
fun accelerationMagnitude(x: Float, y: Float, z: Float): Float = magnitude(x, y, z)

/** Euclidean magnitude of a gyroscope sample in rad/s. */
fun gyroMagnitude(x: Float, y: Float, z: Float): Float = magnitude(x, y, z)

/**
 * Magnitude of a three axis vector. Accumulated in Double so a long window of
 * samples cannot drift; the sensor values themselves are already noisy Float.
 */
private fun magnitude(x: Float, y: Float, z: Float): Float {
    val dx = x.toDouble()
    val dy = y.toDouble()
    val dz = z.toDouble()
    return sqrt(dx * dx + dy * dy + dz * dz).toFloat()
}

/**
 * How far a measured magnitude sits from the resting gravity magnitude.
 *
 * A phone lying flat reads ~9.81 m/s²; a free fall reads ~0. Both are equally
 * far from the calibrated neutral point, which is why the deviation is signed
 * out and taken as an absolute distance.
 */
fun gravityDeviation(acceleration: Float, standardGravity: Float): Float =
    abs(acceleration - standardGravity)

/**
 * Maps [value] linearly onto 0..1 between [start] (zero) and [full] (one).
 * Used to turn a raw magnitude into a comparable fraction of evidence.
 */
fun normalizedEvidence(value: Float, start: Float, full: Float): Float {
    if (full <= start) return if (value >= full) 1f else 0f
    return ((value - start) / (full - start)).coerceIn(0f, 1f)
}