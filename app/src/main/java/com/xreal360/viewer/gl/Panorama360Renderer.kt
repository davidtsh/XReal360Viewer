package com.xreal360.viewer.gl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class Panorama360Renderer(private val context: Context) : GLSurfaceView.Renderer {

    private val projectionMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val rotationMatrix = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    private lateinit var sphere: SphereGeometry
    private lateinit var shader: ShaderProgram

    private val textures = IntArray(2)
    private var useVideoTexture = false

    var surfaceTexture: SurfaceTexture? = null
        private set

    @Volatile private var pendingBitmap: Bitmap? = null
    @Volatile private var videoTextureUpdated = false
    @Volatile private var bitmapUploaded = false

    var onVideoSurfaceReady: ((SurfaceTexture) -> Unit)? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        sphere = SphereGeometry(radius = 50f, stacks = 64, slices = 64)

        val vertSrc = context.resources.openRawResource(
            context.resources.getIdentifier("sphere_vertex", "raw", context.packageName)
        ).bufferedReader().readText()

        val fragSrc = context.resources.openRawResource(
            context.resources.getIdentifier("sphere_fragment_new", "raw", context.packageName)
        ).bufferedReader().readText()

        shader = ShaderProgram(vertSrc, fragSrc)

        GLES20.glGenTextures(2, textures, 0)

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[1])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        surfaceTexture = SurfaceTexture(textures[1]).also { st ->
            st.setOnFrameAvailableListener { videoTextureUpdated = true }
            onVideoSurfaceReady?.invoke(st)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val ratio = width.toFloat() / height
        Matrix.perspectiveM(projectionMatrix, 0, 90f, ratio, 0.1f, 200f)
    }

    override fun onDrawFrame(gl: GL10?) {
        pendingBitmap?.let { bmp ->
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
            val maxSize = 4096
            val scaled = if (bmp.width > maxSize || bmp.height > maxSize) {
                val scale = maxSize.toFloat() / maxOf(bmp.width, bmp.height)
                Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            } else bmp
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, scaled, 0)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            pendingBitmap = null
            bitmapUploaded = true
            useVideoTexture = false
            Log.d("Renderer", "Bitmap uploaded")
        }

        if (videoTextureUpdated) {
            surfaceTexture?.updateTexImage()
            videoTextureUpdated = false
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (!bitmapUploaded && !useVideoTexture) return

        shader.use()

        // Just apply rotation - camera sits at origin inside the sphere
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, rotationMatrix, 0)
        GLES20.glUniformMatrix4fv(shader.uniform("uMVPMatrix"), 1, false, mvpMatrix, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glUniform1i(shader.uniform("uImageTexture"), 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[1])
        GLES20.glUniform1i(shader.uniform("uVideoTexture"), 1)

        GLES20.glUniform1i(shader.uniform("uUseVideo"), if (useVideoTexture) 1 else 0)

        val posLoc = shader.attrib("aPosition")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 3, GLES20.GL_FLOAT, false, 0, sphere.vertexBuffer)

        val texLoc = shader.attrib("aTexCoord")
        GLES20.glEnableVertexAttribArray(texLoc)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, sphere.texCoordBuffer)

        GLES20.glDrawElements(GLES20.GL_TRIANGLES, sphere.indexCount, GLES20.GL_UNSIGNED_SHORT, sphere.indexBuffer)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    fun loadBitmap(bitmap: Bitmap) {
        pendingBitmap = bitmap
        bitmapUploaded = false
    }

    fun switchToVideo() {
        useVideoTexture = true
    }

    fun setRotationMatrix(matrix: FloatArray) {
        System.arraycopy(matrix, 0, rotationMatrix, 0, 16)
    }
}
