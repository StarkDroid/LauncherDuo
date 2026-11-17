package dev.trishi.launchertuner.depth

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.max

/**
 * How far the phone has been turned left or right, measured against a calibrated
 * centre.
 *
 * The signal is built entirely from Android's own attitude geometry:
 *
 * 1. `TYPE_GAME_ROTATION_VECTOR` (fused accelerometer + gyroscope) is turned into
 *    a rotation matrix.
 * 2. That matrix is remapped so the **direction the screen faces** becomes the
 *    frame's vertical reference, and [SensorManager.getOrientation] reports it as
 *    an azimuth plus a pitch. Reading the heading this way keeps the axis layout,
 *    handedness and world frame inside the platform instead of in hand-written
 *    matrix indexing.
 * 3. Calibration stores the pose as [neutralAzimuth]. The effect is driven by
 *    `azimuth − neutralAzimuth`, wrapped to ±180°, so the reading is exactly zero
 *    at the captured pose and grows by the same amount in either direction:
 *    positive one way, negative the other.
 * 4. The pitch tells whether the screen is facing sideways at all. Pointed at the
 *    ceiling or lying flat on a table, a heading has no meaning, so nothing is
 *    published.
 * 5. The angle goes through a **one-euro filter** (adaptive low-pass): a slow,
 *    deliberate turn is smoothed hard, a fast one keeps its latency.
 *
 * The centre is only ever adopted by [beginRecalibration], which watches the
 * sensor for a window and commits when the pose has not moved. Nothing else can
 * change the zero.
 */
class TiltTracker(
    context: Context,
    private val onChange: (yawDeg: Float, depth: Float) -> Unit,
) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? =
        sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val rotation = FloatArray(9)
    private val facing = FloatArray(9)
    private val angles = FloatArray(3)
    private val windowStart = FloatArray(9)

    private var hasSample = false
    private var armed = false
    private var calibrating = false
    private var neutralAzimuth = 0f

    // Calibration window: opened on the first sample after a request and committed
    // only when the pose has stayed inside a small band for long enough.
    private var windowOpenedAt = 0L
    private var windowSamples = 0
    private var windowWorst = 0f

    /** Reports "start" when a recalibration begins and "ready" when it commits. */
    var onStatus: ((String) -> Unit)? = null

    // One-euro filter state.
    private var filtered = 0f
    private var derivative = 0f
    private var lastRaw = 0f
    private var primed = false

    private var depth = 0f
    private var lastTimestamp = 0L

    fun start(): Boolean {
        val s = sensor
        if (s == null) {
            android.util.Log.w("TunerDepth", "no rotation sensor")
            return false
        }
        val ok = sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        android.util.Log.d("TunerDepth", "rotation sensor ${s.name} registered=$ok")
        return ok
    }

    fun stop() {
        runCatching { sensors.unregisterListener(this) }
        pause()
    }

    fun isArmed(): Boolean = armed

    /**
     * Leave the whole calibration state behind. Used when the launcher is no
     * longer in front, so a window opened at home can never commit later, inside
     * another app.
     */
    fun pause() {
        armed = false
        calibrating = false
        closeWindow()
        depth = 0f
        primed = false
        DepthEngine.publishCentered(false)
    }

    /**
     * Stop bending and learn a new centre: watch for [CALIBRATION_MS], require the
     * pose to move less than [CALIB_TOLERANCE_DEG] across that window, and commit
     * only then. An unsteady window is thrown away and started again rather than
     * guessed at.
     */
    fun beginRecalibration() {
        calibrating = true
        armed = false
        closeWindow()
        depth = 0f
        primed = false
        DepthEngine.publishCentered(false)
        DepthEngine.reset()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        if (values.size < 3) return
        SensorManager.getRotationMatrixFromVector(rotation, values)
        SensorManager.remapCoordinateSystem(
            rotation,
            SensorManager.AXIS_X,
            SensorManager.AXIS_Z,
            facing,
        )
        SensorManager.getOrientation(facing, angles)
        hasSample = true

        val dt = if (lastTimestamp > 0L) {
            ((event.timestamp - lastTimestamp) / 1_000_000_000f).coerceIn(0.005f, 0.1f)
        } else {
            0.02f
        }
        lastTimestamp = event.timestamp

        // A heading only means something while the screen faces sideways. R[8] is
        // the cosine of the angle between the screen normal and straight up, so it
        // is ~0 held in the hand and ~1 flat on a table, where the heading would
        // be ill-conditioned and report a phantom turn.
        val sideways = abs(rotation[8]) <= FACING_COS_LIMIT

        if (calibrating) {
            calibrationStep(sideways)
            onChange(0f, 0f)
            return
        }
        if (!armed || !sideways) {
            depth = 0f
            DepthEngine.publishCentered(armed && sideways)
            onChange(0f, 0f)
            return
        }

        val raw = yawFromNeutral()
        if (!primed) {
            filtered = raw
            lastRaw = raw
            derivative = 0f
            primed = true
        } else {
            oneEuro(raw, dt)
        }

        val target = smoothstep(DEAD_ZONE_DEG, DepthEngine.maxAngle, abs(filtered))
        // Ease the effect itself: the angle may move quickly, but the warp should
        // arrive and leave slowly.
        depth += (target - depth) * (1f - exp(-dt / DEPTH_TAU_S).toFloat())
        onChange(filtered, depth)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun calibrationStep(sideways: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (windowOpenedAt == 0L) {
            for (i in 0 until 9) windowStart[i] = rotation[i]
            windowOpenedAt = now
            windowSamples = 0
            windowWorst = 0f
            onStatus?.invoke("start")
            return
        }
        windowSamples++
        // How far the pose has strayed from the attitude this window opened with.
        // The angle between two attitudes is the same number whichever way the
        // platform orders its axes, so the check cannot be fooled by that.
        val strayed = Math.toDegrees(angleBetween(windowStart, rotation).toDouble()).toFloat()
        windowWorst = max(windowWorst, strayed)
        if (now - windowOpenedAt < CALIBRATION_MS) return

        val seconds = (now - windowOpenedAt) / 1000f
        when {
            !sideways -> retry("screen not facing sideways")
            windowSamples < MIN_WINDOW_SAMPLES -> retry("not enough samples")
            windowWorst > CALIB_TOLERANCE_DEG ->
                retry("moved ${String.format("%.1f", windowWorst)}° in ${String.format("%.2f", seconds)}s")
            else -> commitCentre()
        }
    }

    private fun retry(why: String) {
        android.util.Log.d("TunerDepth", "calibration restarts: $why")
        closeWindow()
    }

    private fun closeWindow() {
        windowOpenedAt = 0L
        windowSamples = 0
        windowWorst = 0f
    }

    private fun commitCentre() {
        if (!hasSample) return
        neutralAzimuth = angles[0]
        depth = 0f
        filtered = 0f
        lastRaw = 0f
        derivative = 0f
        primed = false
        val worst = windowWorst
        closeWindow()
        calibrating = false
        armed = true
        DepthEngine.publishCentered(true)
        android.util.Log.d(
            "TunerDepth",
            "centre committed, held within " + String.format("%.1f", worst) + "°, yaw now " +
                String.format("%.1f", yawFromNeutral()),
        )
        onStatus?.invoke("ready")
    }

    /** Signed turn away from the captured pose, zero while the phone is not moved. */
    private fun yawFromNeutral(): Float {
        var d = Math.toDegrees((angles[0] - neutralAzimuth).toDouble()).toFloat()
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    /**
     * One-euro filter: cutoff rises with the filtered derivative, so slow motion
     * is smoothed heavily while fast motion keeps its latency.
     */
    private fun oneEuro(x: Float, dt: Float) {
        val dx = (x - lastRaw) / dt
        lastRaw = x
        derivative = lowpass(dx, derivative, alpha(DERIVATIVE_CUTOFF, dt))
        val cutoff = MIN_CUTOFF + BETA * abs(derivative)
        filtered = lowpass(x, filtered, alpha(cutoff, dt))
    }

    private fun lowpass(x: Float, prev: Float, a: Float) = prev + a * (x - prev)

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    /** Angle in radians between two attitudes. */
    private fun angleBetween(a: FloatArray, b: FloatArray): Float {
        var trace = 0f
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                trace += a[j * 3 + i] * b[j * 3 + i]
            }
        }
        return acos(((trace - 1f) / 2f).coerceIn(-1f, 1f))
    }

    private companion object {
        /** cos(45°): the screen may point at most this far above or below the horizon. */
        const val FACING_COS_LIMIT = 0.7071f

        const val CALIBRATION_MS = 900L
        const val CALIB_TOLERANCE_DEG = 3f
        const val MIN_WINDOW_SAMPLES = 8

        const val DEAD_ZONE_DEG = 10f

        // One-euro tuning.
        const val MIN_CUTOFF = 0.7f
        const val BETA = 0.02f
        const val DERIVATIVE_CUTOFF = 1.0f

        const val DEPTH_TAU_S = 0.30f

        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
