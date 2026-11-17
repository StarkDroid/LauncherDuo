package dev.trishi.launchertuner.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Test

/**
 * Guards for the fold's arithmetic. Each test here is a rule the effect visibly
 * depends on; breaking one means the screen bends when the phone is still, or
 * refuses to bend when it is turned.
 */
class PoseCalibrationTest {

    private fun rad(deg: Float) = PoseCalibration.toRadians(deg)
    private fun deg(rad: Float) = PoseCalibration.toDegrees(rad)

    /** The bug this exists to prevent: degrees added to radians. */
    @Test
    fun centreReadsZeroAtEveryHeading() {
        val tremor = 2.5f
        for (heading in -179..179) {
            val base = rad(heading.toFloat())
            val neutral = PoseCalibration.commit(base, tremor)
            // The phone resting where the window's median says it is.
            val poseNow = rad(heading.toFloat() + tremor)
            assertEquals("heading $heading", 0f, PoseCalibration.yawFrom(poseNow, neutral), 0.01f)
        }
    }

    @Test
    fun turnReadsTheSameSizeEitherWay() {
        val neutral = rad(0f)
        assertEquals(20f, PoseCalibration.yawFrom(rad(20f), neutral), 0.01f)
        assertEquals(-20f, PoseCalibration.yawFrom(rad(-20f), neutral), 0.01f)
        assertEquals(45f, PoseCalibration.yawFrom(rad(45f), neutral), 0.01f)
        assertEquals(-45f, PoseCalibration.yawFrom(rad(-45f), neutral), 0.01f)
    }

    /** Facing north and continuing past it must read a small turn, not 360°. */
    @Test
    fun turnThroughNorthStaysContinuous() {
        val neutral = rad(179.5f)
        assertEquals(1f, PoseCalibration.yawFrom(rad(-179.5f), neutral), 0.01f)
        assertEquals(-1f, PoseCalibration.yawFrom(rad(178.5f), neutral), 0.01f)
        assertEquals(30f, PoseCalibration.yawFrom(rad(-150.5f), neutral), 0.01f)
        assertEquals(-30f, PoseCalibration.yawFrom(rad(149.5f), neutral), 0.01f)
    }

    @Test
    fun centreIsOffsetFromTheHeadingThatOpenedTheWindow() {
        // The window stores degrees relative to its first sample; the centre has to
        // land on the phone's real heading, not on the opening heading.
        val neutral = PoseCalibration.commit(rad(10f), centreDeg = 5f)
        assertEquals(0f, PoseCalibration.yawFrom(rad(15f), neutral), 0.01f)
        assertEquals(-10f, PoseCalibration.yawFrom(rad(5f), neutral), 0.01f)
    }

    /** A hand holding a phone "still" sways; the window must still settle. */
    @Test
    fun settlesOnANaturalHold() {
        val sway = FloatArray(40) {
            1.8f * sin(2.0f * PI.toFloat() * 0.9f * it / 50f) +
                1.1f * sin(2.0f * PI.toFloat() * 2.3f * it / 50f + 2f)
        }
        assertTrue(PoseCalibration.isSettled(sway, sway.size))
        assertEquals(0f, PoseCalibration.centreDeg(sway, sway.size), 1.5f)
    }

    @Test
    fun refusesAWindowThatIsActuallyTurning() {
        val turning = FloatArray(40) { it * 0.8f }     // 40° over the window
        assertFalse(PoseCalibration.isSettled(turning, turning.size))
    }

    @Test
    fun oneJoltDoesNotSpoilAGoodWindow() {
        val withJolt = FloatArray(40) { 1.5f * sin(it / 4f) }
        withJolt[17] = 30f
        assertTrue(PoseCalibration.isSettled(withJolt, withJolt.size))
    }

    @Test
    fun tooFewSamplesNeverCommit() {
        assertFalse(PoseCalibration.isSettled(FloatArray(PoseCalibration.MIN_SAMPLES - 1), 1))
    }

    @Test
    fun medianIgnoresOutliersThatMeanWouldNot() {
        val values = floatArrayOf(1f, 1.2f, 0.8f, 1.1f, 60f, 0.9f, 1.05f)
        assertEquals(1.0f, PoseCalibration.median(values, values.size), 0.2f)
    }

    /**
     * The safety net: a heading that claims a turn the phone has not physically
     * made must not bend anything.
     */
    @Test
    fun nothingBendsUntilThePhoneHasLeftTheCommittedPose() {
        assertEquals(
            0f,
            PoseCalibration.foldFor(yawDeg = 30f, maxAngleDeg = 35f, strayedDeg = 1f),
            0f,
        )
        assertEquals(
            0f,
            PoseCalibration.foldFor(yawDeg = 90f, maxAngleDeg = 35f, strayedDeg = 3.9f),
            0f,
        )
        assertTrue(
            PoseCalibration.foldFor(30f, 35f, 30f) > 0.5f,
        )
    }

    @Test
    fun rampStartsAfterTheDeadZoneAndCompletesAtTheChosenAngle() {
        assertEquals(0f, PoseCalibration.foldFor(9.9f, 35f, 30f), 0f)
        assertEquals(0f, PoseCalibration.foldFor(10f, 35f, 30f), 0f)
        assertEquals(1f, PoseCalibration.foldFor(35f, 35f, 30f), 0f)
        var previous = 0f
        for (yaw in 10..35) {
            val fold = PoseCalibration.foldFor(yaw.toFloat(), 35f, 30f)
            assertTrue("not monotone at $yaw", fold >= previous)
            previous = fold
        }
    }

    @Test
    fun attitudeAngleComparesPosesNotHeadings() {
        val a = identity()
        val b = yawMatrix(20f)
        assertEquals(20f, PoseCalibration.strayedDeg(a, b), 0.01f)
        assertEquals(0f, PoseCalibration.strayedDeg(a, a), 0.01f)
        // Same number whichever way the platform orders the axes in its array.
        assertEquals(PoseCalibration.strayedDeg(a, b), PoseCalibration.strayedDeg(transpose(a), transpose(b)), 0.01f)
    }

    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun yawMatrix(deg: Float): FloatArray {
        val r = deg * PI.toFloat() / 180f
        val c = kotlin.math.cos(r)
        val s = sin(r)
        return floatArrayOf(c, -s, 0f, s, c, 0f, 0f, 0f, 1f)
    }

    private fun transpose(m: FloatArray) =
        floatArrayOf(m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8])
}
