package com.xreal360.viewer.sensors

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.opengl.Matrix
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class NrealAirUsbTracker(context: Context) {

    var listener: HeadTracker.Listener? = null

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbReceiver: BroadcastReceiver? = null
    private var receiverRegistered = false

    @Volatile private var running = false
    private var readerThread: Thread? = null
    private var connection: UsbDeviceConnection? = null
    private var imuInterface: UsbInterface? = null

    private var gyroBiasX = 0f
    private var gyroBiasY = 0f
    private var gyroBiasZ = 0f
    private var biasSamples = 0

    private var yaw = 0f
    private var pitch = 0f
    private var roll = 0f

    fun start() {
        registerUsbReceiver()
        val device = findNrealAirDevice()
        if (device == null) {
            Log.w(TAG, "Nreal Air USB device not found.")
            return
        }
        openOrRequestPermission(device)
    }

    fun stop() {
        stopReader()
        closeConnection()
        unregisterUsbReceiver()
    }

    private fun openOrRequestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            openDevice(device)
            return
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            appContext,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(appContext.packageName),
            flags
        )
        Log.d(TAG, "Requesting Nreal Air USB permission.")
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun openDevice(device: UsbDevice) {
        if (!isNrealAir(device)) return
        stopReader()
        closeConnection()

        val interfaceToClaim = findHidInterface(device)
        if (interfaceToClaim == null) {
            Log.w(TAG, "Nreal Air IMU HID interface not found.")
            return
        }

        val endpoints = findImuEndpoints(interfaceToClaim)
        if (endpoints == null) {
            Log.w(TAG, "Nreal Air IMU endpoints not found.")
            return
        }

        val openedConnection = usbManager.openDevice(device)
        if (openedConnection == null) {
            Log.w(TAG, "Could not open Nreal Air USB connection.")
            return
        }

        if (!openedConnection.claimInterface(interfaceToClaim, true)) {
            Log.w(TAG, "Could not claim Nreal Air IMU interface.")
            openedConnection.close()
            return
        }

        connection = openedConnection
        imuInterface = interfaceToClaim
        resetOrientation()
        running = true
        readerThread = Thread(
            { readImuLoop(openedConnection, endpoints.first, endpoints.second) },
            "NrealAirImu"
        ).apply { start() }
        Log.d(TAG, "Nreal Air USB head tracker started.")
    }

    private fun readImuLoop(
        openedConnection: UsbDeviceConnection,
        imuIn: UsbEndpoint,
        imuOut: UsbEndpoint
    ) {
        openedConnection.bulkTransfer(imuOut, START_IMU_PAYLOAD, START_IMU_PAYLOAD.size, USB_TIMEOUT_MS)

        val buffer = ByteArray(IMU_PACKET_SIZE)
        var lastTimestampNs = 0L
        while (running && !Thread.currentThread().isInterrupted) {
            val read = openedConnection.bulkTransfer(imuIn, buffer, buffer.size, USB_TIMEOUT_MS)
            if (read == IMU_PACKET_SIZE && isValidImuPacket(buffer)) {
                val sample = decodeImuSample(buffer)
                if (lastTimestampNs != 0L && sample.timestampNs > lastTimestampNs) {
                    val dt = ((sample.timestampNs - lastTimestampNs) / 1_000_000_000f)
                        .coerceIn(MIN_DELTA_SECONDS, MAX_DELTA_SECONDS)
                    updateOrientation(sample, dt)
                }
                lastTimestampNs = sample.timestampNs
            }
        }
    }

    private fun updateOrientation(sample: ImuSample, dt: Float) {
        val gyroX = sample.gyroX * GYRO_SCALE_DPS
        val gyroY = sample.gyroY * GYRO_SCALE_DPS
        val gyroZ = sample.gyroZ * GYRO_SCALE_DPS

        if (biasSamples < GYRO_BIAS_SAMPLE_COUNT) {
            gyroBiasX += gyroX
            gyroBiasY += gyroY
            gyroBiasZ += gyroZ
            biasSamples++
            if (biasSamples == GYRO_BIAS_SAMPLE_COUNT) {
                gyroBiasX /= GYRO_BIAS_SAMPLE_COUNT
                gyroBiasY /= GYRO_BIAS_SAMPLE_COUNT
                gyroBiasZ /= GYRO_BIAS_SAMPLE_COUNT
                Log.d(TAG, "Nreal Air gyro calibrated.")
            }
            return
        }

        val appPitchRate = (gyroX - gyroBiasX) * NREAL_PITCH_SIGN
        val appRollRate = (gyroY - gyroBiasY) * NREAL_ROLL_SIGN
        val appYawRate = (gyroZ - gyroBiasZ) * NREAL_YAW_SIGN

        pitch += appPitchRate * dt
        roll += appRollRate * dt
        yaw += appYawRate * dt

        val accelX = sample.accelX * ACCEL_SCALE_G
        val accelY = sample.accelY * ACCEL_SCALE_G
        val accelZ = sample.accelZ * ACCEL_SCALE_G
        val accelMagnitude = sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)
        if (accelMagnitude in 0.75f..1.25f) {
            val accelPitch = Math.toDegrees(atan2(accelY.toDouble(), accelZ.toDouble())).toFloat() *
                NREAL_PITCH_SIGN
            val accelRoll = Math.toDegrees(
                atan2(
                    -accelX.toDouble(),
                    sqrt((accelY * accelY + accelZ * accelZ).toDouble())
                )
            ).toFloat() * NREAL_ROLL_SIGN
            roll = blendDegrees(roll, accelRoll)
            pitch = blendDegrees(pitch, accelPitch)
        }

        val normalizedYaw = normalizeDegrees(yaw)
        val normalizedPitch = pitch.coerceIn(-89f, 89f)
        val normalizedRoll = normalizeDegrees(roll)
        listener?.onOrientation(normalizedYaw, normalizedPitch, normalizedRoll)
        listener?.onRotationMatrix(buildRotationMatrix(normalizedYaw, normalizedPitch))
    }

    private fun buildRotationMatrix(yawDegrees: Float, pitchDegrees: Float): FloatArray {
        val mat = FloatArray(16)
        val yawRadians = Math.toRadians((-yawDegrees).toDouble())
        val pitchRadians = Math.toRadians((-pitchDegrees).toDouble())
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
        return mat
    }

    private fun resetOrientation() {
        gyroBiasX = 0f
        gyroBiasY = 0f
        gyroBiasZ = 0f
        biasSamples = 0
        yaw = 0f
        pitch = 0f
        roll = 0f
    }

    private fun closeConnection() {
        val openedConnection = connection
        val openedInterface = imuInterface
        if (openedConnection != null && openedInterface != null) {
            runCatching { openedConnection.releaseInterface(openedInterface) }
        }
        runCatching { openedConnection?.close() }
        connection = null
        imuInterface = null
    }

    private fun registerUsbReceiver() {
        if (receiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = intent.usbDeviceExtra()
                when (intent.action) {
                    ACTION_USB_PERMISSION -> {
                        if (device != null && isNrealAir(device) && usbManager.hasPermission(device)) {
                            openDevice(device)
                        } else {
                            Log.w(TAG, "Nreal Air USB permission was not granted.")
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        if (device != null && isNrealAir(device)) openOrRequestPermission(device)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        if (device != null && isNrealAir(device)) {
                            stopReader()
                            closeConnection()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        usbReceiver = receiver
        receiverRegistered = true
    }

    private fun unregisterUsbReceiver() {
        if (!receiverRegistered) return
        runCatching { appContext.unregisterReceiver(usbReceiver) }
        usbReceiver = null
        receiverRegistered = false
    }

    private fun findNrealAirDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { isNrealAir(it) }

    private fun stopReader() {
        running = false
        readerThread?.interrupt()
        readerThread?.join(500)
        readerThread = null
    }

    private fun findHidInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (
                usbInterface.interfaceClass == UsbConstants.USB_CLASS_HID &&
                usbInterface.interfaceSubclass == IMU_INTERFACE_SUBCLASS &&
                usbInterface.id == IMU_INTERFACE_CLASS
            ) {
                return usbInterface
            }
        }
        return null
    }

    private fun findImuEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index)
            if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_INT) continue
            if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                if (endpoint.address != IMU_IN_ENDPOINT) {
                    Log.w(TAG, "Using IMU input endpoint ${endpoint.address}, expected $IMU_IN_ENDPOINT.")
                }
                input = endpoint
            } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                if (endpoint.address != IMU_OUT_ENDPOINT) {
                    Log.w(TAG, "Using IMU output endpoint ${endpoint.address}, expected $IMU_OUT_ENDPOINT.")
                }
                output = endpoint
            }
        }
        return if (input != null && output != null) Pair(input, output) else null
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private data class ImuSample(
        val timestampNs: Long,
        val gyroX: Int,
        val gyroY: Int,
        val gyroZ: Int,
        val accelX: Int,
        val accelY: Int,
        val accelZ: Int
    )

    companion object {
        private const val TAG = "NrealAirUsbTracker"
        private const val ACTION_USB_PERMISSION = "com.xreal360.viewer.NREAL_AIR_USB_PERMISSION"

        private const val NREAL_AIR_VENDOR_ID = 0x3318
        private const val NREAL_AIR_PRODUCT_ID = 0x0424
        private const val IMU_INTERFACE_CLASS = 3
        private const val IMU_INTERFACE_SUBCLASS = 0
        private const val IMU_IN_ENDPOINT = 0x84
        private const val IMU_OUT_ENDPOINT = 0x05
        private const val IMU_PACKET_SIZE = 64
        private const val TIMESTAMP_OFFSET = 4
        private const val USB_TIMEOUT_MS = 200

        private const val GYRO_SCALE_DPS = 2000f / 8_388_608f
        private const val ACCEL_SCALE_G = 16f / 8_388_608f
        private const val GYRO_BIAS_SAMPLE_COUNT = 60
        private const val ACCEL_CORRECTION_ALPHA = 0.015f
        private const val MIN_DELTA_SECONDS = 0.001f
        private const val MAX_DELTA_SECONDS = 0.05f
        private const val NREAL_PITCH_SIGN = 1f
        private const val NREAL_ROLL_SIGN = 1f
        private const val NREAL_YAW_SIGN = -1f

        private val START_IMU_PAYLOAD = byteArrayOf(
            0xaa.toByte(),
            0xc5.toByte(),
            0xd1.toByte(),
            0x21,
            0x42,
            0x04,
            0x00,
            0x19,
            0x01
        )

        fun isNrealAirAttached(context: Context): Boolean {
            val usbManager = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
            return usbManager.deviceList.values.any { isNrealAir(it) }
        }

        private fun isNrealAir(device: UsbDevice): Boolean =
            device.vendorId == NREAL_AIR_VENDOR_ID && device.productId == NREAL_AIR_PRODUCT_ID

        private fun isValidImuPacket(packet: ByteArray): Boolean =
            packet.size >= IMU_PACKET_SIZE &&
                packet[0] == 1.toByte() &&
                packet[1] == 2.toByte() &&
                packet[12] == 0xa0.toByte() &&
                packet[13] == 0x0f.toByte() &&
                packet[27] == 0x20.toByte() &&
                packet[42] == 0x00.toByte()

        private fun decodeImuSample(packet: ByteArray): ImuSample =
            ImuSample(
                timestampNs = u64Le(packet),
                gyroX = s24Le(packet, 18),
                gyroY = s24Le(packet, 21),
                gyroZ = s24Le(packet, 24),
                accelX = s24Le(packet, 33),
                accelY = s24Le(packet, 36),
                accelZ = s24Le(packet, 39)
            )

        private fun u64Le(packet: ByteArray): Long {
            var value = 0L
            for (index in 0 until 8) {
                value = value or ((packet[TIMESTAMP_OFFSET + index].toLong() and 0xffL) shl (8 * index))
            }
            return value
        }

        private fun s24Le(packet: ByteArray, offset: Int): Int {
            val value = (packet[offset].toInt() and 0xff) or
                ((packet[offset + 1].toInt() and 0xff) shl 8) or
                ((packet[offset + 2].toInt() and 0xff) shl 16)
            return if (value and 0x800000 != 0) value or -0x1000000 else value
        }

        private fun blendDegrees(current: Float, target: Float): Float =
            normalizeDegrees(current + shortestDeltaDegrees(current, target) * ACCEL_CORRECTION_ALPHA)

        private fun shortestDeltaDegrees(from: Float, to: Float): Float =
            normalizeDegrees(to - from)

        private fun normalizeDegrees(value: Float): Float {
            var normalized = value % 360f
            if (normalized > 180f) normalized -= 360f
            if (normalized < -180f) normalized += 360f
            return normalized
        }
    }
}
