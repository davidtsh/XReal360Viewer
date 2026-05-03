package com.xreal360.viewer.gl

import android.opengl.GLES20
import android.util.Log

class ShaderProgram(vertexSrc: String, fragmentSrc: String) {

    val programId: Int

    init {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        programId = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
    }

    private fun compileShader(type: Int, src: String): Int {
        return GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, src)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                Log.e("ShaderProgram", GLES20.glGetShaderInfoLog(shader))
            }
        }
    }

    fun use() = GLES20.glUseProgram(programId)

    fun attrib(name: String) = GLES20.glGetAttribLocation(programId, name)
    fun uniform(name: String) = GLES20.glGetUniformLocation(programId, name)
}
