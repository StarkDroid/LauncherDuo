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
 * The centre is only ever adopted by [beginRecalibration]. A hand holding a phone
 * "still" is actually swaying by a degree or two continuously, so the window takes
 * the **median** heading rather than an instantaneous one and accepts it as soon as
 * most of its samples agree, instead of demanding a stillness nobody's arm can
 * hold. Nothing else can change the zero.
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

    private var hasSample = false
    private var armed = false
    private var calibrating = false
    private var neutralAzimuth = 0f
    private val neutralRotation = FloatArray(9)
    private var warnedAt = 0L

    // Calibration window: heading samples, in degrees relative to the sample that
    // opened the window, so the ±180° seam of a compass reading cannot cut it in
    // half.
    private val windowYaw = FloatArray(MAX_WINDOW_SAMPLES)
    private val windowRank = FloatArray(MAX_WINDOW_SAMPLES)
    private var windowOpenedAt = 0L
    private var windowSamples = 0
    private var windowBase = 0f
    private var windowCarried = 0f
    private var windowLast = 0f

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

        // A signed heading needs an axis convention, and a wrong one reads a turn
        // where there is none — which is what made the fold snap to one side the
        // moment it said it was ready. This angle is the trace of the two attitudes
        // multiplied together: the same number whichever way the axes are ordered,
        // and exactly 0 at the pose that was committed. So nothing bends until the
        // phone has physically left that pose, whatever the heading claims.
        val strayed = Math.toDegrees(angleBetween(neutralRotation, rotation).toDouble()).toFloat()
        if (strayed < HOLD_RELEASE_DEG && abs(filtered) > DEAD_ZONE_DEG) {
            val now = SystemClock.elapsedRealtime()
            if (now - warnedAt > 2000L) {
                warnedAt = now
                android.util.Log.w(
                    "TunerDepth",
                    "heading claims " + String.format("%.1f", filtered) +
                        "° while the phone has not moved " + String.format("%.1f", strayed) + "°",
                )
            }
            depth = 0f
            onChange(filtered, 0f)
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
            windowOpenedAt = now
            windowSamples = 0
            windowBase = angles[0]
            windowCarried = 0f
            windowLast = angles[0]
            onStatus?.invoke("start")
            return
        }
        // Walk the heading forward a sample at a time: a raw difference of azimuths
        // jumps by 360° when the reading crosses north.
        windowCarried += wrapDegrees(Math.toDegrees((angles[0] - windowLast).toDouble()).toFloat())
        windowLast = angles[0]
        if (sideways && windowSamples < windowYaw.size) {
            windowYaw[windowSamples++] = windowCarried
        }
        if (now - windowOpenedAt < CALIBRATION_MS) return

        if (windowSamples < MIN_WINDOW_SAMPLES) {
            retry("screen not held upright")
            return
        }
        val centre = medianYaw()
        // Steady means most of the window agrees with its own median, not that no
        // sample ever moved: one jolt in thirty should not throw the reading away.
        var agree = 0
        for (i in 0 until windowSamples) {
            if (abs(windowYaw[i] - centre) <= STEADY_DEG) agree++
        }
        if (agree * 4 < windowSamples * 3) {
            retry("still moving")
            return
        }
        commitCentre(windowBase + centre)
    }

    private fun retry(why: String) {
        android.util.Log.d("TunerDepth", "calibration restarts: $why")
        closeWindow()
    }

    private fun closeWindow() {
        windowOpenedAt = 0L
        windowSamples = 0
    }

    private fun medianYaw(): Float {
        for (i in 0 until windowSamples) windowRank[i] = windowYaw[i]
        java.util.Arrays.sort(windowRank, 0, windowSamples)
        val middle = windowSamples / 2
        return if (windowSamples % 2 == 1) windowRank[middle]
        else (windowRank[middle - 1] + windowRank[middle]) / 2f
    }

    private fun commitCentre(azimuth: Float) {
        neutralAzimuth = azimuth
        for (i in 0 until 9) neutralRotation[i] = rotation[i]
        depth = 0f
        filtered = 0f
        lastRaw = 0f
        derivative = 0f
        primed = false
        val taken = windowSamples
        closeWindow()
        calibrating = false
        armed = true
        DepthEngine.publishCentered(true)
        android.util.Log.d(
            "TunerDepth",
            "centre committed from $taken samples, yaw now " +
                String.format("%.1f", yawFromNeutral()) + "°",
        )
        onStatus?.invoke("ready")
    }

    private fun wrapDegrees(degrees: Float): Float {
        var d = degrees
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    /** Signed turn away from the captured pose, zero while the phone is not moved. */
    private fun yawFromNeutral(): Float =
        wrapDegrees(Math.toDegrees((angles[0] - neutralAzimuth).toDouble()).toFloat())

    /** Angle in radians between two attitudes; invariant to the axis convention. */
    private fun angleBetween(a: FloatArray, b: FloatArray): Float {
        var trace = 0f
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                trace += a[j * 3 + i] * b[j * 3 + i]
            }
        }
        return acos(((trace - 1f) / 2f).coerceIn(-1f, 1f))
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

    private companion object {
        /** cos(45°): the screen may point at most this far above or below the horizon. */
        const val FACING_COS_LIMIT = 0.7071f

        const val CALIBRATION_MS = 700L
        const val MAX_WINDOW_SAMPLES = 64
        const val MIN_WINDOW_SAMPLES = 10
        /** A sample counts as agreeing with the centre within this much of it. */
        const val STEADY_DEG = 4f

        const val DEAD_ZONE_DEG = 10f
        /** How far the phone must physically move from the committed pose at all. */
        const val HOLD_RELEASE_DEG = 4f

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
