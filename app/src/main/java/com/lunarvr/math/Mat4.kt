package com.lunarvr.math

import kotlin.math.tan

/**
 * Column-major 4x4 matrix (OpenGL convention): m[col * 4 + row].
 */
class Mat4 {
    val m = FloatArray(16)

    fun set(other: Mat4): Mat4 {
        System.arraycopy(other.m, 0, m, 0, 16)
        return this
    }

    fun identity(): Mat4 {
        m.fill(0f)
        m[0] = 1f; m[5] = 1f; m[10] = 1f; m[15] = 1f
        return this
    }

    /** this = a * b */
    /** this = this * b */
    fun multiply(b: Mat4): Mat4 = mul(this, b)

    fun mul(a: Mat4, b: Mat4): Mat4 {
        val am = a.m; val bm = b.m
        val out = FloatArray(16)
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                var s = 0f
                for (k in 0 until 4) s += am[k * 4 + r] * bm[c * 4 + k]
                out[c * 4 + r] = s
            }
        }
        m.set(0, out)
        return this
    }

    fun perspective(fovyRad: Float, aspect: Float, near: Float, far: Float): Mat4 {
        m.fill(0f)
        val f = (1.0 / tan(fovyRad.toDouble() * 0.5)).toFloat()
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = (2f * far * near) / (near - far)
        return this
    }

    /** World matrix = T * R * S (pos, rotation quat, non-uniform scale). */
    fun compose(pos: Vec3, q: Quat, scale: Vec3): Mat4 {
        m.fill(0f)
        val x = q.x; val y = q.y; val z = q.z; val w = q.w
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        // column 0
        m[0] = (1f - 2f * (yy + zz)) * scale.x
        m[1] = 2f * (xy + wz) * scale.x
        m[2] = 2f * (xz - wy) * scale.x
        // column 1
        m[4] = 2f * (xy - wz) * scale.y
        m[5] = (1f - 2f * (xx + zz)) * scale.y
        m[6] = 2f * (yz + wx) * scale.y
        // column 2
        m[8] = 2f * (xz + wy) * scale.z
        m[9] = 2f * (yz - wx) * scale.z
        m[10] = (1f - 2f * (xx + yy)) * scale.z
        m[12] = pos.x; m[13] = pos.y; m[14] = pos.z
        m[15] = 1f
        return this
    }

    /** View matrix for a camera at [pos] with orientation [q] (inverse of the rigid world). */
    fun viewFromPose(pos: Vec3, q: Quat): Mat4 {
        m.fill(0f)
        val x = q.x; val y = q.y; val z = q.z; val w = q.w
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        // World rotation R (v' = q v q*):
        //   R = [ 1-2(yy+zz), 2(xy-wz), 2(xz+wy) ]
        //       [ 2(xy+wz),  1-2(xx+zz), 2(yz-wx) ]
        //       [ 2(xz-wy),  2(yz+wx),  1-2(xx+yy) ]
        // View rotation is R^T: its column i is ROW i of R.
        val r00 = 1f - 2f * (yy + zz); val r01 = 2f * (xy - wz); val r02 = 2f * (xz + wy)
        val r10 = 2f * (xy + wz);      val r11 = 1f - 2f * (xx + zz); val r12 = 2f * (yz - wx)
        val r20 = 2f * (xz - wy);      val r21 = 2f * (yz + wx);      val r22 = 1f - 2f * (xx + yy)
        m[0] = r00; m[1] = r01; m[2] = r02
        m[4] = r10; m[5] = r11; m[6] = r12
        m[8] = r20; m[9] = r21; m[10] = r22
        // translation = -R^T * pos
        val tx = -(r00 * pos.x + r01 * pos.y + r02 * pos.z)
        val ty = -(r10 * pos.x + r11 * pos.y + r12 * pos.z)
        val tz = -(r20 * pos.x + r21 * pos.y + r22 * pos.z)
        m[12] = tx; m[13] = ty; m[14] = tz
        m[15] = 1f
        return this
    }

    fun transformPoint(v: Vec3): Vec3 {
        val x = m[0] * v.x + m[4] * v.y + m[8] * v.z + m[12]
        val y = m[1] * v.x + m[5] * v.y + m[9] * v.z + m[13]
        val z = m[2] * v.x + m[6] * v.y + m[10] * v.z + m[14]
        return Vec3(x, y, z)
    }

    fun transformDirection(v: Vec3): Vec3 {
        val x = m[0] * v.x + m[4] * v.y + m[8] * v.z
        val y = m[1] * v.x + m[5] * v.y + m[9] * v.z
        val z = m[2] * v.x + m[6] * v.y + m[10] * v.z
        return Vec3(x, y, z)
    }

    companion object {
        /** Read-only identity (do not mutate). Prefer [temp] for working copies. */
        @JvmStatic
        val IDENTITY: Mat4 = Mat4().identity()

        fun temp(): Mat4 = Mat4().identity()
    }
}
