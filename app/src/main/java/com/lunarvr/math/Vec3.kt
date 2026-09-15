package com.lunarvr.math

import kotlin.math.sqrt

/** Simple 3D vector used across the VR engine. */
class Vec3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {

    constructor(src: Vec3) : this(src.x, src.y, src.z)

    fun set(x: Float, y: Float, z: Float): Vec3 {
        this.x = x; this.y = y; this.z = z
        return this
    }

    fun copy(): Vec3 = Vec3(x, y, z)

    fun add(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)
    fun sub(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
    fun mul(s: Float): Vec3 = Vec3(x * s, y * s, z * s)
    fun addScaled(o: Vec3, s: Float): Vec3 = Vec3(x + o.x * s, y + o.y * s, z + o.z * s)

    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3): Vec3 =
        Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun distanceTo(o: Vec3): Float {
        val dx = x - o.x; val dy = y - o.y; val dz = z - o.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun normalize(): Vec3 {
        val l = length()
        return if (l > 1e-8f) mul(1f / l) else Vec3(0f, 0f, 1f)
    }

    override fun toString(): String = "Vec3(${x}, ${y}, ${z})"

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val ONE = Vec3(1f, 1f, 1f)
        val X_AXIS = Vec3(1f, 0f, 0f)
        val Y_AXIS = Vec3(0f, 1f, 0f)
        val Z_AXIS = Vec3(0f, 0f, 1f)
    }
}
