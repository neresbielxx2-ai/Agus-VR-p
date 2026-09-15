package com.lunarvr.environment

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The virtual environment: a dark premium space with a soft moon glow,
 * faint stars and a subtle floor disc — depth cues without clutter.
 */
class Environment {

    private var skyProgram = 0
    private var skyVao = 0
    private var skyVbo = 0
    private var uSkyProj = 0
    private var uSkyView = 0
    private var uSkyEye = 0
    private var uMoonDir = 0

    private var starProgram = 0
    private var starVao = 0
    private var starVbo = 0
    private var starCount = 0
    private var uStarProj = 0
    private var uStarView = 0
    private var uStarEye = 0
    private var uStarTime = 0

    private var floorProgram = 0
    private var floorVao = 0
    private var floorVbo = 0
    private var floorTex = 0
    private var uFloorModel = 0
    private var uFloorView = 0
    private var uFloorProj = 0
    private var uFloorTex = 0
    private var uFloorAlpha = 0

    private val moonDir = Vec3(-0.35f, 0.42f, -0.84f).normalize()

    fun initGL(quality: Int) {
        initSky()
        initStars(quality)
        initFloor()
    }

    // ------------------------------------------------------------------
    // Sky dome
    // ------------------------------------------------------------------
    private fun initSky() {
        val vs = """
            attribute vec3 aPos;
            uniform mat4 uProj;
            uniform mat4 uView;
            uniform vec3 uEye;
            varying vec3 vDir;
            void main() {
                vDir = aPos;
                gl_Position = uProj * uView * vec4(aPos + uEye, 1.0);
            }
        """.trimIndent()
        val fs = """
            precision mediump float;
            varying vec3 vDir;
            uniform vec3 uMoonDir;
            void main() {
                vec3 d = normalize(vDir);
                float h = d.y;
                vec3 top = vec3(0.014, 0.020, 0.044);
                vec3 mid = vec3(0.036, 0.052, 0.092);
                vec3 bot = vec3(0.006, 0.009, 0.018);
                vec3 col = mix(mid, top, smoothstep(0.0, 0.55, h));
                col = mix(col, bot, smoothstep(0.0, -0.5, h));
                float m = clamp(dot(d, uMoonDir), 0.0, 1.0);
                col += vec3(0.92, 0.96, 1.0) * pow(m, 1400.0) * 0.9;
                col += vec3(0.55, 0.66, 0.92) * pow(m, 90.0) * 0.09;
                col += vec3(0.10, 0.13, 0.22) * pow(m, 6.0) * 0.10;
                vec3 nd = normalize(vec3(0.6, 0.12, -0.79));
                col += vec3(0.055, 0.045, 0.095) * pow(clamp(dot(d, nd), 0.0, 1.0), 4.0) * 0.35;
                gl_FragColor = vec4(col, 1.0);
            }
        """.trimIndent()
        skyProgram = GLUtil.createProgram(vs, fs, "aPos" to 0)
        uSkyProj = GLES20.glGetUniformLocation(skyProgram, "uProj")
        uSkyView = GLES20.glGetUniformLocation(skyProgram, "uView")
        uSkyEye = GLES20.glGetUniformLocation(skyProgram, "uEye")
        uMoonDir = GLES20.glGetUniformLocation(skyProgram, "uMoonDir")

        // Lat-long sphere
        val lat = 14
        val lon = 24
        val radius = 40f
        val verts = FloatArray((lat + 1) * (lon + 1) * 3)
        var i = 0
        for (a in 0..lat) {
            val theta = PI * a / lat
            val st = sin(theta); val ct = cos(theta)
            for (b in 0..lon) {
                val phi = 2f * PI * b / lon
                verts[i++] = (radius * st * cos(phi))
                verts[i++] = (radius * ct)
                verts[i++] = (radius * st * sin(phi))
            }
        }
        skyVao = GLES30.glGenVertexArrays()
        GLES30.glBindVertexArray(skyVao)
        skyVbo = GLES30.glGenBuffers()
        GLES30.glBindBuffer(GLES20.GL_ARRAY_BUFFER, skyVbo)
        val bb = GLUtil.directFloatBuffer(verts)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, bb, GLES20.GL_STATIC_DRAW)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 12, 0)
        GLES20.glEnableVertexAttribArray(0)
        GLES30.glBindVertexArray(0)
    }

    // ------------------------------------------------------------------
    // Stars (GL_POINTS)
    // ------------------------------------------------------------------
    private fun initStars(quality: Int) {
        val vs = """
            attribute vec3 aPos;
            attribute float aSize;
            attribute float aPhase;
            uniform mat4 uProj;
            uniform mat4 uView;
            uniform vec3 uEye;
            uniform float uTime;
            varying float vA;
            void main() {
                gl_Position = uProj * uView * vec4(aPos + uEye, 1.0);
                float tw = 0.72 + 0.28 * sin(uTime * 1.6 + aPhase);
                vA = tw;
                gl_PointSize = aSize;
            }
        """.trimIndent()
        val fs = """
            precision mediump float;
            varying float vA;
            void main() {
                vec2 c = gl_PointCoord - 0.5;
                float r = length(c) * 2.0;
                float a = smoothstep(1.0, 0.15, r) * vA;
                gl_FragColor = vec4(vec3(0.80, 0.87, 1.0) * a, a);
            }
        """.trimIndent()
        starProgram = GLUtil.createProgram(vs, fs, "aPos" to 0, "aSize" to 1, "aPhase" to 2)
        uStarProj = GLES20.glGetUniformLocation(starProgram, "uProj")
        uStarView = GLES20.glGetUniformLocation(starProgram, "uView")
        uStarEye = GLES20.glGetUniformLocation(starProgram, "uEye")
        uStarTime = GLES20.glGetUniformLocation(starProgram, "uTime")

        rebuildStars(quality)
    }

    /** LOW = 260 stars, MEDIUM = 460, HIGH = 720. */
    fun rebuildStars(quality: Int) {
        starCount = when (quality) {
            0 -> 260
            2 -> 720
            else -> 460
        }
        val rnd = Random(20260915)
        val data = FloatArray(starCount * 5)
        var i = 0
        for (s in 0 until starCount) {
            // random direction on a sphere
            val u = rnd.nextFloat() * 2f - 1f
            val t = rnd.nextFloat() * 2f * PI
            val sxy = kotlin.math.sqrt((1f - u * u).coerceAtLeast(0f))
            val r = 38f
            data[i++] = (r * sxy * cos(t))
            data[i++] = (r * u)
            data[i++] = (r * sxy * sin(t))
            data[i++] = (1.2f + rnd.nextFloat() * 2.2f) // size px
            data[i++] = rnd.nextFloat() * 6.28f          // phase
        }
        starVao = GLES30.glGenVertexArrays()
        GLES30.glBindVertexArray(starVao)
        val oldVbo = starVbo
        starVbo = GLES30.glGenBuffers()
        GLES30.glBindBuffer(GLES20.GL_ARRAY_BUFFER, starVbo)
        val bb = GLUtil.directFloatBuffer(data)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, bb, GLES20.GL_STATIC_DRAW)
        // aPos
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 20, 0)
        GLES20.glEnableVertexAttribArray(0)
        // aSize
        GLES20.glVertexAttribPointer(1, 1, GLES20.GL_FLOAT, false, 20, 12)
        GLES20.glEnableVertexAttribArray(1)
        // aPhase
        GLES20.glVertexAttribPointer(2, 1, GLES20.GL_FLOAT, false, 20, 16)
        GLES20.glEnableVertexAttribArray(2)
        GLES30.glBindVertexArray(0)
        if (oldVbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(oldVbo), 0)
    }

    // ------------------------------------------------------------------
    // Floor disc (radial glow + ring)
    // ------------------------------------------------------------------
    private fun initFloor() {
        val vs = """
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
        val fs = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uTex;
            uniform float uAlpha;
            void main() {
                vec4 c = texture2D(uTex, vUv);
                gl_FragColor = vec4(c.rgb, c.a * uAlpha);
            }
        """.trimIndent()
        floorProgram = GLUtil.createProgram(vs, fs, "aPos" to 0, "aUv" to 1)
        uFloorModel = GLES20.glGetUniformLocation(floorProgram, "uModel")
        uFloorView = GLES20.glGetUniformLocation(floorProgram, "uView")
        uFloorProj = GLES20.glGetUniformLocation(floorProgram, "uProj")
        uFloorTex = GLES20.glGetUniformLocation(floorProgram, "uTex")
        uFloorAlpha = GLES20.glGetUniformLocation(floorProgram, "uAlpha")

        // 4-vertex quad, 10m x 10m centered
        val verts = floatArrayOf(
            -5f, 0f, -5f, 0f, 1f,
            5f, 0f, -5f, 1f, 1f,
            -5f, 0f, 5f, 0f, 0f,
            5f, 0f, 5f, 1f, 0f
        )
        floorVao = GLES30.glGenVertexArrays()
        GLES30.glBindVertexArray(floorVao)
        floorVbo = GLES30.glGenBuffers()
        GLES30.glBindBuffer(GLES20.GL_ARRAY_BUFFER, floorVbo)
        val bb = GLUtil.directFloatBuffer(verts)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, bb, GLES20.GL_STATIC_DRAW)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 20, 0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 20, 12)
        GLES20.glEnableVertexAttribArray(1)
        GLES30.glBindVertexArray(0)

        // radial glow texture
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(
            size / 2f, size / 2f, size / 2f,
            intArrayOf(0x66243A66.toInt(), 0x331B2A4A.toInt(), 0x11142038.toInt(), 0x00000000),
            floatArrayOf(0f, 0.45f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        cv.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        // stage ring under the user
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = 0x2E5B7FB8.toInt()
        cv.drawCircle(size / 2f, size / 2f, size * 0.28f, paint)
        paint.strokeWidth = 2.5f
        paint.color = 0x1A4A6A9E.toInt()
        cv.drawCircle(size / 2f, size / 2f, size * 0.46f, paint)
        floorTex = GLUtil.createTexture(size, size)
        GLUtil.uploadBitmap(floorTex, bmp)
        bmp.recycle()
    }

    // ------------------------------------------------------------------
    // Draw calls
    // ------------------------------------------------------------------
    fun drawSky(proj: Mat4, view: Mat4, eyePos: Vec3) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(skyProgram)
        GLES20.glUniformMatrix4fv(uSkyProj, 1, false, proj.m, 0)
        GLES20.glUniformMatrix4fv(uSkyView, 1, false, view.m, 0)
        GLES20.glUniform3f(uSkyEye, eyePos.x, eyePos.y, eyePos.z)
        GLES20.glUniform3f(uMoonDir, moonDir.x, moonDir.y, moonDir.z)
        GLES30.glBindVertexArray(skyVao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 15 * 25)
        GLES30.glBindVertexArray(0)
    }

    fun drawStars(proj: Mat4, view: Mat4, eyePos: Vec3, timeSec: Float) {
        if (starCount == 0) return
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(starProgram)
        GLES20.glUniformMatrix4fv(uStarProj, 1, false, proj.m, 0)
        GLES20.glUniformMatrix4fv(uStarView, 1, false, view.m, 0)
        GLES20.glUniform3f(uStarEye, eyePos.x, eyePos.y, eyePos.z)
        GLES20.glUniform1f(uStarTime, timeSec)
        GLES30.glBindVertexArray(starVao)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, starCount)
        GLES30.glBindVertexArray(0)
        GLES20.glDepthMask(true)
    }

    fun drawFloor(proj: Mat4, view: Mat4) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(floorProgram)
        val model = Mat4().compose(Vec3(0f, -1.05f, 0f), Quat.fromAxisAngle(1f, 0f, 0f, -PI / 2f), Vec3(1f, 1f, 1f))
        GLES20.glUniformMatrix4fv(uFloorModel, 1, false, model.m, 0)
        GLES20.glUniformMatrix4fv(uFloorView, 1, false, view.m, 0)
        GLES20.glUniformMatrix4fv(uFloorProj, 1, false, proj.m, 0)
        GLES30.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, floorTex)
        GLES20.glUniform1i(uFloorTex, 0)
        GLES20.glUniform1f(uFloorAlpha, 0.9f)
        GLES30.glBindVertexArray(floorVao)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES20.glDepthMask(true)
    }

    /** GL context was destroyed: objects are gone, just reset the ids. */
    fun contextLost() {
        skyProgram = 0; starProgram = 0; floorProgram = 0
        skyVao = 0; starVao = 0; floorVao = 0
        skyVbo = 0; starVbo = 0; floorVbo = 0
        floorTex = 0
    }

    fun release() {
        if (skyProgram != 0) GLES20.glDeleteProgram(skyProgram)
        if (starProgram != 0) GLES20.glDeleteProgram(starProgram)
        if (floorProgram != 0) GLES20.glDeleteProgram(floorProgram)
        if (skyVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(skyVao), 0)
        if (starVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(starVao), 0)
        if (floorVao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(floorVao), 0)
        if (skyVbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(skyVbo), 0)
        if (starVbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(starVbo), 0)
        if (floorVbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(floorVbo), 0)
        if (floorTex != 0) GLES20.glDeleteTextures(1, intArrayOf(floorTex), 0)
        skyProgram = 0; starProgram = 0; floorProgram = 0
        skyVao = 0; starVao = 0; floorVao = 0
        skyVbo = 0; starVbo = 0; floorVbo = 0; floorTex = 0
        starCount = 0
    }
}
