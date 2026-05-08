package com.xreal360.viewer.sensors
import android.content.Context
import android.opengl.Matrix
import android.util.Log
import io.onexr.OneXrClient
import io.onexr.OneXrEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import io.onexr.XrPoseDataMode
import kotlin.math.cos
import kotlin.math.sin

class HeadTracker(private val context: Context) {

    interface Listener {
        fun onRotationMatrix(matrix: FloatArray)
        fun onOrientation(yaw: Float, pitch: Float, roll: Float) = Unit
    }

    var listener: Listener? = null

    private var client: OneXrClient? = null
    private var collectJob: Job? = null
    private var startJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        client = OneXrClient(context, OneXrEndpoint()).apply {
            setPoseDataMode(XrPoseDataMode.SMOOTH_IMU)
        }

        startJob = scope.launch {
            try {
                val info = client!!.start()
                Log.d(TAG, "Connected to XREAL One: $info")
                // Request a zero view (re-center) after connection to establish a stable baseline
                client!!.zeroView()
            } catch (e: Exception) {
                Log.w(TAG, "Connection failed: ${e.message}")
            }
        }

        collectJob = scope.launch {
            client!!.poseData.collect { pose ->
                pose?.let {
                    val yaw   = it.relativeOrientation.yaw
                    val pitch = it.relativeOrientation.pitch
                    val roll  = it.relativeOrientation.roll

                    listener?.onOrientation(yaw, pitch, roll)

                    val mat = FloatArray(16)

                    val yawRadians = Math.toRadians((-yaw).toDouble())
                    val pitchRadians = Math.toRadians((-pitch).toDouble())
                    val cosPitch = cos(pitchRadians)

                    val lookX = (cosPitch * cos(yawRadians)).toFloat()
                    val lookY = sin(pitchRadians).toFloat()
                    val lookZ = (-cosPitch * sin(yawRadians)).toFloat()

                    Matrix.setLookAtM(
                        mat,
                        0,
                        0f,
                        0f,
                        0f,
                        lookX,
                        lookY,
                        lookZ,
                        0f,
                        1f,
                        0f
                    )

                    listener?.onRotationMatrix(mat)
                }
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        startJob?.cancel()
        scope.launch { client?.stop() }
        client = null
    }

    companion object { private const val TAG = "HeadTracker" }
}
