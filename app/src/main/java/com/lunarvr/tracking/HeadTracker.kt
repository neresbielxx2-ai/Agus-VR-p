package com.lunarvr.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.lunarvr.math.Quat
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 3DOF head tracking — orientation only (pitch / yaw / roll).
 *
 * ABSOLUTE RULES implemented here:
 *  - Gyroscope / rotation sensors + quaternion math
 *  - Smoothing (exponential slerp) + micro-jitter correction
 *  - Calibration: current head pose becomes the reference
 *  - NO 6DOF, NO SLAM, NO ARCore/ARKit, NO environment tracking.
 *    Position is never tracked; only the head orientation changes the view.
 *
 * Sensor ladder (first available wins):
 *  1. TYPE_GAME_ROTATION_VECTOR  (quaternion, magnetometer-free)
 *  2. TYPE_ROTATION_VECTOR       (quaternion)
 *  3. TYPE_GYROSCOPE + TYPE_ACCELEROMETER (manual integration + gravity realign)
 *  4. none -> 3DOF disabled, fixed orientation (clear status, never fake)
 *
 * Roll / screen correction is derived at runtime from the accelerometer:
 * C = Rz(atan2(-ax, ay)) aligns the screen-up axis with world up for ANY
 * device orientation (portrait, landscape-left, landscape-right), so no
 * display-rotation sign guessing is ever needed.
 */
class HeadTracker(context: Context) : SensorEventListener {

    enum class Source { NONE, GAME_VECTOR, ROTATION_VECTOR, GYRO_INTEGRATION }

    val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val gameSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    var source: Source = Source.NONE
        private set

    val hasOrientationSensor: Boolean
        get() = gameSensor != null || rotationSensor != null ||
            (gyroSensor != null && accelSensor != null)

    var statusText: String = "Sem dados"
        private set

    // ------------------------------------------------------------------
    // Settings-driven parameters (read every frame, volatile for the GL thread)
    // ------------------------------------------------------------------
    @Volatile var sensitivity: Float = 1f        // 0.5 .. 1.5 angular gain
    @Volatile var smoothing: Float = 0.35f       // 0.0 .. 0.9

    // ------------------------------------------------------------------
    // Sensor data (written by sensor thread, read by render thread)
    // ------------------------------------------------------------------
    private val qSensorRef = AtomicReference(Quat.IDENTITY)
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f
    private var lastAccelNs = 0L
    private var gyroQ = Quat.IDENTITY
    private var lastGyroNs = 0L

    private var started = false

    // Calibration: the head pose captured as the starting reference.
    private var calibQ = Quat.IDENTITY
    private var calibrated = false

    // Final smoothed camera orientation (3DOF).
    private var qOut = Quat.IDENTITY
    val currentOrientation: Quat get() = qOut

    @Synchronized
    fun start() {
        if (started) return
        if (gameSensor != null) {
            source = Source.GAME_VECTOR
            sensorManager.registerListener(this, gameSensor, SensorManager.SENSOR_DELAY_GAME)
            statusText = "Giroscópio OK (game vector)"
        } else if (rotationSensor != null) {
            source = Source.ROTATION_VECTOR
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
            statusText = "Giroscópio OK (rotation vector)"
        } else if (gyroSensor != null && accelSensor != null) {
            source = Source.GYRO_INTEGRATION
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_GAME)
            statusText = "Giroscópio OK (integração)"
        } else {
            source = Source.NONE
            statusText = "Giroscópio ausente — orientação fixa"
        }
        started = true
    }

    @Synchronized
    fun stop() {
        if (!started) return
        sensorManager.unregisterListener(this)
        started = false
    }

    /** Marks the CURRENT head pose as the starting reference (calibration). */
    @Synchronized
    fun calibrateNow(rawHead: Quat) {
        calibQ = rawHead.normalize()
        calibrated = true
        qOut = Quat.IDENTITY
    }

    val isCalibrated: Boolean get() = calibrated

    /** Current raw head orientation (sensor + roll correction), before calibration. */
    fun rawHeadNow(): Quat {
        if (source == Source.NONE) return Quat.IDENTITY
        return qSensorRef.get().multiply(rollCorrection()).normalize()
    }

    /**
     * Advances the 3DOF state by dt seconds.
     * Returns the final camera orientation (relative to calibration).
     */
    fun update(dt: Float): Quat {
        if (source == Source.NONE) {
            qOut = Quat.IDENTITY
            return qOut
        }

        val qRaw = qSensorRef.get()
        // Roll / screen-up correction derived from gravity (works in any orientation)
        val c = rollCorrection()
        val qHead = qRaw.multiply(c).normalize()

        if (!calibrated) calibrateNow(qHead)

        // Motion relative to the calibration pose, expressed in the world:
        // S = qHead^-1 * qCalib  (camera orientation; content stays fixed)
        val s = qHead.conjugate().multiply(calibQ).normalize()

        // Sensitivity = angular gain around the neutral pose
        val gain = sensitivity.coerceIn(0.5f, 1.5f)
        val sGained = if (gain == 1f) s else Quat.IDENTITY.slerp(s, gain)

        // Smoothing: exponential slerp + micro-jitter deadzone
        val tau = 0.012f + smoothing.coerceIn(0f, 0.9f) * 0.22f
        val k = 1f - exp(-dt.toDouble() / tau.toDouble()).toFloat()
        if (qOut.angleTo(sGained) < 0.002f) {
            // micro-oscillation correction: keep the pose
        } else {
            qOut = qOut.slerp(sGained, k)
        }
        return qOut
    }

    /** Gravity-derived roll correction C: C * ŷ_view == world-up (device frame). */
    private fun rollCorrection(): Quat {
        val ax = accelX; val ay = accelY
        val n = sqrt(ax * ax + ay * ay)
        if (n < 0.5f) return Quat.IDENTITY // no gravity data yet
        // up in device frame ~ (-ax, -ay, 0)/|..| ; C = Rz(theta), theta = atan2(-ax, ay)
        val theta = atan2(-ax.toDouble(), ay.toDouble()).toFloat()
        return Quat.fromAxisAngle(0f, 0f, 1f, theta)
    }

    // ------------------------------------------------------------------
    // SensorEventListener
    // ------------------------------------------------------------------
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_ROTATION_VECTOR -> {
                val v = event.values
                qSensorRef.set(Quat(v[0], v[1], v[2], v[3]).normalize())
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val nowNs = event.timestamp
                if (lastAccelNs == 0L) {
                    accelX = event.values[0]
                    accelY = event.values[1]
                    accelZ = event.values[2]
                } else {
                    val dt = (nowNs - lastAccelNs) / 1e9f
                    val a = 1f - exp(-dt.toDouble() / 0.45).toFloat()
                    accelX += (event.values[0] - accelX) * a
                    accelY += (event.values[1] - accelY) * a
                    accelZ += (event.values[2] - accelZ) * a
                }
                lastAccelNs = nowNs
            }
            Sensor.TYPE_GYROSCOPE -> {
                val nowNs = event.timestamp
                if (lastGyroNs == 0L) {
                    lastGyroNs = nowNs
                    return
                }
                val dt = ((nowNs - lastGyroNs) / 1e9f).coerceAtMost(0.1f)
                lastGyroNs = nowNs
                val wx = event.values[0] * 57.29578f // rad/s -> deg/s (fromAxisAngle expects radians below)
                val wy = event.values[1] * 57.29578f
                val wz = event.values[2] * 57.29578f
                // integrate: angle = |omega| * dt (radians), axis = omega
                val wRad = sqrt(wx * wx + wy * wy + wz * wz) * PI_180 * dt
                if (wRad > 1e-6f) {
                    val qd = Quat.fromAxisAngle(wx * PI_180, wy * PI_180, wz * PI_180, wRad)
                    gyroQ = gyroQ.multiply(qd).normalize()
                }
                qSensorRef.set(gyroQ)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { /* not used */ }

    companion object {
        private const val PI_180 = 0.017453292519943295f
    }
}
