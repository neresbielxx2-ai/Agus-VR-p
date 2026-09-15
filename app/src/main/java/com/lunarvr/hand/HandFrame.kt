package com.lunarvr.hand

/**
 * Immutable snapshot of one MediaPipe detection result.
 * Each hand: 21 landmarks as flat (x, y, z) triples — x/y normalized image
 * coordinates (0..1), z relative depth in the same normalized units.
 * Handedness: 0 = user's LEFT hand, 1 = user's RIGHT hand
 * (front-camera mirror mapping applied by HandTracker).
 */
class HandFrame(
    val hands: List<FloatArray>,
    val handedness: IntArray,
    val timeMs: Long
)
