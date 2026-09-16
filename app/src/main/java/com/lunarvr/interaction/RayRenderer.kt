package com.lunarvr.interaction

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.opengl.GLES20
import com.lunarvr.math.Mat4
import com.lunarvr.math.Quat
import com.lunarvr.math.Vec3
import com.lunarvr.scene.GLUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Renders the finger ray (thin soft line with gradient), its glow and the
 * circular cursor at the hit point. One small dynamic buffer per ray.
 */
class RayRenderer {

    private var rayProgram = 0
    private var rayVao = 0
    private var rayVbo = 0
    private var uRayProj = 0
    private var uRayView = 0
    private var uRayColor = 0

    private var cursorProgram = 0
    private var cursorVao = 0
    private var cursorVbo = 0
    private var cursorTex = 0
    private var uCurModel = 0
    private var uCurView = 0
    private var uCurProj = 0
    private var uCurTint = 0

    private val pos = FloatArray(8 * 3)
    private val uvs = FloatArray(8)
    private val cursorPos = FloatArray(4 * 3)

    fun initGL() {
        if (rayProgram != 0) return
        val vs = """
            attribute vec3 aPos;
            attribute float aT;
            uniform mat4 uView;
            uniform mat4 uProj;
            varying float vT;
            void main() {
                vT = aT;
                gl_Position = uProj * uView * vec4(aPos, 1.0);
            }
        """.trimIndent()
        val fs = """
            precision mediump float;
            uniform vec4 uColor;
            varying float vT;
            void main() {
                float a = mix(uColor.a * 0.85, uColor.a * 0.06, vT);
                gl_FragColor = vec4(uColor.rgb, a);
            }
        """.trimIndent()
        rayProgram = GLUtil.createProgram(vs, fs, "aPos" to 0, "aT" to 1)
        uRayProj = GLES20.glGetUniformLocation(rayProgram, "uProj")
        uRayView = GLES20.glGetUniformLocation(rayProgram, "uView")
        uRayColor = GLES20.glGetUniformLocation(rayProgram, "uColor")
        rayVao = GLUtil.genVertexArray()
        GLUtil.bindVertexArray(rayVao)
        rayVbo = GLUtil.genBuffer()
        // interleaved: pos(3) + t(1)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, rayVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 16 * 8 * 4, null, GLES20.GL_DYNAMIC_DRAW)
        GLUtil.attrPointer(rayVao, 0, 3, GLES20.GL_FLOAT, 16, 0)
        GLUtil.attrPointer(rayVao, 1, 1, GLES20.GL_FLOAT, 16, 12)
        GLUtil.bindVertexArray(0)

        // cursor (billboard ring, textured)
        val cvs = """
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
        val cfs = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform vec4 uTint;
            void main() {
                vec4 c = texture2D(uTex, vUv);
                gl_FragColor = vec4(c.rgb * uTint.rgb, c.a * uTint.a);
            }
        """.trimIndent()
        cursorProgram = GLUtil.createProgram(cvs, cfs, "aPos" to 0, "aUv" to 1)
        uCurModel = GLES20.glGetUniformLocation(cursorProgram, "uModel")
        uCurView = GLES20.glGetUniformLocation(cursorProgram, "uView")
        uCurProj = GLES20.glGetUniformLocation(cursorProgram, "uProj")
        uCurTint = GLES20.glGetUniformLocation(cursorProgram, "uTint")
        cursorTexLoc = GLES20.glGetUniformLocation(cursorProgram, "uTex")
        cursorVao = GLUtil.genVertexArray()
        GLUtil.bindVertexArray(cursorVao)
        cursorVbo = GLUtil.genBuffer()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, cursorVbo)
        val quad = floatArrayOf(
            -1f, -1f, 0f, 0f, 0f,
            1f, -1f, 0f, 1f, 0f,
            -1f, 1f, 0f, 0f, 1f,
            1f, 1f, 0f, 1f, 1f
        )
        val bb = GLUtil.directFloatBuffer(quad)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, bb, GLES20.GL_STATIC_DRAW)
        GLUtil.attrPointer(cursorVao, 0, 3, GLES20.GL_FLOAT, 20, 0)
        GLUtil.attrPointer(cursorVao, 1, 2, GLES20.GL_FLOAT, 20, 12)
        GLUtil.bindVertexArray(0)

        // ring cursor texture
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(
            size / 2f, size / 2f, size / 2f,
            intArrayOf(0x40FFFFFF, 0x00FFFFFF),
            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        cv.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        paint.color = 0xF2FFFFFF.toInt()
        cv.drawCircle(size / 2f, size / 2f, size * 0.30f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        cv.drawCircle(size / 2f, size / 2f, size * 0.075f, paint)
        cursorTex = GLUtil.createTexture(size, size)
        GLUtil.uploadBitmap(cursorTex, bmp)
        bmp.recycle()
    }

    private var cursorTexLoc = 0

    private fun drawRay(ray: InteractionManager.RayState, proj: Mat4, view: Mat4) {
        if (!ray.active) return
        val o = ray.origin
        val d = ray.dir
        val L = if (ray.hit) ray.hitT else 2.3f
        val e = Vec3(o.x + d.x * L, o.y + d.y * L, o.z + d.z * L)

        // basis: view-right projected perpendicular to the ray
        val vr = Vec3(view.m[0], view.m[4], view.m[8])
        var u = d.cross(vr)
        val ul = u.length()
        if (ul < 1e-4f) u = Vec3(0f, 1f, 0f) else u = u.mul(1f / ul)

        val w0 = 0.0022f
        val w1 = 0.0042f
        pos[0] = o.x - u.x * w0; pos[1] = o.y - u.y * w0; pos[2] = o.z - u.z * w0
        pos[3] = o.x + u.x * w0; pos[4] = o.y + u.y * w0; pos[5] = o.z + u.z * w0
        pos[6] = e.x - u.x * w1; pos[7] = e.y - u.y * w1; pos[8] = e.z - u.z * w1
        pos[9] = e.x + u.x * w1; pos[10] = e.y + u.y * w1; pos[11] = e.z + u.z * w1
        uvs[0] = 0f; uvs[1] = 0f; uvs[2] = 1f; uvs[3] = 1f

        val data = FloatArray(16)
        for (i in 0 until 4) {
            data[i * 4] = pos[i * 3]
            data[i * 4 + 1] = pos[i * 3 + 1]
            data[i * 4 + 2] = pos[i * 3 + 2]
            data[i * 4 + 3] = uvs[i]
        }
        val bb = GLUtil.directFloatBuffer(data)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(rayProgram)
        GLES20.glUniformMatrix4fv(uRayProj, 1, false, proj.m, 0)
        GLES20.glUniformMatrix4fv(uRayView, 1, false, view.m, 0)
        // main line
        GLES20.glUniform4f(uRayColor, ray.rayColorR, ray.rayColorG, ray.rayColorB, 0.8f)
        GLUtil.bindVertexArray(rayVao)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, rayVbo)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, data.size * 4, bb)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLUtil.bindVertexArray(0)

        // cursor at the hit point (or faint at the far end)
        val cp = if (ray.hit) ray.hitPoint else e
        val scale = 0.020f * ray.cursorScale * (1f + ray.cursorPulse * 0.7f)
        val alpha = if (ray.hit) 1f else 0.35f
        drawBillboard(cp, scale, ray.rayColorR, ray.rayColorG, ray.rayColorB, alpha, proj, view)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
    }

    private fun drawBillboard(center: Vec3, size: Float, r: Float, g: Float, b: Float, a: Float, proj: Mat4, view: Mat4) {
        val vr = Vec3(view.m[0], view.m[4], view.m[8])
        val vu = Vec3(view.m[1], view.m[5], view.m[9])
        cursorPos[0] = 0f; cursorPos[1] = 0f; cursorPos[2] = 0f
        GLES20.glUseProgram(cursorProgram)
        val model = Mat4()
        model.m.fill(0f)
        model.m[0] = vr.x * size; model.m[1] = vr.y * size; model.m[2] = vr.z * size
        model.m[4] = vu.x * size; model.m[5] = vu.y * size; model.m[6] = vu.z * size
        val fwd = vr.cross(vu)
        model.m[8] = fwd.x; model.m[9] = fwd.y; model.m[10] = fwd.z
        model.m[12] = center.x; model.m[13] = center.y; model.m[14] = center.z
        model.m[15] = 1f
        GLES20.glUniformMatrix4fv(uCurProj, 1, false, proj.m, 0)
        GLES20.glUniformMatrix4fv(uCurView, 1, false, view.m, 0)
        GLES20.glUniformMatrix4fv(uCurModel, 1, false, model.m, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cursorTex)
        GLES20.glUniform1i(cursorTexLoc, 0)
        GLES20.glUniform4f(uCurTint, r, g, b, a)
        GLUtil.bindVertexArray(cursorVao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLUtil.bindVertexArray(0)
    }

    fun draw(
        proj: Mat4, view: Mat4,
        rayLeft: InteractionManager.RayState,
        rayRight: InteractionManager.RayState,
        headRay: InteractionManager.RayState,
        useHeadRay: Boolean
    ) {
        if (useHeadRay) {
            drawRay(headRay, proj, view)
        } else {
            drawRay(rayLeft, proj, view)
            drawRay(rayRight, proj, view)
        }
    }

    /** GL context was destroyed: objects are gone, just reset the ids. */
    fun contextLost() {
        rayProgram = 0; cursorProgram = 0
        rayVao = 0; cursorVao = 0
        rayVbo = 0; cursorVbo = 0
        cursorTex = 0; cursorTexLoc = 0
    }

    fun release() {
        if (rayProgram != 0) GLES20.glDeleteProgram(rayProgram)
        if (cursorProgram != 0) GLES20.glDeleteProgram(cursorProgram)
        if (rayVao != 0) GLUtil.delVertexArray(rayVao)
        if (cursorVao != 0) GLUtil.delVertexArray(cursorVao)
        if (rayVbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(rayVbo), 0)
        if (cursorVbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(cursorVbo), 0)
        if (cursorTex != 0) GLES20.glDeleteTextures(1, intArrayOf(cursorTex), 0)
        rayProgram = 0; cursorProgram = 0; rayVao = 0; cursorVao = 0
        rayVbo = 0; cursorVbo = 0; cursorTex = 0
    }
}
