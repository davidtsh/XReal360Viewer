package com.xreal360.viewer.ui

import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.xreal360.viewer.databinding.ActivityViewerBinding
import com.xreal360.viewer.gl.Panorama360Renderer
import com.xreal360.viewer.sensors.HeadTracker
import kotlin.math.abs
import kotlin.math.sqrt

class ViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URI = "uri"
        const val EXTRA_URIS = "uris"
        const val EXTRA_INDEX = "index"
        const val EXTRA_IS_VIDEO = "is_video"
        private const val SWIPE_THRESHOLD_PX = 120f
        private const val MIN_ZOOM_FACTOR = 0.65f
        private const val MAX_ZOOM_FACTOR = 3f
        private const val FAST_FORWARD_SPEED = 5f
    }

    private lateinit var binding: ActivityViewerBinding
    private lateinit var renderer: Panorama360Renderer
    private lateinit var headTracker: HeadTracker
    private var exoPlayer: ExoPlayer? = null
    private var videoSurfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var mediaUris: List<Uri> = emptyList()
    private var currentIndex = 0
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var zoomFactor = 1f
    private var pinchStartDistance = 0f
    private var pinchStartZoomFactor = 1f
    private var isPinching = false
    private var gestureWasPinch = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val videoFastForwardRunnable = Runnable { startVideoFastForward() }
    private var isFastForwarding = false
    private var gestureWasFastForward = false

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
        val fallbackUri = Uri.parse(uriString)
        val playlist = intent.getStringArrayListExtra(EXTRA_URIS)
            ?.map { Uri.parse(it) }
            .orEmpty()
        mediaUris = playlist.ifEmpty { listOf(fallbackUri) }
        currentIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, mediaUris.lastIndex)

        // Setup GL
        renderer = Panorama360Renderer(this).apply {
            onVideoSurfaceReady = { surfaceTexture ->
                videoSurfaceTexture = surfaceTexture
                if (isVideoUri(mediaUris[currentIndex])) {
                    runOnUiThread { showMedia(currentIndex) }
                }
            }
        }

        binding.glSurfaceView.apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        showMedia(currentIndex)

        // Head tracker
        headTracker = HeadTracker(this).apply {
            listener = object : HeadTracker.Listener {
                override fun onRotationMatrix(matrix: FloatArray) {
                    renderer.setRotationMatrix(matrix)
                }
            }
        }

        binding.glSurfaceView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = event.x
                    touchDownY = event.y
                    isPinching = false
                    gestureWasPinch = false
                    gestureWasFastForward = false
                    scheduleVideoFastForward()
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelVideoFastForward(resetPlaybackSpeed = true)
                    if (event.pointerCount >= 2) {
                        pinchStartDistance = pointerDistance(event)
                        pinchStartZoomFactor = zoomFactor
                        isPinching = true
                        gestureWasPinch = true
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2 && isPinching && pinchStartDistance > 0f) {
                        val scale = pointerDistance(event) / pinchStartDistance
                        setZoomFactor(pinchStartZoomFactor * scale)
                    } else if (!isFastForwarding && hasMovedPastSwipeThreshold(event)) {
                        cancelVideoFastForward(resetPlaybackSpeed = false)
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount <= 2) {
                        isPinching = false
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val wasFastForwarding = isFastForwarding || gestureWasFastForward
                    cancelVideoFastForward(resetPlaybackSpeed = true)
                    val dx = event.x - touchDownX
                    val dy = event.y - touchDownY
                    val isHorizontalSwipe = abs(dx) > SWIPE_THRESHOLD_PX && abs(dx) > abs(dy)
                    val isVerticalSwipe = abs(dy) > SWIPE_THRESHOLD_PX && abs(dy) > abs(dx)
                    if (wasFastForwarding) {
                        // Long-press fast-forward consumes the gesture.
                    } else if (!gestureWasPinch && (isHorizontalSwipe || isVerticalSwipe)) {
                        val next = if (isHorizontalSwipe) dx < 0f else dy < 0f
                        if (next) showNextMedia() else showPreviousMedia()
                    } else if (!gestureWasPinch) {
                        view.performClick()
                    }
                    isPinching = false
                    gestureWasPinch = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelVideoFastForward(resetPlaybackSpeed = true)
                    isPinching = false
                    gestureWasPinch = false
                    gestureWasFastForward = false
                    true
                }
                else -> true
            }
        }

        binding.glSurfaceView.setOnClickListener {
            openPicker()
        }
    }

    private fun showMedia(index: Int) {
        if (mediaUris.isEmpty()) return
        cancelVideoFastForward(resetPlaybackSpeed = true)
        currentIndex = wrapIndex(index)
        val uri = mediaUris[currentIndex]

        if (isVideoUri(uri)) {
            renderer.switchToVideo()
            videoSurfaceTexture?.let { setupVideo(uri, it) }
        } else {
            releaseVideo()
            loadImage(uri)
        }
    }

    private fun showNextMedia() {
        showMedia(currentIndex + 1)
    }

    private fun showPreviousMedia() {
        showMedia(currentIndex - 1)
    }

    private fun wrapIndex(index: Int): Int {
        val count = mediaUris.size
        return ((index % count) + count) % count
    }

    private fun isVideoUri(uri: Uri): Boolean {
        return contentResolver.getType(uri)?.startsWith("video") == true
    }

    private fun isCurrentVideo(): Boolean {
        return mediaUris.getOrNull(currentIndex)?.let { isVideoUri(it) } == true
    }

    private fun hasMovedPastSwipeThreshold(event: MotionEvent): Boolean {
        return abs(event.x - touchDownX) > SWIPE_THRESHOLD_PX ||
            abs(event.y - touchDownY) > SWIPE_THRESHOLD_PX
    }

    private fun scheduleVideoFastForward() {
        cancelVideoFastForward(resetPlaybackSpeed = false)
        if (!isCurrentVideo()) return
        longPressHandler.postDelayed(
            videoFastForwardRunnable,
            ViewConfiguration.getLongPressTimeout().toLong()
        )
    }

    private fun startVideoFastForward() {
        val player = exoPlayer ?: return
        if (!isCurrentVideo()) return
        isFastForwarding = true
        gestureWasFastForward = true
        player.playWhenReady = true
        player.setPlaybackSpeed(FAST_FORWARD_SPEED)
    }

    private fun cancelVideoFastForward(resetPlaybackSpeed: Boolean) {
        longPressHandler.removeCallbacks(videoFastForwardRunnable)
        if (resetPlaybackSpeed && isFastForwarding) {
            exoPlayer?.setPlaybackSpeed(1f)
        }
        isFastForwarding = false
    }

    private fun openPicker() {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_AUTO_OPEN, true)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun pointerDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun setZoomFactor(value: Float) {
        zoomFactor = value.coerceIn(MIN_ZOOM_FACTOR, MAX_ZOOM_FACTOR)
        binding.glSurfaceView.queueEvent {
            renderer.setZoomFactor(zoomFactor)
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

    private fun setupVideo(uri: Uri, surfaceTexture: SurfaceTexture) {
        releaseVideo()
        val surface = Surface(surfaceTexture)
        videoSurface = surface
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            setVideoSurface(surface)
            repeatMode = ExoPlayer.REPEAT_MODE_ONE
            setPlaybackSpeed(1f)
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
        cancelVideoFastForward(resetPlaybackSpeed = true)
        binding.glSurfaceView.onPause()
        headTracker.stop()
        exoPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseVideo()
    }

    private fun releaseVideo() {
        cancelVideoFastForward(resetPlaybackSpeed = true)
        exoPlayer?.release()
        exoPlayer = null
        videoSurface?.release()
        videoSurface = null
    }
}
