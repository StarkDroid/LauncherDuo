package dev.trishi.launchertuner.gesture

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.trishi.launchertuner.depth.DepthEngine
import dev.trishi.launchertuner.depth.DepthOverlayView
import dev.trishi.launchertuner.depth.TiltTracker

/**
 * Hosts the fold overlay and feeds it screenshots and tilt. This is the only
 * background component; everything else is the settings screen.
 */
class GestureService : AccessibilityService() {

    private lateinit var tilt: TiltTracker
    private var overlay: DepthOverlayView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var lastDrawAt = 0L
    private var lastCaptureAt = 0L
    private var captureInFlight = false
    private var homeSince = 0L
    private var onHome = false
    private var awaitHome = false
    private var lastRecalibrationAt = 0L
    private var chip: android.widget.TextView? = null
    private var suppressUntil = 0L

    private val depthListener: () -> Unit = listener@{
        val view = overlay ?: return@listener
        // The tracker keeps publishing while you are in any app, so the gate has
        // to be checked here as well: nothing is drawn unless the launcher owns
        // the foreground.
        val visible = onHome && !awaitHome &&
            SystemClock.elapsedRealtime() > suppressUntil &&
            DepthEngine.depth > 0.002f && !isLocked()
        val wanted = if (visible) View.VISIBLE else View.INVISIBLE
        if (view.visibility != wanted) view.visibility = wanted
        if (visible) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDrawAt >= 33L) {
                lastDrawAt = now
                view.postInvalidateOnAnimation()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tilt = TiltTracker(this) { angle, depth -> DepthEngine.publishTilt(angle, depth) }
        tilt.onStatus = { state ->
            if (state == "start") showChip("Recalibrating…", sticky = true)
            else { hideChip(); showChip("Fold ready", sticky = false) }
        }
        DepthEngine.addListener(depthListener)
        registerScreenReceiver()
        // Never resume on our own after a reboot or a service rebind. The user
        // switches it on, which is also what forces a fresh calibration for the
        // way they are holding the phone right now.
        prefs(this).edit().putBoolean(KEY_ENABLED, false).apply()
        apply()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        val pkgFromEvent = event.packageName?.toString()
        if (pkgFromEvent == packageName) return
        val interaction = type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (interaction) {
            // Content changes also fire from idle animations, so re-learning is
            // rate limited rather than automatic on every event.
            val now = SystemClock.elapsedRealtime()
            if (now - lastRecalibrationAt < RECALIBRATE_COOLDOWN_MS) return
            lastRecalibrationAt = now
            // Any interaction kills the fold at once, then re-learns the pose
            // over a short window before the bend is allowed again.
            overlay?.visibility = View.INVISIBLE
            tilt.beginRecalibration()
            return
        }
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = pkgFromEvent ?: return
        if (pkg == "com.android.systemui") {
            // The shade, the lock screen and the notification panel are drawn by
            // SystemUI over the launcher. They do not change who owns the screen,
            // but the fold must not float above them, so pause and re-learn.
            val cls = event.className?.toString().orEmpty()
            if (cls.contains("Shade", ignoreCase = true) ||
                cls.contains("Panel", ignoreCase = true) ||
                cls.contains("Keyguard", ignoreCase = true)
            ) {
                suppressUntil = SystemClock.elapsedRealtime() + SUPPRESS_MS
                overlay?.visibility = View.INVISIBLE
                tilt.beginRecalibration()
            }
            return
        }
        if (pkg == "android") return
        applyHome(pkg == LAUNCHER)
    }

    /**
     * The active window, asked directly. Window-state events are not delivered
     * reliably after the service is re-bound, and a stale "on home" flag made the
     * overlay appear inside this app; polling the package is deterministic.
     */
    private fun foregroundPackage(): String? {
        runCatching { rootInActiveWindow?.packageName?.toString() }
            .getOrNull()
            ?.let { return it }
        // Some transitions leave no active root; the window list still knows
        // which application window is on top.
        return runCatching {
            windows.firstOrNull { it.isActive && it.type == WindowManager.LayoutParams.TYPE_APPLICATION }
                ?.root?.packageName?.toString()
        }.getOrNull()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        runCatching { screenReceiver?.let { unregisterReceiver(it) } }
        DepthEngine.removeListener(depthListener)
        removeOverlay()
        hideChip()
        tilt.stop()
        instance = null
        super.onDestroy()
    }

    fun apply() {
        val wanted = prefs(this).getBoolean(KEY_ENABLED, false)
        DepthEngine.maxAngle = prefs(this).getFloat(KEY_ANGLE, 28f)
        DepthEngine.strength = prefs(this).getFloat(KEY_STRENGTH, 1f)
        if (!wanted) {
            removeOverlay()
            tilt.stop()
            DepthEngine.enabled = false
            return
        }
        DepthEngine.enabled = true
        DepthEngine.reset()
        DepthEngine.clearFrame()
        // Calibration is deferred until the launcher is confirmed in front, so
        // flipping the switch inside this app never measures the wrong pose.
        awaitHome = true
        if (overlay == null) addOverlay()
        if (tilt.start()) startCaptureLoop()
    }

    private fun addOverlay() {
        val wm = getSystemService(WindowManager::class.java)
        val view = DepthOverlayView(this).apply { visibility = View.INVISIBLE }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        }
        runCatching { wm.addView(view, lp) }.onSuccess {
            overlay = view
            overlayParams = lp
        }
    }

    private fun removeOverlay() {
        overlay?.let { runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(it) } }
        overlay = null
        overlayParams = null
        DepthEngine.clearFrame()
    }

    private fun startCaptureLoop() {
        if (captureInFlight) return
        captureInFlight = true
        mainExecutor.execute { captureLoop() }
    }

    private fun captureLoop() {
        // The focused window is never our own overlay (it is not focusable), so
        // the poll can treat this app as a real "left home" transition.
        foregroundPackage()?.let { pkg ->
            if (pkg != "com.android.systemui" && pkg != "android") {
                applyHome(pkg == LAUNCHER)
            }
        }
        val now = SystemClock.elapsedRealtime()
        val settled = homeSince != 0L && now - homeSince > SETTLE_MS
        val needsFrame = DepthEngine.frame == null || DepthEngine.depth < 0.02f
        if (DepthEngine.enabled && onHome && settled && needsFrame && !isLocked() &&
            now - lastCaptureAt > CAPTURE_MS
        ) {
            lastCaptureAt = now
            grabScreen()
        }
        if (DepthEngine.enabled) {
            mainHandler.postDelayed({ captureLoop() }, 250L)
        } else {
            captureInFlight = false
        }
    }

    private fun grabScreen() {
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val bitmap = runCatching {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    }.getOrNull()
                    runCatching { buffer.close() }
                    if (bitmap != null) {
                        val half = Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width / 2).coerceAtLeast(1),
                            (bitmap.height / 2).coerceAtLeast(1),
                            true,
                        )
                        bitmap.recycle()
                        DepthEngine.publishFrame(half, half.width, half.height)
                    }
                }

                override fun onFailure(errorCode: Int) = Unit
            })
        }
    }

    private fun applyHome(home: Boolean) {
        if (home == onHome) return
        onHome = home
        homeSince = if (home) SystemClock.elapsedRealtime() else 0L
        if (home && (awaitHome || !tilt.isArmed())) {
            // Arriving on the home screen is the only moment a centre is learned
            // after enabling: pause, watch, then commit.
            awaitHome = false
            overlay?.visibility = View.INVISIBLE
            tilt.beginRecalibration()
        }
        if (!home) {
            DepthEngine.clearFrame()
            DepthEngine.reset()
        }
    }

    /**
     * A small pill near the bottom, drawn by us. Toasts from a background app are
     * dropped by the system since Android 12, and this app is always in the
     * background while the fold matters.
     */
    private fun showChip(text: String, sticky: Boolean) {
        val wm = getSystemService(WindowManager::class.java)
        val view = chip ?: android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setPadding(36, 18, 36, 18)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(215, 20, 20, 24))
                cornerRadius = 40f
            }
            gravity = android.view.Gravity.CENTER
            chip = this
        }
        view.text = text
        val lp = chipParams()
        if (view.parent == null) {
            runCatching { wm.addView(view, lp) }
        } else {
            runCatching { wm.updateViewLayout(view, lp) }
        }
        view.bringToFront()
        if (!sticky) {
            mainHandler.postDelayed({ hideChip() }, 900L)
        }
    }

    private fun hideChip() {
        chip?.let { c ->
            if (c.parent != null) runCatching { getSystemService(WindowManager::class.java).removeViewImmediate(c) }
        }
        chip = null
    }

    private fun chipParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = (140 * resources.displayMetrics.density).toInt()
    }

    private fun isLocked(): Boolean = runCatching {
        getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
    }.getOrDefault(false)

    private fun registerScreenReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    DepthEngine.reset()
                    overlay?.visibility = View.INVISIBLE
                }
            }
        }
        screenReceiver = receiver
        runCatching { registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
    }

    companion object {
        const val PREFS = "duo"
        const val KEY_ENABLED = "enabled"
        const val KEY_ANGLE = "angle"
        const val KEY_STRENGTH = "strength"
        private const val LAUNCHER = "com.google.android.apps.nexuslauncher"
        private const val SETTLE_MS = 250L
        private const val RECALIBRATE_COOLDOWN_MS = 1500L
        private const val SUPPRESS_MS = 1600L
        private const val CAPTURE_MS = 250L
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        @Volatile
        private var instance: GestureService? = null

        fun isRunning() = instance != null

        fun refresh() = instance?.apply()

        fun recentre() {
            instance?.tilt?.calibrate()
        }

        fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
