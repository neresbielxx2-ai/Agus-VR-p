package com.lunarvr.hand

import com.lunarvr.math.Quat
import com.lunarvr.math.Vec3
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Per-hand state: converts the 21 MediaPipe landmarks into a smoothed 3D
 * pose in the virtual world, and detects gestures:
 *
 *  - POINT : only the index finger extended -> a VR Ray is spawned from the
 *            index fingertip
 *  - PINCH : thumb + index tips together    -> click / selection
 *  - OPEN / FIST: tracked for status
 *
 * Depth is estimated from the apparent hand size (a hand ~8.5 cm wide at
 * MCP level) — no depth sensor, no physical positioning.
 */
class HandPose(val slot: Int) {

    /** World-space landmarks (render-thread only). */
    val landmarks = Array(21) { Vec3() }

    @Volatile var visible: Boolean = false
    var lastSeenMs: Long = 0
    var slotHandedness: Int = slot // 0 left, 1 right

    // Gestures
    var isPoint = false
    var isOpen = false
    var isFist = false
    var pinching = false
    var pinchAmount = 0f
    /** Edge trigger, consumed once by the interaction manager. */
    var pinchStart = false
    private var prevPinching = false
    private var pinchFrames = 0
    private var cooldownUntil = 0L

    // Eye-geometry used to unproject
    @Volatile var hFovRad: Float = 1.0f
    @Volatile var vFovRad: Float = 0.8f
    @Volatile var pinchThresholdNorm: Float = 0.055f
    @Volatile var handSmoothing: Float = 0.4f

    private val smoothed = Array(21) { Vec3() }
    private var smoothInit = false
    private var depth = 0.5f

    val wrist: Vec3 get() = landmarks[0]
    val indexTip: Vec3 get() = landmarks[8]
    val indexPip: Vec3 get() = landmarks[6]
    val thumbTip: Vec3 get() = landmarks[4]

    fun expire(nowMs: Long) {
        if (nowMs - lastSeenMs > 450) {
            if (visible) {
                visible = false
                isPoint = false
                pinching = false
            }
        }
    }

    /**
     * @param data 21*(x,y,z) from MediaPipe (normalized)
     * @param cameraQ current rendered camera orientation (WorldRoot frame)
     * @param vFovY vertical FOV radians, aspect screen aspect
     */
    fun update(
        data: FloatArray,
        cameraQ: Quat,
        vFovY: Float,
        aspect: Float,
        dt: Float,
        nowMs: Long
    ) {
        // ---- depth estimate from apparent hand width (index MCP -> pinky MCP)
        val x5 = data[5 * 3]; val y5 = data[5 * 3 + 1]
        val x17 = data[17 * 3]; val y17 = data[17 * 3 + 1]
        val wNorm = maxOf(abs(x17 - x5), 0.02f)
        val vHalf = tan(vFovY.toDouble() * 0.5).toFloat()
        val hHalf = vHalf * aspect
        // physical width ~ 0.085 m : d = 0.085 / (wNorm * 2 * hHalf)
        val dNew = (0.085f / (wNorm * 2f * hHalf)).coerceIn(0.28f, 1.15f)
        depth = depth + (dNew - depth) * 0.35f

        val tanH = hHalf
        val tanV = vHalf
        val kHand = 1f - exp(-dt.toDouble() / (0.015 + handSmoothing * 0.16)).toFloat()

        for (i in 0 until 21) {
            val nx = data[i * 3]
            val ny = data[i * 3 + 1]
            val nz = data[i * 3 + 2]
            // camera space (looking down -Z)
            val cx = (2f * nx - 1f) * tanH * depth
            val cy = (1f - 2f * ny) * tanV * depth
            val cz = -depth + nz * depth * 0.9f
            val p = Vec3(cx, cy, cz)
            val world = cameraQ.rotate(p)
            if (!smoothInit) {
                smoothed[i].set(world.x, world.y, world.z)
                smoothInit = true
            } else {
                smoothed[i].x += (world.x - smoothed[i].x) * kHand
                smoothed[i].y += (world.y - smoothed[i].y) * kHand
                smoothed[i].z += (world.z - smoothed[i].z) * kHand
            }
            landmarks[i].set(smoothed[i].x, smoothed[i].y, smoothed[i].z)
        }
        visible = true
        lastSeenMs = nowMs

        // ---- gestures -------------------------------------------------
        val wristP = landmarks[0]
        val extIndex = fingerExtended(5, 8, wristP)
        val extMiddle = fingerExtended(9, 12, wristP)
        val extRing = fingerExtended(13, 16, wristP)
        val extPinky = fingerExtended(17, 20, wristP)
        isPoint = extIndex && !extMiddle && !extRing && !extPinky
        val extendedCount = (if (extIndex) 1 else 0) + (if (extMiddle) 1 else 0) +
            (if (extRing) 1 else 0) + (if (extPinky) 1 else 0)
        isOpen = extendedCount >= 4
        isFist = extendedCount == 0

        // pinch: physical distance between thumb tip and index tip,
        // compared against the user threshold expressed in image units
        val dist = landmarks[4].distanceTo(landmarks[8])
        val threshM = pinchThresholdNorm * 2f * hHalf * depth
        pinchAmount = (1f - (dist / (threshM * 1.9f))).coerceIn(0f, 1f)
        val active = dist < threshM
        if (active) pinchFrames++ else pinchFrames = 0
        val wasPinching = pinching
        pinching = pinchFrames >= 3
        pinchStart = false
        if (pinching && !wasPinching && nowMs >= cooldownUntil) {
            pinchStart = true
            cooldownUntil = nowMs + 380
        }
        if (!pinching) pinchFrames = 0
        prevPinching = pinching
    }

    private fun fingerExtended(mcp: Int, tip: Int, wristP: Vec3): Boolean {
        val dTip = landmarks[tip].distanceTo(wristP)
        val dMcp = landmarks[mcp].distanceTo(wristP)
        if (dMcp < 1e-4f) return false
        return dTip > dMcp * 1.22f
    }
}
