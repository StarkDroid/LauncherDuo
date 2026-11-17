package dev.trishi.launchertuner.depth

import android.graphics.Bitmap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Shared state for the tilt fold.
 *
 * The accessibility service owns the overlay window and the gyroscope; the
 * foreground service only produces screen frames.
 */
object DepthEngine {

    @Volatile
    var enabled = false

    /** Latest captured frame, if capture is authorised and running. */
    @Volatile
    var frame: Bitmap? = null
    @Volatile
    var frameWidth = 0
    @Volatile
    var frameHeight = 0

    /** Horizontal tilt of the screen normal, in degrees. Sign picks the folding half. */
    @Volatile
    var yawDeg = 0f

    /** 0 = flat, 1 = fully folded. */
    @Volatile
    var depth = 0f

    /** True while the launcher's home screen owns the foreground. */
    @Volatile
    var homeVisible = false

    /** Tilt that maps to depth 1, in degrees. */
    @Volatile
    var maxAngle = 14f

    /** User-facing warp amount: multiplies the visual fold angle. */
    @Volatile
    var strength = 1f

    /** Viewing distance as a multiple of screen height. */
    @Volatile
    var eyeDistance = 1.2f

    @Volatile
    var captureRunning = false

    /** True once a stable, upright hold has been adopted as the centre. */
    @Volatile
    var centered = false

    /** Debug hook: freeze the sensor so a host can hold a tilt for inspection. */
    @Volatile
    var debugLocked = false

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        listeners.forEach { runCatching { it() } }
    }

    fun publishTilt(yaw: Float, d: Float) {
        yawDeg = yaw
        depth = d
        notifyChanged()
    }

    fun publishFrame(bitmap: Bitmap, width: Int, height: Int) {
        frame = bitmap
        frameWidth = width
        frameHeight = height
        notifyChanged()
    }

    fun clearFrame() {
        frame = null
        notifyChanged()
    }

    fun reset() {
        yawDeg = 0f
        depth = 0f
        notifyChanged()
    }

    fun publishCentered(value: Boolean) {
        if (centered == value) return
        centered = value
        notifyChanged()
    }
}
