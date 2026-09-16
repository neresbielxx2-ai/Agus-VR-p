package com.lunarvr.stereo

import android.opengl.GLES20
import android.opengl.GLES30
import com.lunarvr.math.Mat4
import com.lunarvr.math.Vec3
import com.lunarvr.scene.GLUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Off-screen framebuffer used to render the whole scene at a scaled
 * resolution (performance presets LOW / MEDIUM / HIGH), then blitted
 * to the screen. Keeps fill-rate low on weaker phones.
 */
class RenderTarget(var width: Int, var height: Int) {

    private var fbo = 0
    var colorTextureId: Int
    private var depthRb = 0

    init {
        colorTextureId = GLUtil.createTexture(width, height)
        fbo = GLUtil.genFramebuffer()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, colorTextureId, 0)
        depthRb = GLUtil.genRenderbuffer()
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depthRb)
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, width, height)
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, depthRb)
        GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (colorTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(colorTextureId), 0)
        if (depthRb != 0) GLES20.glDeleteRenderbuffers(1, intArrayOf(depthRb), 0)
        fbo = 0; colorTextureId = 0; depthRb = 0
    }
}

/**
 * SIDE-BY-SIDE stereo renderer.
 *
 * Screen layout:  [ LEFT EYE | RIGHT EYE ]
 *
 * The same scene graph is rendered twice with the eye cameras offset by
 * +/- IPD/2 in the head's local X axis (scissor + viewport per eye), giving
 * correct stereoscopic parallax depth for menus and objects. Objects are
 * NOT duplicated in the scene — only the two camera views differ.
 */
class StereoRenderer {

    var surfaceWidth = 0
        private set
    var surfaceHeight = 0
        private set

    /** Render resolution scale (graphics presets). */
    var renderScale = 0.85f
        set(value) {
            field = value.coerceIn(0.5f, 1f)
            rtDirty.set(true)
        }
    private val rtDirty = AtomicBoolean(true)

    private var target: RenderTarget? = null
    private var blitProgram = 0
    private var blitVao = 0
    private var blitVbo = 0
    private var blitTexUniform = 0
    private var ready = false

    fun onSurfaceChanged(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        this.surfaceWidth = width
        this.surfaceHeight = height
        rtDirty.set(true)
    }

    /** GL context was destroyed (rotation): GL objects are gone, just reset state. */
    fun contextLost() {
        target = null
        blitProgram = 0
        blitVao = 0
        blitVbo = 0
        ready = false
        rtDirty.set(true)
    }

    fun initGL() {
        if (ready) return
        val vs = """
            attribute vec3 aPos;
            varying vec2 vUv;
            void main() {
                vUv = vec2(aPos.x * 0.5 + 0.5, aPos.y * 0.5 + 0.5);
                gl_Position = vec4(aPos.x, aPos.y, 0.0, 1.0);
            }
        """.trimIndent()
        val fs = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            void main() { gl_FragColor = texture2D(uTex, vUv); }
        """.trimIndent()
        blitProgram = GLUtil.createProgram(vs, fs, "aPos" to 0)
        blitTexUniform = GLES20.glGetUniformLocation(blitProgram, "uTex")
        blitVao = GLUtil.genVertexArray()
        GLES30.glBindVertexArray(blitVao)
        blitVbo = GLUtil.genBuffer()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, blitVbo)
        // Fullscreen triangle
        val verts = floatArrayOf(-1f, -1f, 0f, 3f, -1f, 0f, -1f, 3f, 0f)
        val buf = GLUtil.directFloatBuffer(verts)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glEnableVertexAttribArray(0)
        GLES30.glBindVertexArray(0)
        ready = true
    }

    private fun ensureTarget() {
        val w = (surfaceWidth * renderScale).toInt().coerceAtLeast(2)
        val h = (surfaceHeight * renderScale).toInt().coerceAtLeast(2)
        val cur = target
        if (cur == null || cur.width != w || cur.height != h) {
            cur?.release()
            target = RenderTarget(w, h)
            rtDirty.set(false)
        }
    }

    val targetWidth: Int get() = target?.width ?: 0
    val targetHeight: Int get() = target?.height ?: 0

    /**
     * Renders one full frame:
     *  1. bind the offscreen target
     *  2. for each eye: set viewport + scissor to its half of the screen,
     *     clear, then draw the scene with that eye's projection/view
     *  3. blit the target to the screen at full resolution
     *
     * @param sbs side-by-side enabled; when false a single centered eye
     *        view fills the screen (plain-phone debug mode).
     * @param makeEye returns (projection, view, eyePosition) for the eye index.
     * @param drawEye draws the whole scene for one eye.
     */
    fun renderFrame(
        sbs: Boolean,
        makeEye: (eyeIndex: Int) -> Triple<Mat4, Mat4, Vec3>,
        drawEye: (eyeIndex: Int, proj: Mat4, view: Mat4, eyePos: Vec3) -> Unit
    ) {
        ensureTarget()
        val rt = target ?: return
        initGL()
        rt.bind()
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)

        val rtW = rt.width
        val rtH = rt.height

        if (sbs) {
            val halfW = rtW / 2
            renderEyeInto(0, 0, halfW, rtH, makeEye(0), drawEye)
            renderEyeInto(1, halfW, rtW - halfW, rtH, makeEye(1), drawEye)
        } else {
            renderEyeInto(0, 0, rtW, rtH, makeEye(0), drawEye)
        }

        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(blitProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rt.colorTextureId)
        GLES20.glUniform1i(blitTexUniform, 0)
        GLES30.glBindVertexArray(blitVao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
    }

    private fun renderEyeInto(
        eye: Int, x: Int, w: Int, h: Int,
        pose: Triple<Mat4, Mat4, Vec3>,
        drawEye: (Int, Mat4, Mat4, Vec3) -> Unit
    ) {
        GLES20.glViewport(x, 0, w, h)
        GLES20.glScissor(x, 0, w, h)
        GLES20.glClearColor(0.008f, 0.011f, 0.024f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        drawEye(eye, pose.first, pose.second, pose.third)
    }

    fun release() {
        target?.release()
        target = null
        if (blitProgram != 0) GLES20.glDeleteProgram(blitProgram)
        if (blitVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(blitVao), 0)
        if (blitVbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(blitVbo), 0)
        blitProgram = 0; blitVao = 0; blitVbo = 0
        ready = false
    }
}
