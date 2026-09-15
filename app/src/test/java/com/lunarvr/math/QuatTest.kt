package com.lunarvr.math

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class QuatTest {
    private fun near(a: Double, b: Double, msg: String) =
        assertEquals(msg, b, a, 1e-4)

    @Test
    fun `identity leaves vectors unchanged`() {
        val v = Quat.IDENTITY.rotate(Vec3(0.5f, -0.7f, 0.4f))
        near(0.5, v.x.toDouble(), "x"); near(-0.7, v.y.toDouble(), "y"); near(0.4, v.z.toDouble(), "z")
    }

    @Test
    fun `fromAxisAngle 90deg Y matches rotation matrix`() {
        val q = Quat.fromAxisAngle(0f, 1f, 0f, (Math.PI / 2).toFloat())
        // q = (0, s/2*axis, 0, c/2) with axis=(0,1,0)
        near(0.0, q.x.toDouble(), "x"); near(sin(Math.PI / 4).toDouble(), q.y.toDouble(), "y")
        near(0.0, q.z.toDouble(), "z"); near(cos(Math.PI / 4).toDouble(), q.w.toDouble(), "w")
    }

    @Test
    fun `conjugate inverts rotation`() {
        val q = Quat.fromAxisAngle(0.1f, 0.8f, 0.3f, 0.9f)
        val r = q.multiply(q.conj()).normalize()
        near(0.0, r.x.toDouble(), "x"); near(0.0, r.y.toDouble(), "y")
        near(0.0, r.z.toDouble(), "z"); near(1.0, r.w.toDouble(), "w")
    }

    @Test
    fun `slerp half way is halfway rotation`() {
        val a = Quat.IDENTITY
        val b = Quat.fromAxisAngle(0f, 0f, 1f, (Math.PI / 2).toFloat())
        val mid = Quat.slerp(a, b, 0.5f)
        // expected: 45 degrees around Z
        val e = Quat.fromAxisAngle(0f, 0f, 1f, (Math.PI / 4).toFloat())
        near(e.x.toDouble(), mid.x.toDouble(), "x")
        near(e.y.toDouble(), mid.y.toDouble(), "y")
        near(e.z.toDouble(), mid.z.toDouble(), "z")
        near(e.w.toDouble(), mid.w.toDouble(), "w")
    }

    @Test
    fun `slerp endpoints`() {
        val a = Quat.IDENTITY
        val b = Quat.fromAxisAngle(1f, 0f, 0f, 0.7f)
        val t0 = Quat.slerp(a, b, 0f)
        val t1 = Quat.slerp(a, b, 1f)
        near(1.0, t0.w.toDouble(), "t0 w")
        near(b.w.toDouble(), t1.w.toDouble(), "t1 w")
    }

    @Test
    fun `multiply is associative in effect`() {
        val p = Quat.fromAxisAngle(0f, 1f, 0f, 0.5f)
        val q = Quat.fromAxisAngle(0f, 0f, 1f, 0.4f)
        // (p*q).v must equal p.(q.v)
        val v = Vec3(0.4f, 0.9f, 0.2f)
        val l = p.multiply(q).rotate(v)
        val r = p.rotate(q.rotate(v))
        near(l.x.toDouble(), r.x.toDouble(), "x")
        near(l.y.toDouble(), r.y.toDouble(), "y")
        near(l.z.toDouble(), r.z.toDouble(), "z")
    }
}
