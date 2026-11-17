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
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Horizontal tilt, armed by stillness, smoothed by a one-euro filter.
 *
 * Method:
 *
 * 1. `TYPE_GAME_ROTATION_VECTOR` is a fused accelerometer + gyroscope attitude.
 *    Successive matrices give angular speed:
 *    `speed = acos((trace(Rprev⁻¹·R) − 1) / 2) / dt`.
 * 2. The phone is "held" when that speed stays under 15°/s for 450 ms. That pose
 *    becomes neutral; before it exists the effect is off.
 * 3. Tilt is `yaw + roll`, both in the neutral frame: yaw is the screen normal's
 *    sideways excursion, roll is gravity rotating in the screen plane. Summing
 *    them means turning the phone and tilting it like a steering wheel both work,
 *    and the signal never flaps between the two axes.
 * 4. The angle goes through a **one-euro filter** (adaptive low-pass): slow,
 *    deliberate tilts are smoothed hard; fast ones keep their latency. That is
 *    what removes the sensor jitter without making the effect feel laggy.
 * 5. Holding still re-zeroes the centre once and latches: the effect stays at
 *    zero until the phone leaves that pose by 5°, which keeps sensor noise from
 *    flickering it on and off around the stillness threshold.
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
    private val previous = FloatArray(9)
    private val neutral = FloatArray(9)
    private var gravityNeutral = 0f

    private var hasSample = false
    private var hasPrevious = false
    private var armed = false
    private var lastTimestamp = 0L
    private var stillSince = 0L

    // One-euro filter state.
    private var filtered = 0f
    private var derivative = 0f
    private var lastRaw = 0f
    private var primed = false

    // Slow drift washout, in degrees.
    private var depth = 0f
    private var idle = false
    private var stillScore = 0f
    private var lastLog = 0L

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
        armed = false
        hasPrevious = false
    }

    fun isArmed(): Boolean = armed

    /** Adopt the current pose as neutral (Set neutral button / first hold). */
    fun calibrate() {
        if (!hasSample) return
        for (i in 0 until 9) neutral[i] = rotation[i]
        gravityNeutral = rawRoll()
        armed = true
        primed = false
        stillSince = 0L
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values
        if (values.size < 3) return
        SensorManager.getRotationMatrixFromVector(rotation, values)
        hasSample = true

        var haveRate = false
        var speed = 0f
        var dt = 0.02f
        if (hasPrevious && lastTimestamp > 0L) {
            val seconds = (event.timestamp - lastTimestamp) / 1_000_000_000f
            if (seconds > 0f) {
                dt = seconds.coerceIn(0.005f, 0.1f)
                speed = Math.toDegrees(relativeAngle(previous, rotation).toDouble()).toFloat() / dt
                haveRate = true
            }
        }
        lastTimestamp = event.timestamp
        for (i in 0 until 9) previous[i] = rotation[i]
        hasPrevious = true

        val now = SystemClock.elapsedRealtime()
        // Smooth "how still is it" score instead of a per-sample boolean: a
        // single noisy sample used to flip still<->moving at 50 Hz, which made
        // the effect pump between reset and warped.
        if (haveRate) {
            val target = if (speed <= STILL_SPEED_DEG_S) 1f else 0f
            val a = 1f - exp(-dt / STILL_TAU_S).toFloat()
            stillScore += (target - stillScore) * a
        }
        val still = stillScore >= STILL_ENTER
        if (still) {
            if (stillSince == 0L) stillSince = now
        } else {
            stillSince = 0L
        }
        val settled = still && stillSince != 0L && now - stillSince >= STILL_MS
        // A phone lying on a table is not being held; do not adopt that as centre.
        val upright = abs(rotation[5]) < UPRIGHT_LIMIT

        if (!armed) {
            if (settled && upright) {
                calibrate()
                depth = 0f
                idle = true
                DepthEngine.publishCentered(true)
                android.util.Log.d("TunerDepth", "centre locked to a steady hold")
            } else {
                DepthEngine.publishCentered(false)
                onChange(0f, 0f)
                return
            }
        }

        // Yaw only: turning the phone left or right. Rolling it like a steering
        // wheel must not move the effect.
        val raw = yawFromNeutral()

        if (!primed) {
            filtered = raw
            lastRaw = raw
            derivative = 0f
            primed = true
        } else {
            oneEuro(raw, dt)
        }

        val tilt = filtered

        // Idle handling. Once the phone is held still it is re-zeroed once and
        // latched: the effect stays at zero until the phone actually leaves that
        // pose by REENGAGE_DEG. Without the latch, sensor noise around the
        // stillness threshold made it flicker between reset and warped.
        if (settled && upright) {
            if (!idle) {
                calibrate()
                depth = 0f
                idle = true
                DepthEngine.publishCentered(true)
            }
            onChange(0f, 0f)
            return
        }
        // Leaving the latch needs both a real departure and a real movement,
        // so tremor around the centre cannot re-trigger the warp.
        if (idle) {
            if (abs(tilt) < REENGAGE_DEG || stillScore > STILL_EXIT) {
                onChange(0f, 0f)
                return
            }
            idle = false
        }

        val target = smoothstep(DEAD_ZONE_DEG, DepthEngine.maxAngle, abs(tilt))
        // Ease the effect itself: the angle may move quickly, but the warp
        // should arrive and leave slowly.
        val a = 1f - exp(-dt / DEPTH_TAU_S).toFloat()
        depth += (target - depth) * a
        if (now - lastLog > 500L) {
            lastLog = now
            android.util.Log.d(
                "TunerDepth",
                "speed=" + String.format("%.1f", speed) +
                    " stillScore=" + String.format("%.2f", stillScore) +
                    " idle=" + idle +
                    " tilt=" + String.format("%.1f", tilt) +
                    " depth=" + String.format("%.2f", depth),
            )
        }
        onChange(tilt, depth)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

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

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }

    private fun lowpass(x: Float, prev: Float, a: Float) = prev + a * (x - prev)

    /**
     * Roll: the in-plane direction of gravity, degrees. Gravity in device
     * coordinates is (-r[3], -r[4], -r[5]); the angle is atan2(gx, -gy), which
     * stays zero when the phone is pitched but not rolled — the previous
     * atan2(gx, gy) folded pitch into this axis, which is why tilting the phone
     * top/bottom reacted more than tilting it left/right.
     */
    private fun rawRoll(): Float =
        Math.toDegrees(atan2(-rotation[3], rotation[4]).toDouble()).toFloat()

    private fun rollFromNeutral(): Float {
        var r = rawRoll() - gravityNeutral
        while (r > 180f) r -= 360f
        while (r < -180f) r += 360f
        return r
    }

    /** Screen normal's x component relative to neutral, in degrees. */
    private fun yawFromNeutral(): Float {
        val n0 = neutral
        val r = rotation
        val cx = r[2]
        val cy = r[5]
        val cz = r[8]
        val vx = n0[0] * cx + n0[1] * cy + n0[2] * cz
        val vz = n0[6] * cx + n0[7] * cy + n0[8] * cz
        if (vz <= 0f) return 0f
        return Math.toDegrees(atan2(vx, vz).toDouble()).toFloat()
    }

    private fun relativeAngle(a: FloatArray, b: FloatArray): Float {
        var trace = 0f
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                trace += a[j * 3 + i] * b[j * 3 + i]
            }
        }
        val c = ((trace - 1f) / 2f).coerceIn(-1f, 1f)
        return acos(c)
    }

    private companion object {
        /** |screen normal · world up|; above this the phone is lying flat. */
        const val UPRIGHT_LIMIT = 0.7f

        const val STILL_SPEED_DEG_S = 8f
        const val STILL_MS = 1000L
        const val DEAD_ZONE_DEG = 8f

        // One-euro tuning.
        const val MIN_CUTOFF = 0.7f
        const val BETA = 0.02f
        const val DERIVATIVE_CUTOFF = 1.0f

        // Drift washout.
        const val DEPTH_TAU_S = 0.22f
        /** Leaving this much tilt from the latched centre re-engages the effect. */
        const val REENGAGE_DEG = 5f

        /** Stillness score time constant and thresholds. */
        const val STILL_TAU_S = 0.35f
        const val STILL_ENTER = 0.75f
        const val STILL_EXIT = 0.35f

        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
