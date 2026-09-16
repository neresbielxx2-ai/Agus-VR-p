package com.lunarvr.scene

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Helper routines for the GL layer. */
object GLUtil {

    const val TAG = "LunarVR.GL"

    /**
     * Builds a direct ByteBuffer with [count] floats copied from [data] at
     * [offset], ready for glBufferData/glBufferSubData (position 0).
     */
    fun directFloatBuffer(data: FloatArray, offset: Int = 0, count: Int = data.size): ByteBuffer {
        val bb = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(data, offset, count)
        fb.position(0)
        bb.position(0)
        return bb
    }

    fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compile failed: $log")
            throw RuntimeException("Shader compile failed: $log")
        }
        return shader
    }

    fun createProgram(vertexSrc: String, fragmentSrc: String, vararg attribs: Pair<String, Int>): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        // Bind attribute locations BEFORE linking so the VAO setup in the
        // callers (hardcoded locations) is always correct.
        for ((name, loc) in attribs) {
            GLES20.glBindAttribLocation(program, loc, name)
        }
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            Log.e(TAG, "Program link failed: $log")
            throw RuntimeException("Program link failed: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    fun createTexture(width: Int, height: Int, mipmaps: Boolean = false): Int {
        val tex = IntArray(1)
        GLES20.glGenTextures(1, tex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, if (mipmaps) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (mipmaps) {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        }
        return tex[0]
    }

    /** Uploads a full bitmap to an existing texture (replaces content). */
    fun uploadBitmap(texId: Int, bitmap: Bitmap) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    /** Uploads a rectangular region of a bitmap into an existing texture. */
    fun uploadBitmapRegion(texId: Int, bitmap: Bitmap, left: Int, top: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, left, top, bitmap, left, top)
    }

    // ------------------------------------------------------------------
    // ES2/ES3 compatibility (VAO emulation)
    //
    // OpenGL ES 2.0 has no vertex array objects: attribute state (pointer +
    // enabled flag, per location) is GLOBAL. To keep the renderers written
    // against the VAO model, we record each "vao"'s attribute configuration
    // when it is created and re-apply it (buffer bind + pointer + enable/
    // disable per location) every time it is "bound".
    // ------------------------------------------------------------------
    private class VaoAttrib {
        var buffer = 0
        var size = 0
        var type = 0
        var stride = 0
        var offset = 0
    }

    private class VaoState {
        val attrs = HashMap<Int, VaoAttrib>()
    }

    /** True when the current context is ES 3.x (from glGetString). */
    var isES3 = false
        private set

    private val vaoStates = HashMap<Int, VaoState>()
    private var es2VaoCounter = 0
    private var maxAttribs = 16

    /** Call on the GL thread once per context, before any other GL init. */
    fun initCompat() {
        val a = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_VERTEX_ATTRIBS, a, 0)
        if (a[0] > 0) maxAttribs = a[0]
        val v = GLES20.glGetString(GLES20.GL_VERSION) ?: ""
        isES3 = v.startsWith("OpenGL ES 3")
        vaoStates.clear()
        es2VaoCounter = 0
        Log.i(TAG, "GL version: $v (ES3=$isES3, maxAttribs=$maxAttribs)")
    }

    /** Clears the ES2 state map — call when the GL context is destroyed. */
    fun resetCompat() {
        vaoStates.clear()
        es2VaoCounter = 0
    }

    /** Android's GLES30 class only exposes GLES3-specific entry points; the
     *  glGen*/glDelete* calls come in (count, int[], offset) form. */
    fun genVertexArray(): Int {
        if (isES3) {
            val a = IntArray(1)
            GLES30.glGenVertexArrays(1, a, 0)
            return a[0]
        }
        es2VaoCounter += 1
        return es2VaoCounter
    }

    fun bindVertexArray(vaoId: Int) {
        if (isES3) {
            GLES30.glBindVertexArray(vaoId)
            return
        }
        val state = vaoStates[vaoId] ?: return
        for (loc in 0 until maxAttribs) {
            val a = state.attrs[loc]
            if (a == null) {
                GLES20.glDisableVertexAttribArray(loc)
            } else {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, a.buffer)
                GLES20.glVertexAttribPointer(loc, a.size, a.type, false, a.stride, a.offset)
                GLES20.glEnableVertexAttribArray(loc)
            }
        }
    }

    fun delVertexArray(vaoId: Int) {
        if (vaoId == 0) return
        if (isES3) GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
        vaoStates.remove(vaoId)
    }

    /**
     * Sets the attribute pointer for [loc] of the "vao" [vaoId]. Call while
     * the vao is bound (as in the original VAO code) and with the source
     * buffer already bound to GL_ARRAY_BUFFER — the ES2 emulation captures
     * that binding into the per-vao state.
     */
    fun attrPointer(vaoId: Int, loc: Int, size: Int, type: Int, stride: Int, offset: Int) {
        if (!isES3) {
            val binding = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING, binding, 0)
            val st = vaoStates.getOrPut(vaoId) { VaoState() }
            val a = st.attrs.getOrPut(loc) { VaoAttrib() }
            a.buffer = binding[0]
            a.size = size
            a.type = type
            a.stride = stride
            a.offset = offset
        }
        GLES20.glVertexAttribPointer(loc, size, type, false, stride, offset)
        GLES20.glEnableVertexAttribArray(loc)
    }

    fun genBuffer(): Int {
        val b = IntArray(1)
        GLES20.glGenBuffers(1, b, 0)
        return b[0]
    }

    fun delBuffer(id: Int) {
        if (id != 0) GLES20.glDeleteBuffers(1, intArrayOf(id), 0)
    }

    fun genTexture(): Int {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        return t[0]
    }

    fun delTexture(id: Int) {
        if (id != 0) GLES20.glDeleteTextures(1, intArrayOf(id), 0)
    }

    fun genFramebuffer(): Int {
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        return f[0]
    }

    fun delFramebuffer(id: Int) {
        if (id != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(id), 0)
    }

    fun genRenderbuffer(): Int {
        val r = IntArray(1)
        GLES20.glGenRenderbuffers(1, r, 0)
        return r[0]
    }

    fun delRenderbuffer(id: Int) {
        if (id != 0) GLES20.glDeleteRenderbuffers(1, intArrayOf(id), 0)
    }

    fun checkError(context: String) {
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "GL error 0x${Integer.toHexString(err)} in $context")
        }
    }

    fun toOESMatrix(mat: com.lunarvr.math.Mat4): FloatArray {
        return mat.m
    }
}
