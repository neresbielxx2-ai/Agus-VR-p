package com.lunarvr.math

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.tan

class Mat4Test {
    private fun near(a: Double, b: Double, msg: String) =
        assertEquals(msg, b, a, 1e-4)

    @Test
    fun `identity transforms point unchanged`() {
        val m = Mat4.IDENTITY
        val p = m.transformPoint(Vec3(1f, 2f, -3f))
        near(1.0, p.x.toDouble(), "x"); near(2.0, p.y.toDouble(), "y"); near(-3.0, p.z.toDouble(), "z")
    }

    @Test
    fun `compose with identity view is identity`() {
        val m = Mat4().viewFromPose(Vec3(0f, 0f, 0f), Quat.IDENTITY)
        val m2 = Mat4().compose(Vec3(0f, 0f, 0f), Quat.IDENTITY, Vec3(1f, 1f, 1f))
        val prod = m.multiply(m2)
        for (i in 0 until 16) {
            near(m.m[i].toDouble(), prod.m[i].toDouble(), "m[$i]")
        }
    }

    @Test
    fun `view matrix inverts camera orientation`() {
        // camera pitched down 90 degrees (looking -Y... i.e. q = Rx(-90)?)
        // use 90 deg around X: forward -Z should map to -Y in view space
        val q = Quat.fromAxisAngle(1f, 0f, 0f, (Math.PI / 2).toFloat())
        val view = Mat4().viewFromPose(Vec3(0f, 0f, 0f), q)
        val forward = view.transformPoint(Vec3(0f, 0f, -1f))
        near(0.0, forward.x.toDouble(), "x")
        near(-1.0, forward.y.toDouble(), "y")
        near(0.0, forward.z.toDouble(), "z")
    }

    @Test
    fun `perspective values`() {
        val fovy = 1.0f
        val aspect = 16f / 9f
        val zn = 0.1f
        val zf = 100f
        val m = Mat4().perspective(fovy, aspect, zn, zf)
        val f = 1f / tan((fovy / 2f).toDouble()).toFloat()
        near((f / aspect).toDouble(), m.m[0].toDouble(), "m00")
        near(f.toDouble(), m.m[5].toDouble(), "m55")
        near(((zf + zn) / (zn - zf)).toDouble(), m.m[10].toDouble(), "m10")
        near(-1.0, m.m[11].toDouble(), "m11")
        near((2f * zf * zn / (zn - zf)).toDouble(), m.m[14].toDouble(), "m14")
    }

    @Test
    fun `perspective maps point at -z into ndc depth`() {
        val f = 1.0f
        val m = Mat4().perspective(f, 1f, 0.1f, 100f)
        val p = m.transformPoint(Vec3(0f, 0f, -5f))
        val w = p.w
        assertEquals(0.0, (p.x / w).toDouble(), 1e-4)
        assertEquals(0.0, (p.y / w).toDouble(), 1e-4)
        val d = p.z / w
        // between -1 and 1, closer than the horizon
        assertTrue(-1.0 < d && d < 1.0)
    }

    private fun assertTrue(cond: Boolean) =
        assertEquals(true, cond)
}
