package com.lunarvr.math

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Hamilton quaternion (x, y, z, w) — the core of the 3DOF head tracking system.
 * Composition order: a.multiply(b) == a * b (b applied first).
 */
class Quat(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var w: Float = 1f) {

    constructor(src: Quat) : this(src.x, src.y, src.z, src.w)

    fun set(x: Float, y: Float, z: Float, w: Float): Quat {
        this.x = x; this.y = y; this.z = z; this.w = w
        return this
    }

    fun copy(): Quat = Quat(x, y, z, w)

    fun normalize(): Quat {
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 1e-8f) return Quat(0f, 0f, 0f, 1f)
        val s = 1f / n
        return Quat(x * s, y * s, z * s, w * s)
    }

    fun normalizeInPlace(): Quat {
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < 1e-8f) return set(0f, 0f, 0f, 1f)
        val s = 1f / n
        return set(x * s, y * s, z * s, w * s)
    }

    /** this * b */
    fun multiply(b: Quat): Quat = Quat(
        w * b.x + x * b.w + y * b.z - z * b.y,
        w * b.y - x * b.z + y * b.w + z * b.x,
        w * b.z + x * b.y - y * b.x + z * b.w,
        w * b.w - x * b.x - y * b.y - z * b.z
    )

    fun conjugate(): Quat = Quat(-x, -y, -z, w)

    fun conj(): Quat = conjugate()

    fun dot(o: Quat): Float = x * o.x + y * o.y + z * o.z + w * o.w

    /** Rotates vector v by this quaternion. */
    fun rotate(v: Vec3): Vec3 {
        val qv = Vec3(x, y, z)
        val t = qv.cross(v).mul(2f)
        return v.add(t.mul(w)).add(qv.cross(t))
    }

    /** Spherical interpolation. t in [0,1] (values >1 extrapolate, clamped by the caller). */
    fun slerp(b: Quat, t: Float): Quat {
        var bx = b.x; var by = b.y; var bz = b.z; var bw = b.w
        var cosHalf = x * bx + y * by + z * bz + w * bw
        if (cosHalf < 0f) {
            cosHalf = -cosHalf
            bx = -bx; by = -by; bz = -bz; bw = -bw
        }
        if (cosHalf > 0.9995f) {
            val rx = x + (bx - x) * t
            val ry = y + (by - y) * t
            val rz = z + (bz - z) * t
            val rw = w + (bw - w) * t
            return Quat(rx, ry, rz, rw).normalize()
        }
        val halfTheta = acos(cosHalf.coerceIn(-1f, 1f))
        val sinHalf = sin(halfTheta)
        if (sinHalf < 1e-6f) return copy()
        val a = sin((1f - t) * halfTheta) / sinHalf
        val bb = sin(t * halfTheta) / sinHalf
        return Quat(a * x + bb * bx, a * y + bb * by, a * z + bb * bz, a * w + bb * bw).normalize()
    }

    /** Smaller angular distance to o, in radians. */
    fun angleTo(o: Quat): Float {
        val d = abs(dot(o)).coerceIn(0f, 1f)
        return 2f * acos(d)
    }

    override fun toString(): String = "Quat(${x}, ${y}, ${z}, ${w})"

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)

        fun fromAxisAngle(axisX: Float, axisY: Float, axisZ: Float, angleRad: Float): Quat {
            val n = sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ)
            if (n < 1e-8f) return Quat(0f, 0f, 0f, 1f)
            val s = sin(angleRad / 2f) / n
            return Quat(axisX * s, axisY * s, axisZ * s, cos(angleRad / 2f))
        }

        fun fromAxisAngle(axis: Vec3, angleRad: Float): Quat =
            fromAxisAngle(axis.x, axis.y, axis.z, angleRad)

        fun slerp(a: Quat, b: Quat, t: Float): Quat = a.slerp(b, t)
    }
}
