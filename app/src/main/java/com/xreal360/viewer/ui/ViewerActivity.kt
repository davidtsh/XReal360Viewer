package com.xreal360.viewer.ui

import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
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
        private const val FAST_FORWARD_INTERVAL_MS = 250L
        private const val FAST_FORWARD_EXTRA_MS = FAST_FORWARD_INTERVAL_MS * 4

        // Shake detection
        private const val SHAKE_THRESHOLD_G = 2.5f
        private const val SHAKE_COOLDOWN_MS = 1000L

        // Yaw animation
        private const val YAW_ANIMATION_DURATION_MS = 600L
        private const val YAW_TARGET_DEGREES = 180f
    }

    private lateinit var binding: ActivityViewerBinding
    private lateinit var renderer: Panorama360Renderer
    private lateinit var headTracker: HeadTracker
    private var exoPlayer: ExoPlayer? = null
    @Volatile private var videoSurfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var mediaUris: List<Uri> = emptyList()
    private var currentIndex = 0
    @Volatile private var mediaGeneration = 0
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var zoomFactor = 1f
    private var pinchStartDistance = 0f
    private var pinchStartZoomFactor = 1f
    private var isPinching = false
    private var gestureWasPinch = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val videoFastForwardRunnable = Runnable { startVideoFastForward() }
    private val videoFastForwardTick = object : Runnable {
        override fun run() {
            fastForwardStep()
            if (isFastForwarding) {
                longPressHandler.postDelayed(this, FAST_FORWARD_INTERVAL_MS)
            }
        }
    }
    private var isFastForwarding = false
    private var gestureWasFastForward = false

    // Shake detection
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var lastShakeTimeMs = 0L

    // Yaw animation state (accessed only from GL thread)
    @Volatile private var isYawAnimating = false
    private var yawAnimationStartMs = 0L
    private var yawAnimationStartDegrees = 0f
    private var accumulatedYawDegrees = 0f

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val gForce = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
            if (gForce > SHAKE_THRESHOLD_G) {
                val now = System.currentTimeMillis()
                if (now - lastShakeTimeMs > SHAKE_COOLDOWN_MS) {
                    lastShakeTimeMs = now
                    triggerYawAnimation()
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

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

        // Shake / accelerometer
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

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
                    val finalMatrix = if (isYawAnimating) {
                        applyYawAnimation(matrix)
                    } else {
                        applyStaticYaw(matrix)
                    }
                    renderer.setRotationMatrix(finalMatrix)
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
                    cancelVideoFastForward()
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
                        cancelVideoFastForward()
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
                    cancelVideoFastForward()
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
                    cancelVideoFastForward()
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

    // Called from shake listener on sensor thread — safe, only sets volatiles
    private fun triggerYawAnimation() {
        yawAnimationStartDegrees = accumulatedYawDegrees
        yawAnimationStartMs = System.currentTimeMillis()
        isYawAnimating = true
    }

    // Called from HeadTracker callback — applies in-progress yaw animation on top of head rotation
    private fun applyYawAnimation(headMatrix: FloatArray): FloatArray {
        val elapsed = System.currentTimeMillis() - yawAnimationStartMs
        val t = (elapsed.toFloat() / YAW_ANIMATION_DURATION_MS).coerceIn(0f, 1f)
        val eased = easeInOut(t)
        accumulatedYawDegrees = yawAnimationStartDegrees + eased * YAW_TARGET_DEGREES
        if (t >= 1f) {
            accumulatedYawDegrees = yawAnimationStartDegrees + YAW_TARGET_DEGREES
            isYawAnimating = false
        }
        return buildYawedMatrix(headMatrix, accumulatedYawDegrees)
    }

    // Called when no animation is running — just applies the current accumulated yaw
    private fun applyStaticYaw(headMatrix: FloatArray): FloatArray {
        return buildYawedMatrix(headMatrix, accumulatedYawDegrees)
    }

    private fun buildYawedMatrix(headMatrix: FloatArray, yawDegrees: Float): FloatArray {
        val yawMatrix = FloatArray(16)
        Matrix.setRotateM(yawMatrix, 0, yawDegrees, 0f, 1f, 0f)
        val result = FloatArray(16)
        Matrix.multiplyMM(result, 0, yawMatrix, 0, headMatrix, 0)
        return result
    }

    // Smooth ease-in-out curve: 3t²-2t³
    private fun easeInOut(t: Float): Float = t * t * (3f - 2f * t)

    private fun showMedia(index: Int) {
        if (mediaUris.isEmpty()) return
        cancelVideoFastForward()
        currentIndex = wrapIndex(index)
        val targetGeneration = ++mediaGeneration
        val targetIndex = currentIndex
        val uri = mediaUris[currentIndex]

        if (isVideoUri(uri)) {
            releaseVideo()
            if (videoSurfaceTexture == null) {
                binding.glSurfaceView.queueEvent {
                    if (mediaGeneration == targetGeneration) {
                        renderer.switchToVideo()
                    }
                }
                return
            }
            binding.glSurfaceView.queueEvent {
                if (mediaGeneration != targetGeneration) return@queueEvent
                val surfaceTexture = renderer.resetVideoTexture()
                runOnUiThread {
                    videoSurfaceTexture = surfaceTexture
                    val stillCurrent = mediaGeneration == targetGeneration &&
                            currentIndex == targetIndex &&
                            mediaUris.getOrNull(currentIndex) == uri
                    if (stillCurrent) {
                        setupVideo(uri, surfaceTexture)
                    }
                }
            }
        } else {
            releaseVideo()
            binding.glSurfaceView.queueEvent {
                if (mediaGeneration == targetGeneration) {
                    renderer.switchToImage()
                }
            }
            loadImage(uri, targetGeneration)
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
        cancelVideoFastForward()
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
        longPressHandler.post(videoFastForwardTick)
    }

    private fun cancelVideoFastForward() {
        longPressHandler.removeCallbacks(videoFastForwardRunnable)
        longPressHandler.removeCallbacks(videoFastForwardTick)
        isFastForwarding = false
    }

    private fun fastForwardStep() {
        val player = exoPlayer ?: return
        if (!isCurrentVideo()) return
        val duration = player.duration
        val currentPosition = player.currentPosition
        val targetPosition = if (duration != C.TIME_UNSET && duration > 0L) {
            minOf(currentPosition + FAST_FORWARD_EXTRA_MS, duration)
        } else {
            currentPosition + FAST_FORWARD_EXTRA_MS
        }
        player.seekTo(targetPosition)
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

    private fun loadImage(uri: Uri, targetGeneration: Int) {
        Thread {
            try {
                val stream = contentResolver.openInputStream(uri) ?: return@Thread
                val bitmap = BitmapFactory.decodeStream(stream)
                stream.close()
                if (bitmap != null) {
                    if (mediaGeneration == targetGeneration) {
                        renderer.loadBitmap(bitmap)
                    } else {
                        bitmap.recycle()
                    }
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
            prepare()
            playWhenReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        binding.glSurfaceView.onResume()
        headTracker.start()
        exoPlayer?.playWhenReady = true
        accelerometer?.let {
            sensorManager.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        super.onPause()
        cancelVideoFastForward()
        binding.glSurfaceView.onPause()
        headTracker.stop()
        exoPlayer?.playWhenReady = false
        sensorManager.unregisterListener(shakeListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseVideo()
    }

    private fun releaseVideo() {
        cancelVideoFastForward()
        exoPlayer?.clearVideoSurface()
        exoPlayer?.release()
        exoPlayer = null
        videoSurface?.release()
        videoSurface = null
    }
}