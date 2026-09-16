package com.lunarvr.ui

import android.opengl.GLES20
import android.opengl.GLES30
import com.lunarvr.math.Mat4
import com.lunarvr.math.Quat
import com.lunarvr.math.Vec3
import com.lunarvr.scene.GLUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * A REAL 3D spatial menu: a textured quad with its own position, rotation
 * (yaw), scale and depth, rendered with transparency and a soft shadow.
 *
 * The panel is a child of the WorldRoot scene graph — it does NOT follow
 * the head. Once placed in the virtual space it stays there.
 *
 * Open/close animation (spec):
 *   ABRIR : scale 0.92 -> 1.0, fade-in, small approach
 *   FECHAR: scale 1.0 -> 0.92, fade-out, small recession
 */
class Panel3D(
    val title: String,
    val subtitle: String,
    val headerIcon: Int,
    val widthM: Float,
    val heightM: Float,
    texW: Int,
    texH: Int
) {

    val texture = PanelTexture(texW, texH, headerIcon)
    val items = ArrayList<UIItem>()
    var showHeader: Boolean = true

    var state: Int = STATE_HIDDEN
    var animT: Float = 0f

    /** Logical placement in the WorldRoot space. */
    var pos = Vec3(0f, 0f, -1.6f)
    var yaw: Float = 0f

    // Effective (animated) pose — read by the renderer and raycaster
    val currentPos = Vec3()
    val currentQuat = Quat.IDENTITY
    var currentScale: Float = 0.92f
    val alphaForHit: Float
        get() = if (state == STATE_HIDDEN) 0f else ease(animT)

    private var vao = 0
    private var vbo = 0

    fun add(item: UIItem): UIItem {
        items.add(item)
        return item
    }

    /** Draws the full panel into the canvas (no GL — any thread). */
    fun prepare() {
        texture.drawBackground()
        if (showHeader) texture.drawHeader(title, subtitle)
        for (i in items) texture.drawItem(i)
    }

    /** Uploads the texture (GL thread). */
    fun uploadGL() {
        texture.fullUpload()
    }

    fun setHovered(item: UIItem?, newHover: Boolean) {
        for (i in items) {
            val was = i.hovered
            val now = if (i === item) newHover else false
            if (was != now) {
                i.hovered = now
                texture.redrawItem(i)
            }
        }
    }

    // ------------------------------------------------------------------
    // Animation
    // ------------------------------------------------------------------
    fun update(dt: Float) {
        when (state) {
            STATE_OPENING -> {
                animT += dt / 0.18f
                if (animT >= 1f) { animT = 1f; state = STATE_OPEN }
            }
            STATE_CLOSING -> {
                animT -= dt / 0.14f
                if (animT <= 0f) { animT = 0f; state = STATE_HIDDEN; setHovered(null, false) }
            }
        }
        val e = ease(animT)
        currentScale = 0.92f + 0.08f * e
        val away = (1f - e) * 0.12f
        currentPos.set(pos.x, pos.y, pos.z - away)
        val q = Quat.fromAxisAngle(0f, 1f, 0f, yaw)
        currentQuat.set(q.x, q.y, q.z, q.w)
    }

    fun openAt(forward: Vec3) {
        pos.set(forward.x * 1.6f, forward.y * 1.6f - 0.02f, forward.z * 1.6f)
        // face the user (origin) with yaw only
        val dx = -pos.x
        val dz = -pos.z
        yaw = kotlin.math.atan2(dx.toDouble(), dz.toDouble()).toFloat()
        state = STATE_OPENING
        animT = 0.02f
    }

    fun closeNow() {
        if (state == STATE_OPENING || state == STATE_OPEN) {
            state = STATE_CLOSING
        }
    }

    fun isOpen(): Boolean = state == STATE_OPEN || state == STATE_OPENING

    fun hide() {
        state = STATE_HIDDEN
        animT = 0f
        setHovered(null, false)
    }

    private fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    // ------------------------------------------------------------------
    // Hit testing (local meters)
    // ------------------------------------------------------------------
    fun hitItem(localX: Float, localY: Float): UIItem? {
        if (alphaForHit < 0.5f) return null
        val u = 0.5f + localX / widthM
        val v = 0.5f - localY / heightM
        val px = u * texture.bitmap.width
        val py = v * texture.bitmap.height
        for (i in items.indices.reversed()) {
            val item = items[i]
            if (!UIItem.isInteractive(item.kind)) continue
            if (!item.enabled) continue
            val r = item.rect
            if (px >= r.left && px <= r.right && py >= r.top && py <= r.bottom) return item
        }
        return null
    }

    /** Maps a local hit (meters) to a 0..1 slider fraction along X. */
    fun sliderT01(localX: Float): Float =
        (((localX / (widthM * 0.5f)) + 1f) * 0.5f).coerceIn(0f, 1f)

    // ------------------------------------------------------------------
    // GL
    // ------------------------------------------------------------------
    fun initGL() {
        if (vao != 0) return
        texture.initGL()
        val hw = widthM * 0.5f
        val hh = heightM * 0.5f
        // pos(3) + uv(2)
        val data = floatArrayOf(
            -hw, -hh, 0f, 0f, 1f,
            hw, -hh, 0f, 1f, 1f,
            -hw, hh, 0f, 0f, 0f,
            hw, hh, 0f, 1f, 0f
        )
        vao = GLUtil.genVertexArray()
        GLES30.glBindVertexArray(vao)
        vbo = GLUtil.genBuffer()
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(data)
        fb.position(0)
        bb.position(0)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, bb, GLES20.GL_STATIC_DRAW)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 20, 0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 20, 12)
        GLES20.glEnableVertexAttribArray(1)
        GLES30.glBindVertexArray(0)
    }

    /**
     * Draws shadow + panel using the shared panel program.
     * Callers manage blend/depth state.
     */
    fun draw(
        proj: Mat4, view: Mat4,
        panelProgram: Int,
        uModel: Int, uView: Int, uProj: Int, uTex: Int, uAlpha: Int,
        shadowTex: Int
    ) {
        if (state == STATE_HIDDEN && animT <= 0f) return
        val e = ease(animT)
        val yawQ = Quat.fromAxisAngle(0f, 1f, 0f, yaw)

        GLES20.glUseProgram(panelProgram)
        GLES20.glUniformMatrix4fv(uProj, 1, false, proj.m, 0)
        GLES20.glUniformMatrix4fv(uView, 1, false, view.m, 0)

        // soft shadow behind the panel
        val n = yawQ.rotate(Vec3(0f, 0f, 1f))
        val sp = currentPos.addScaled(n, -0.018f)
        val sScale = currentScale * 1.14f
        val sModel = Mat4().compose(sp, yawQ, Vec3(sScale, sScale, sScale))
        GLES20.glUniformMatrix4fv(uModel, 1, false, sModel.m, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTex)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniform1f(uAlpha, 0.34f * e)
        GLES30.glBindVertexArray(vao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // the panel itself
        val pModel = Mat4().compose(currentPos, yawQ, Vec3(currentScale, currentScale, currentScale))
        GLES20.glUniformMatrix4fv(uModel, 1, false, pModel.m, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture.texId)
        GLES20.glUniform1f(uAlpha, e)
        GLES30.glBindVertexArray(vao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
    }

    /** GL context was destroyed: objects are gone, just reset the ids. */
    fun contextLost() {
        vao = 0
        vbo = 0
        texture.contextLost()
    }

    fun release() {
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (vbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(vbo), 0)
        vao = 0; vbo = 0
        texture.release()
    }

    companion object {
        const val STATE_HIDDEN = 0
        const val STATE_OPENING = 1
        const val STATE_OPEN = 2
        const val STATE_CLOSING = 3
    }
}
