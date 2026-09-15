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
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, if (mipmaps) GLES20.GL_LINEAR_MIPMAP_LINEAR else GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        if (mipmaps) {
            GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES30.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        }
        return tex[0]
    }

    /** Uploads a full bitmap to an existing texture (replaces content). */
    fun uploadBitmap(texId: Int, bitmap: Bitmap) {
        GLES30.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    /** Uploads a rectangular region of a bitmap into an existing texture. */
    fun uploadBitmapRegion(texId: Int, bitmap: Bitmap, left: Int, top: Int) {
        GLES30.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, left, top, bitmap, left, top)
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
