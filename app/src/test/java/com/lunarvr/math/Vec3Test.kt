package com.lunarvr.math

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class Vec3Test {
    private fun assertVec(v: Vec3, x: Double, y: Double, z: Double, msg: String = "") {
        assertEquals("$msg x", x, v.x.toDouble(), 1e-5)
        assertEquals("$msg y", y, v.y.toDouble(), 1e-5)
        assertEquals("$msg z", z, v.z.toDouble(), 1e-5)
    }

    @Test
    fun `length and normalize`() {
        val v = Vec3(3f, 0f, 4f)
        assertEquals(5.0, v.length().toDouble(), 1e-5)
        v.normalize()
        assertVec(v, 0.6, 0.0, 0.8)
    }

    @Test
    fun `dot`() {
        assertEquals(11.0, Vec3(1f, 2f, 3f).dot(Vec3(4f, 5f, 6f)).toDouble(), 1e-5)
    }

    @Test
    fun `cross`() {
        val c = Vec3(1f, 0f, 0f).cross(Vec3(0f, 1f, 0f))
        assertVec(c, 0.0, 0.0, 1.0)
    }

    @Test
    fun `rotate by 90 around Y`() {
        val q = Quat.fromAxisAngle(0f, 1f, 0f, (Math.PI / 2).toFloat())
        val v = q.rotate(Vec3(1f, 0f, 0f))
        assertVec(v, 0.0, 0.0, -1.0)
    }

    @Test
    fun `rotate by 90 around Z`() {
        val q = Quat.fromAxisAngle(0f, 0f, 1f, (Math.PI / 2).toFloat())
        val v = q.rotate(Vec3(1f, 0f, 0f))
        assertVec(v, 0.0, 1.0, 0.0)
    }

    @Test
    fun `rotate preserves length`() {
        val q = Quat.fromAxisAngle(0.3f, 1f, -0.2f, 1.7f)
        val v = q.rotate(Vec3(0.7f, -0.4f, 1.2f))
        assertEquals(1.4457f, v.length().toFloat(), 1e-3)
    }
}
