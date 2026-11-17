package dev.trishi.launchertuner.depth

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * How far the phone has been turned left or right, measured against a calibrated
 * centre, and how folded that makes the screen.
 *
 * The signal is built from Android's own attitude geometry:
 *
 * 1. `TYPE_GAME_ROTATION_VECTOR` (fused accelerometer + gyroscope) becomes a
 *    rotation matrix.
 * 2. That matrix is remapped so the **direction the screen faces** is the frame's
 *    vertical reference, and [SensorManager.getOrientation] reports its compass
 *    heading. Reading the heading this way keeps the axis layout, the handedness
 *    and the world frame inside the platform instead of in hand-written matrix
 *    indexing.
 * 3. Calibration stores a heading; the effect is driven by the signed difference
 *    from it, so it reads 0° at the pose that was captured and the same amount
 *    with opposite signs for equal turns either way.
 * 4. The heading is only read while the screen faces roughly sideways: pointed at
 *    the ceiling or lying flat on a table, a compass bearing means nothing.
 * 5. The angle goes through a **one-euro filter** (adaptive low-pass): a slow,
 *    deliberate turn is smoothed hard, a fast one keeps its latency.
 *
 * The arithmetic lives in [PoseCalibration]; this class is the sensor plumbing, the
 * window and the easing. The centre is only ever adopted by [beginRecalibration] —
 * a hand holding a phone "still" sways by a couple of degrees, so the window takes
 * the median heading rather than an instantaneous one and settles as soon as most
 * of its samples agree.
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

    private var armed = false
    private var listening = false
    private var calibrating = false
    private var neutralHeading = 0f
    private val neutralRotation = FloatArray(9)
    private var warnedAt = 0L

    // Calibration window: heading offsets in degrees from the sample that opened
    // the window, so the ±180° seam of a compass reading cannot cut it in half.
    private val windowYaw = FloatArray(PoseCalibration.MAX_SAMPLES)
    private var windowOpenedAt = 0L
    private var windowSamples = 0
    private var windowBase = 0f
    private var windowCarried = 0f
    private var windowLast = 0f
    private var statusOpen = false

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
        if (listening) return true
        listening = sensors.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
        android.util.Log.d("TunerDepth", "rotation sensor ${s.name} registered=$listening")
        return listening
    }

    fun stop() {
        if (listening) {
            runCatching { sensors.unregisterListener(this) }
            listening = false
        }
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
        statusOpen = false
        closeWindow()
        depth = 0f
        primed = false
        DepthEngine.publishCentered(false)
    }

    /**
     * Stop bending and learn a new centre: watch the heading for a window and
     * commit its median once the phone has settled. An unsettled window is thrown
     * away and started again rather than guessed at.
     */
    fun beginRecalibration() {
        calibrating = true
        statusOpen = false
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

        val dt = if (lastTimestamp > 0L) {
            ((event.timestamp - lastTimestamp) / 1_000_000_000f).coerceIn(0.005f, 0.1f)
        } else {
            0.02f
        }
        lastTimestamp = event.timestamp

        // A heading only means something while the screen faces sideways. R[8] is
        // the cosine of the angle between the screen normal and straight up, so it
        // is ~0 held in the hand and ~1 flat on a table, where the heading would be
        // ill-conditioned and report a phantom turn.
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

        val yaw = PoseCalibration.yawFrom(angles[0], neutralHeading)
        if (!primed) {
            filtered = yaw
            lastRaw = yaw
            derivative = 0f
            primed = true
        } else {
            oneEuro(yaw, dt)
        }

        // How far the phone has physically left the pose it was calibrated in.
        val strayed = PoseCalibration.strayedDeg(neutralRotation, rotation)
        val target = PoseCalibration.foldFor(filtered, DepthEngine.maxAngle, strayed)
        if (target == 0f && abs(filtered) > PoseCalibration.DEAD_ZONE_DEG) {
            warn(strayed)
        }

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
            if (!statusOpen) {
                statusOpen = true
                onStatus?.invoke("start")
            }
            return
        }
        // Walk the heading forward a sample at a time: a raw difference of azimuths
        // jumps by 360° when the reading crosses north.
        windowCarried += PoseCalibration.wrap(PoseCalibration.toDegrees(angles[0] - windowLast))
        windowLast = angles[0]
        if (sideways && windowSamples < windowYaw.size) {
            windowYaw[windowSamples++] = windowCarried
        }
        if (now - windowOpenedAt < PoseCalibration.WINDOW_MS) return

        if (windowSamples < PoseCalibration.MIN_SAMPLES) {
            retry("screen not held upright")
            return
        }
        if (!PoseCalibration.isSettled(windowYaw, windowSamples)) {
            retry("still moving")
            return
        }
        commitCentre()
    }

    private fun retry(why: String) {
        android.util.Log.d("TunerDepth", "calibration restarts: $why")
        closeWindow()
    }

    private fun closeWindow() {
        windowOpenedAt = 0L
        windowSamples = 0
    }

    private fun commitCentre() {
        // The window measured degrees away from the heading it opened at, so the
        // centre is that heading plus its median offset — both in radians, which is
        // what the platform reports and what yawFrom expects.
        val centreDeg = PoseCalibration.centreDeg(windowYaw, windowSamples)
        neutralHeading = PoseCalibration.commit(windowBase, centreDeg)
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
        statusOpen = false
        DepthEngine.publishCentered(true)
        android.util.Log.d(
            "TunerDepth",
            "centre committed from $taken samples, yaw now " +
                String.format("%.1f", PoseCalibration.yawFrom(angles[0], neutralHeading)) + "°",
        )
        onStatus?.invoke("ready")
    }

    /** The two measurements disagree: report it instead of bending the screen. */
    private fun warn(strayed: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - warnedAt < 2000L) return
        warnedAt = now
        android.util.Log.w(
            "TunerDepth",
            "heading claims " + String.format("%.1f", filtered) +
                "° while the phone has not moved " + String.format("%.1f", strayed) + "°",
        )
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
        /** sin(60°): the screen may point at most this far above or below the horizon. */
        const val FACING_COS_LIMIT = 0.8660f

        // One-euro tuning.
        const val MIN_CUTOFF = 0.7f
        const val BETA = 0.02f
        const val DERIVATIVE_CUTOFF = 1.0f

        const val DEPTH_TAU_S = 0.30f
    }
}
