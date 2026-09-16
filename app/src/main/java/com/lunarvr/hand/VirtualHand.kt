package com.lunarvr.hand

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.GLES20
import android.opengl.GLES30
import com.lunarvr.math.Mat4
import com.lunarvr.math.Quat
import com.lunarvr.math.Vec3
import com.lunarvr.scene.GLUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D representation of the tracked hand: a translucent white/grey body with
 * visible joints, defined fingers and a soft fresnel "aura".
 *
 * The whole hand is built into ONE dynamic vertex buffer (one draw call):
 * palm plate + finger capsules + joint spheres.
 * Quality presets: LOW = fewer segments, no aura; HIGH = finer mesh + aura.
 */
class VirtualHand {

    private val maxVerts = 12000
    private var pos = FloatArray(maxVerts * 3)
    private var nrm = FloatArray(maxVerts * 3)
    private var vertCount = 0

    private var vao = 0
    private var vboPos = 0
    private var vboNrm = 0
    private var program = 0
    private var uProj = 0
    private var uView = 0
    private var uCamPos = 0
    private var uAlpha = 0
    private var uGlow = 0

    private var auraProgram = 0
    private var auraVao = 0
    private var auraVbo = 0
    private var auraTex = 0
    private var uAuraModel = 0
    private var uAuraView = 0
    private var uAuraProj = 0
    private var uAuraColor = 0

    private val jointRadius = floatArrayOf(
        0.0135f, 0.0110f, 0.0095f, 0.0085f, 0.0085f, // wrist..thumb tip
        0.0105f, 0.0095f, 0.0085f, 0.0080f,           // index
        0.0105f, 0.0095f, 0.0085f, 0.0080f,           // middle
        0.0100f, 0.0090f, 0.0080f, 0.0075f,           // ring
        0.0095f, 0.0085f, 0.0075f, 0.0070f,           // pinky
        0.0065f                                        // little finger tip helper
    )

    fun initGL() {
        if (program != 0) return
        val vs = """
            attribute vec3 aPos;
            attribute vec3 aNormal;
            uniform mat4 uView;
            uniform mat4 uProj;
            varying vec3 vNormal;
            varying vec3 vWorld;
            void main() {
                vWorld = aPos;
                vNormal = aNormal;
                gl_Position = uProj * uView * vec4(aPos, 1.0);
            }
        """.trimIndent()
        val fs = """
            precision mediump float;
            uniform vec3 uCamPos;
            uniform float uAlpha;
            uniform float uGlow;
            varying vec3 vNormal;
            varying vec3 vWorld;
            void main() {
                vec3 n = normalize(vNormal);
                vec3 vdir = normalize(uCamPos - vWorld);
                float ndv = max(dot(n, vdir), 0.0);
                float fres = pow(1.0 - ndv, 2.2);
                vec3 base = vec3(0.88, 0.92, 0.98);
                float light = 0.55 + 0.45 * max(dot(n, normalize(vec3(0.3, 0.8, 0.5))), 0.0);
                vec3 col = base * light;
                col += vec3(0.35, 0.52, 0.85) * fres * (0.55 + uGlow * 0.9);
                float a = clamp(uAlpha + fres * 0.32 + uGlow * 0.12, 0.0, 1.0);
                gl_FragColor = vec4(col, a);
            }
        """.trimIndent()
        program = GLUtil.createProgram(vs, fs, "aPos" to 0, "aNormal" to 1)
        uProj = GLES20.glGetUniformLocation(program, "uProj")
        uView = GLES20.glGetUniformLocation(program, "uView")
        uCamPos = GLES20.glGetUniformLocation(program, "uCamPos")
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha")
        uGlow = GLES20.glGetUniformLocation(program, "uGlow")

        vao = GLES30.glGenVertexArrays()
        GLES30.glBindVertexArray(vao)
        vboPos = GLES30.glGenBuffers()
        GLES30.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, maxVerts * 3 * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glEnableVertexAttribArray(0)
        vboNrm = GLES30.glGenBuffers()
        GLES30.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboNrm)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, maxVerts * 3 * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES30.glBindVertexArray(0)

        // aura billboard
        val avs = """
            attribute vec3 aPos;
            attribute vec2 aUv;
            uniform mat4 uModel;
            uniform mat4 uView;
            uniform mat4 uProj;
            varying vec2 vUv;
            void main() {
                vUv = aUv;
                gl_Position = uProj * uView * uModel * vec4(aPos, 1.0);
            }
        """.trimIndent()
        val afs = """
            precision mediump float;
            varying vec2 vUv;
            uniform vec4 uColor;
            void main() {
                vec2 c = vUv - 0.5;
                float r = length(c) * 2.0;
                float a = smoothstep(1.0, 0.05, r) * uColor.a;
                gl_FragColor = vec4(uColor.rgb * a, a);
            }
        """.trimIndent()
        auraProgram = GLUtil.createProgram(avs, afs, "aPos" to 0, "aUv" to 1)
        uAuraModel = GLES20.glGetUniformLocation(auraProgram, "uModel")
        uAuraView = GLES20.glGetUniformLocation(auraProgram, "uView")
        uAuraProj = GLES20.glGetUniformLocation(auraProgram, "uProj")
        uAuraColor = GLES20.glGetUniformLocation(auraProgram, "uColor")
        auraVao = GLES30.glGenVertexArrays()
        GLES30.glBindVertexArray(auraVao)
        auraVbo = GLES30.glGenBuffers()
        GLES30.glBindBuffer(GLES20.GL_ARRAY_BUFFER, auraVbo)
        val quad = floatArrayOf(
            -1f, -1f, 0f, 0f, 0f,
            1f, -1f, 0f, 1f, 0f,
            -1f, 1f, 0f, 0f, 1f,
            1f, 1f, 0f, 1f, 1f
        )
        val bb = GLUtil.directFloatBuffer(quad)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, bb, GLES20.GL_STATIC_DRAW)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 20, 0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 20, 12)
        GLES20.glEnableVertexAttribArray(1)
        GLES30.glBindVertexArray(0)
    }

    // ------------------------------------------------------------------
    // Mesh building (world space — the pose is already in WorldRoot coords)
    // ------------------------------------------------------------------
    private fun addVert(p: Vec3, n: Vec3) {
        if (vertCount >= maxVerts) return
        pos[vertCount * 3] = p.x
        pos[vertCount * 3 + 1] = p.y
        pos[vertCount * 3 + 2] = p.z
        nrm[vertCount * 3] = n.x
        nrm[vertCount * 3 + 1] = n.y
        nrm[vertCount * 3 + 2] = n.z
        vertCount++
    }

    /** Open cylinder between a and b (joint spheres cap the ends). */
    private fun capsule(a: Vec3, b: Vec3, r: Float, sides: Int) {
        val d = b.sub(a)
        val len = d.length()
        if (len < 1e-4f) return
        val dn = d.mul(1f / len)
        val ref = if (abs(dn.x) < 0.9f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)
        val u = dn.cross(ref).normalize()
        val v = dn.cross(u)

        val o0 = FloatArray(sides * 3)
        val o1 = FloatArray(sides * 3)
        for (i in 0 until sides) {
            val ang = 2 * PI * i / sides
            val c = cos(ang).toFloat(); val s = sin(ang).toFloat()
            val nx = u.x * c + v.x * s
            val ny = u.y * c + v.y * s
            val nz = u.z * c + v.z * s
            o0[i * 3] = nx; o0[i * 3 + 1] = ny; o0[i * 3 + 2] = nz
            o1[i * 3] = nx; o1[i * 3 + 1] = ny; o1[i * 3 + 2] = nz
        }
        for (i in 0 until sides) {
            val j = (i + 1) % sides
            val p0 = Vec3(a.x + o0[i * 3] * r, a.y + o0[i * 3 + 1] * r, a.z + o0[i * 3 + 2] * r)
            val p1 = Vec3(a.x + o0[j * 3] * r, a.y + o0[j * 3 + 1] * r, a.z + o0[j * 3 + 2] * r)
            val p2 = Vec3(b.x + o1[i * 3] * r, b.y + o1[i * 3 + 1] * r, b.z + o1[i * 3 + 2] * r)
            val p3 = Vec3(b.x + o1[j * 3] * r, b.y + o1[j * 3 + 1] * r, b.z + o1[j * 3 + 2] * r)
            val n0 = Vec3(o0[i * 3], o0[i * 3 + 1], o0[i * 3 + 2])
            val n1 = Vec3(o0[j * 3], o0[j * 3 + 1], o0[j * 3 + 2])
            addVert(p0, n0); addVert(p1, n1); addVert(p2, n0)
            addVert(p1, n1); addVert(p3, n1); addVert(p2, n0)
        }
    }

    private fun spherePoint(
        center: Vec3, r: Float, u: Vec3, n: Vec3, v: Vec3,
        i: Int, j: Int, lat: Int, lon: Int
    ): Pair<Vec3, Vec3> {
        val theta = PI * i / lat
        val phi = 2 * PI * j / lon
        val st = sin(theta).toFloat()
        val cphi = cos(phi).toFloat()
        val ctheta = cos(theta).toFloat()
        val sphi = sin(phi).toFloat()
        val dirX = u.x * st * cphi + n.x * ctheta + v.x * st * sphi
        val dirY = u.y * st * cphi + n.y * ctheta + v.y * st * sphi
        val dirZ = u.z * st * cphi + n.z * ctheta + v.z * st * sphi
        return Pair(
            Vec3(center.x + dirX * r, center.y + dirY * r, center.z + dirZ * r),
            Vec3(dirX, dirY, dirZ)
        )
    }

    private fun jointSphere(center: Vec3, r: Float, normal: Vec3, lat: Int, lon: Int) {
        val ref = if (abs(normal.y) < 0.9f) Vec3(0f, 1f, 0f) else Vec3(1f, 0f, 0f)
        val u = normal.cross(ref).normalize()
        val v = normal.cross(u)
        for (i in 0 until lat) {
            for (j in 0 until lon) {
                val p00 = spherePoint(center, r, u, normal, v, i, j, lat, lon)
                val p01 = spherePoint(center, r, u, normal, v, i, j + 1, lat, lon)
                val p10 = spherePoint(center, r, u, normal, v, i + 1, j, lat, lon)
                val p11 = spherePoint(center, r, u, normal, v, i + 1, j + 1, lat, lon)
                addVert(p00.first, p00.second); addVert(p10.first, p10.second); addVert(p11.first, p11.second)
                addVert(p00.first, p00.second); addVert(p11.first, p11.second); addVert(p01.first, p01.second)
            }
        }
    }

    private fun palmPlate(center: Vec3, radius: Float, normal: Vec3) {
        val ref = if (abs(normal.y) < 0.9f) Vec3(0f, 1f, 0f) else Vec3(1f, 0f, 0f)
        val u = normal.cross(ref).normalize()
        val v = normal.cross(u)
        val sides = 8
        val off = 0.0045f
        val p0 = FloatArray(sides * 3)
        val p1 = FloatArray(sides * 3)
        for (i in 0 until sides) {
            val ang = 2 * PI * i / sides
            val c = cos(ang).toFloat(); val s = sin(ang).toFloat()
            val px = (u.x * c + v.x * s) * radius
            val py = (u.y * c + v.y * s) * radius
            val pz = (u.z * c + v.z * s) * radius
            p0[i * 3] = px; p0[i * 3 + 1] = py; p0[i * 3 + 2] = pz
            p1[i * 3] = px; p1[i * 3 + 1] = py; p1[i * 3 + 2] = pz
        }
        for (i in 0 until sides) {
            val j = (i + 1) % sides
            // side wall
            val a0 = Vec3(center.x + p0[i * 3] - normal.x * off, center.y + p0[i * 3 + 1] - normal.y * off, center.z + p0[i * 3 + 2] - normal.z * off)
            val a1 = Vec3(center.x + p0[j * 3] - normal.x * off, center.y + p0[j * 3 + 1] - normal.y * off, center.z + p0[j * 3 + 2] - normal.z * off)
            val b0 = Vec3(center.x + p1[i * 3] + normal.x * off, center.y + p1[i * 3 + 1] + normal.y * off, center.z + p1[i * 3 + 2] + normal.z * off)
            val b1 = Vec3(center.x + p1[j * 3] + normal.x * off, center.y + p1[j * 3 + 1] + normal.y * off, center.z + p1[j * 3 + 2] + normal.z * off)
            addVert(a0, normal.mul(-1f)); addVert(a1, normal.mul(-1f)); addVert(b0, normal.mul(-1f))
            addVert(a1, normal.mul(-1f)); addVert(b1, normal.mul(-1f)); addVert(b0, normal.mul(-1f))
        }
        // front & back caps (triangle fans)
        val c0 = Vec3(center.x - normal.x * off, center.y - normal.y * off, center.z - normal.z * off)
        val c1 = Vec3(center.x + normal.x * off, center.y + normal.y * off, center.z + normal.z * off)
        for (i in 0 until sides) {
            val j = (i + 1) % sides
            val a0 = Vec3(center.x + p0[i * 3] - normal.x * off, center.y + p0[i * 3 + 1] - normal.y * off, center.z + p0[i * 3 + 2] - normal.z * off)
            val a1 = Vec3(center.x + p0[j * 3] - normal.x * off, center.y + p0[j * 3 + 1] - normal.y * off, center.z + p0[j * 3 + 2] - normal.z * off)
            val b0 = Vec3(center.x + p1[i * 3] + normal.x * off, center.y + p1[i * 3 + 1] + normal.y * off, center.z + p1[i * 3 + 2] + normal.z * off)
            val b1 = Vec3(center.x + p1[j * 3] + normal.x * off, center.y + p1[j * 3 + 1] + normal.y * off, center.z + p1[j * 3 + 2] + normal.z * off)
            addVert(c0, normal.mul(-1f)); addVert(a0, normal.mul(-1f)); addVert(a1, normal.mul(-1f))
            addVert(c1, normal); addVert(b1, normal); addVert(b0, normal)
        }
    }

    /**
     * Rebuilds the mesh for this frame.
     * @param quality 0 LOW, 1 MEDIUM, 2 HIGH
     */
    fun build(hand: HandPose, quality: Int) {
        vertCount = 0
        val sides = when (quality) { 0 -> 6; 2 -> 10; else -> 8 }
        val lat = when (quality) { 0 -> 3; 2 -> 6; else -> 4 }
        val lon = when (quality) { 0 -> 5; 2 -> 8; else -> 6 }

        val lm = hand.landmarks
        // palm plane
        val c = Vec3(
            (lm[5].x + lm[9].x + lm[13].x + lm[17].x) / 4f,
            (lm[5].y + lm[9].y + lm[13].y + lm[17].y) / 4f,
            (lm[5].z + lm[9].z + lm[13].z + lm[17].z) / 4f
        )
        var radius = 0f
        for (i in intArrayOf(5, 9, 13, 17)) radius = maxOf(radius, lm[i].distanceTo(c))
        radius *= 1.12f
        if (radius < 0.02f) radius = 0.02f
        var pn = lm[5].sub(lm[17]).cross(lm[13].sub(lm[9]))
        if (pn.length() < 1e-5f) pn = Vec3(0f, 0f, 1f)
        pn = pn.normalize()

        palmPlate(c, radius, pn)

        // wrist
        capsule(lm[0], c, 0.011f, sides)

        // fingers: (base, mid, tip) indices; radius tapers
        val fingers = arrayOf(
            intArrayOf(1, 2, 3, 4),
            intArrayOf(5, 6, 7, 8),
            intArrayOf(9, 10, 11, 12),
            intArrayOf(13, 14, 15, 16),
            intArrayOf(17, 18, 19, 20)
        )
        for (f in fingers) {
            val baseR = if (f[0] == 1) 0.0095f else 0.0105f
            capsule(lm[f[0]], lm[f[1]], baseR, sides)
            capsule(lm[f[1]], lm[f[2]], baseR * 0.88f, sides)
            capsule(lm[f[2]], lm[f[3]], baseR * 0.74f, sides)
        }

        // joint spheres (aura: visible articulation)
        for (i in 0 until 21) {
            jointSphere(lm[i], jointRadius.getOrElse(i) { 0.008f }, pn, lat, lon)
        }
    }

    fun render(
        proj: Mat4, view: Mat4, eyePos: Vec3,
        hand: HandPose, quality: Int, timeSec: Float
    ) {
        if (!hand.visible || vertCount == 0) return
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE) // both sides: translucent material
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uProj, 1, false, proj.m, 0)
        GLES20.glUniformMatrix4fv(uView, 1, false, view.m, 0)
        GLES20.glUniform3f(uCamPos, eyePos.x, eyePos.y, eyePos.z)
        GLES20.glUniform1f(uAlpha, if (quality == 0) 0.38f else 0.30f)
        val glow = if (hand.pinching) 0.6f + 0.4f * sin(timeSec.toDouble() * 18).toFloat() else if (hand.isPoint) 0.25f else 0f
        GLES20.glUniform1f(uGlow, glow)

        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos)
        val bb = GLUtil.directFloatBuffer(pos, 0, vertCount * 3)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, vertCount * 3 * 4, bb)
        GLES30.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboNrm)
        val bb2 = GLUtil.directFloatBuffer(nrm, 0, vertCount * 3)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, vertCount * 3 * 4, bb2)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertCount)
        GLES30.glBindVertexArray(0)

        // aura around the palm (skipped on LOW)
        if (quality > 0) {
            val auraScale = 0.16f + (if (hand.pinching) 0.05f else 0f)
            drawAura(proj, view, hand.wrist.addScaled(hand.indexTip, 0.3f), auraScale,
                0.45f, 0.62f, 0.95f, if (hand.pinching) 0.22f else 0.10f)
        }
        GLES20.glDepthMask(true)
    }

    private fun drawAura(proj: Mat4, view: Mat4, center: Vec3, size: Float, r: Float, g: Float, b: Float, a: Float) {
        // view-space billboard: build the model so the quad faces the camera
        val viewRight = Vec3(view.m[0], view.m[4], view.m[8])
        val viewUp = Vec3(view.m[1], view.m[5], view.m[9])
        val model = Mat4()
        model.m.fill(0f)
        model.m[0] = viewRight.x * size; model.m[1] = viewRight.y * size; model.m[2] = viewRight.z * size
        model.m[4] = viewUp.x * size; model.m[5] = viewUp.y * size; model.m[6] = viewUp.z * size
        val fwd = viewRight.cross(viewUp)
        model.m[8] = fwd.x; model.m[9] = fwd.y; model.m[10] = fwd.z
        model.m[12] = center.x; model.m[13] = center.y; model.m[14] = center.z
        model.m[15] = 1f
        GLES20.glUseProgram(auraProgram)
        GLES20.glUniformMatrix4fv(uAuraProj, 1, false, proj.m, 0)
        GLES20.glUniformMatrix4fv(uAuraView, 1, false, view.m, 0)
        GLES20.glUniformMatrix4fv(uAuraModel, 1, false, model.m, 0)
        GLES20.glUniform4f(uAuraColor, r, g, b, a)
        GLES30.glBindVertexArray(auraVao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    /** GL context was destroyed: objects are gone, just reset the ids. */
    fun contextLost() {
        program = 0; auraProgram = 0
        vao = 0; auraVao = 0
        vboPos = 0; vboNrm = 0; auraVbo = 0
        vertCount = 0
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        if (auraProgram != 0) GLES20.glDeleteProgram(auraProgram)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (auraVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(auraVao), 0)
        if (vboPos != 0) GLES20.glDeleteBuffers(1, intArrayOf(vboPos), 0)
        if (vboNrm != 0) GLES20.glDeleteBuffers(1, intArrayOf(vboNrm), 0)
        if (auraVbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(auraVbo), 0)
        program = 0; auraProgram = 0; vao = 0; auraVao = 0
        vboPos = 0; vboNrm = 0; auraVbo = 0
    }
}
