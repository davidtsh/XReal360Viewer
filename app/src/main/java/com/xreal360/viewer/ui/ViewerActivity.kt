package com.xreal360.viewer.ui

import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.xreal360.viewer.databinding.ActivityViewerBinding
import com.xreal360.viewer.gl.Panorama360Renderer
import com.xreal360.viewer.sensors.HeadTracker

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_IS_VIDEO = "is_video"
    }

    private lateinit var binding: ActivityViewerBinding
    private lateinit var renderer: Panorama360Renderer
    private lateinit var headTracker: HeadTracker
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Full-screen immersive
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        val uriString = intent.getStringExtra(EXTRA_URI) ?: return finish()
        val isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        val uri = Uri.parse(uriString)

        // Setup GL
        renderer = Panorama360Renderer(this)

        // Load media after GL surface is ready
        if (isVideo) {
            renderer.onVideoSurfaceReady = { surfaceTexture ->
                runOnUiThread { setupVideo(uri, surfaceTexture) }
            }
            renderer.switchToVideo()
        } else {
            loadImage(uri)
        }

        binding.glSurfaceView.apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }

        // Head tracker
        headTracker = HeadTracker(this).apply {
            listener = object : HeadTracker.Listener {
                override fun onRotationMatrix(matrix: FloatArray) {
                    renderer.setRotationMatrix(matrix)
                }
            }
        }

        binding.glSurfaceView.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_AUTO_OPEN, true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun loadImage(uri: Uri) {
        Thread {
            try {
                val stream = contentResolver.openInputStream(uri) ?: return@Thread
                val bitmap = BitmapFactory.decodeStream(stream)
                stream.close()
                if (bitmap != null) {
                    renderer.loadBitmap(bitmap)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun setupVideo(uri: Uri, surfaceTexture: android.graphics.SurfaceTexture) {
        val surface = android.view.Surface(surfaceTexture)
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            setVideoSurface(surface)
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            prepare()
            playWhenReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        binding.glSurfaceView.onResume()
        headTracker.start()
        exoPlayer?.playWhenReady = true
    }

    override fun onPause() {
        super.onPause()
        binding.glSurfaceView.onPause()
        headTracker.stop()
        exoPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
    }
}
