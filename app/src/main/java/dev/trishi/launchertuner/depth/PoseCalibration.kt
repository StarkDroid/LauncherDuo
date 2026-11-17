package dev.trishi.launchertuner.depth

import kotlin.math.abs
import kotlin.math.acos

/**
 * The arithmetic of the fold: how a centre is chosen, how a turn is read from it,
 * and when a turn is allowed to bend anything.
 *
 * Deliberately free of Android so it can be tested as plain numbers — see
 * `src/test/java`. Every function here takes or returns one documented unit, and
 * mixing them up is the single easiest way to break the effect, so the conversion
 * happens in exactly one place: [yawFrom].
 */
internal object PoseCalibration {

    /** How long the pose is watched before a centre is adopted. */
    const val WINDOW_MS = 700L

    /** Samples a window can hold, and the fewest that can decide it. */
    const val MAX_SAMPLES = 64
    const val MIN_SAMPLES = 10

    /** A sample counts as agreeing with the centre within this much of it. */
    const val STEADY_DEG = 4f

    /** Fraction of the window that must agree before the centre is adopted. */
    const val AGREEMENT = 0.75f

    /**
     * How far the phone must physically leave the committed attitude before the
     * fold may bend at all. This is the safety net: the heading is a signed angle
     * that depends on an axis convention, the attitude difference is not, so a
     * heading that is somehow wrong cannot produce a fold on its own.
     */
    const val HOLD_RELEASE_DEG = 4f

    /** Turn below which nothing happens, so a hand at rest is never read as a move. */
    const val DEAD_ZONE_DEG = 10f

    /** Angles from [SensorManager.getOrientation] arrive in radians. */
    fun toDegrees(radians: Float): Float = Math.toDegrees(radians.toDouble()).toFloat()

    fun toRadians(degrees: Float): Float = Math.toRadians(degrees.toDouble()).toFloat()

    /** Fold an angle into −180..180 so a turn through north stays continuous. */
    fun wrap(degrees: Float): Float {
        var d = degrees
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    /**
     * Signed turn away from the committed heading, in degrees. Both arguments are
     * radians, exactly as the platform reports them: the conversion happens here
     * and nowhere else. Zero means the phone is where it was calibrated.
     */
    fun yawFrom(headingRad: Float, neutralRad: Float): Float =
        wrap(toDegrees(headingRad - neutralRad))

    /** The heading that [samples], a run of offsets in degrees, centres on. */
    fun centreDeg(samples: FloatArray, count: Int): Float = median(samples, count)

    /**
     * The committed centre as a heading in radians: the heading the window opened
     * at, moved by the median offset its samples measured in degrees. The units are
     * written out because getting them wrong here is the difference between the
     * fold resting at zero and snapping to one side the instant it says ready.
     */
    fun commit(openedAtRad: Float, centreDeg: Float): Float = openedAtRad + toRadians(centreDeg)

    /**
     * A window is settled when most of it agrees with its own median. A hand
     * holding a phone "still" sways by a degree or two continuously, so demanding
     * that no sample ever moved never settles; a median with an inlier ratio does,
     * and one jolt in forty samples no longer throws a good reading away.
     */
    fun isSettled(samples: FloatArray, count: Int): Boolean {
        if (count < MIN_SAMPLES) return false
        val middle = median(samples, count)
        var agree = 0
        for (i in 0 until count) {
            if (abs(samples[i] - middle) <= STEADY_DEG) agree++
        }
        return agree >= count * AGREEMENT
    }

    /** Median of the first [count] entries, without disturbing the input. */
    fun median(values: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        val sorted = FloatArray(count) { values[it] }
        sorted.sort()
        val middle = count / 2
        return if (count % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    /**
     * How folded the screen should be: 0 flat, 1 fully bent. Nothing bends until
     * the phone has physically moved [HOLD_RELEASE_DEG] from the pose it was
     * calibrated in; past that, the turn ramps up across the remaining range.
     */
    fun foldFor(yawDeg: Float, maxAngleDeg: Float, strayedDeg: Float): Float {
        if (strayedDeg < HOLD_RELEASE_DEG) return 0f
        return smoothstep(DEAD_ZONE_DEG, maxAngleDeg, abs(yawDeg))
    }

    /**
     * The angle between two attitudes, in degrees: the trace of one transposed
     * against the other. The same number whichever way the axes are ordered, and
     * exactly 0 between a pose and itself.
     */
    fun strayedDeg(a: FloatArray, b: FloatArray): Float {
        var trace = 0f
        for (i in 0 until 3) {
            for (j in 0 until 3) {
                trace += a[j * 3 + i] * b[j * 3 + i]
            }
        }
        return toDegrees(acos(((trace - 1f) / 2f).coerceIn(-1f, 1f)))
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
