package dev.trishi.launchertuner.depth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import dev.trishi.launchertuner.R

/**
 * Bends one edge of the screen inward.
 *
 * The captured frame is a plane behind the glass; a vertical hinge sits at the
 * strip's inner boundary, so the boundary is pinned to the real screen and there
 * is no seam — outside the strip everything is untouched, live and sharp.
 *
 * On API 33+ this runs `res/raw/duo_edge.agsl` per pixel over the strip only:
 * eye ray → glass → plane, Vogel-disk blur proportional to the gap, darkening
 * proportional to the blur, black where the kernel misses. That gives the
 * progressive stretch + frost the Duo reference has, and both scale with the
 * amount of tilt because the tilt angle is driven by depth.
 *
 * Below 33 it falls back to a sliced affine bend.
 */
class DepthOverlayView(context: Context) : View(context) {

    private val contentPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backPaint = Paint().apply { color = Color.BLACK }

    private val dst = RectF()
    private var sideState = 1f

    private var runtime: RuntimeShader? = null
    private var runtimeFailed = false
    private var contentShader: BitmapShader? = null
    private var contentFor: Bitmap? = null

    // Fallback path only.

    override fun onDraw(canvas: Canvas) {
        val depth = DepthEngine.depth
        val frame = DepthEngine.frame
        if (depth < 0.004f || frame == null || frame.isRecycled || DepthEngine.frameWidth <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        // Hinge on the edge opposite the tilt. Hysteresis keeps it from
        // chattering between sides when the tilt hovers around zero.
        val yaw = DepthEngine.yawDeg
        if (yaw > SIDE_HYSTERESIS) sideState = 1f else if (yaw < -SIDE_HYSTERESIS) sideState = -1f
        val hingeRight = sideState > 0f
        val hingeX = if (hingeRight) w else 0f
        val side = if (hingeRight) -1f else 1f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            drawWithShader(canvas, frame, depth, side, hingeX, w, h)
        ) {
            return
        }

        drawEdgeFallback(canvas, frame, depth, side, w, h)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawWithShader(
        canvas: Canvas,
        frame: Bitmap,
        depth: Float,
        side: Float,
        hingeX: Float,
        w: Float,
        h: Float,
    ): Boolean {
        if (runtimeFailed) return false
        val shader = runtime ?: runCatching {
            RuntimeShader(
                context.resources.openRawResource(R.raw.duo_edge)
                    .bufferedReader()
                    .use { it.readText() },
            )
        }.onFailure {
            runtimeFailed = true
            android.util.Log.e("TunerDepth", "AGSL compile failed: ${it.message}")
        }.getOrNull()?.also { runtime = it } ?: return false

        val content = contentShader
            ?: runCatching {
                BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            }.getOrNull()?.also { contentShader = it } ?: return false

        if (contentFor !== frame) {
            contentFor = frame
            contentShader = BitmapShader(frame, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        val bitmapShader = contentShader ?: return false
        // Identity local matrix: the shader multiplies coordinates by
        // contentScale itself, so there is no canvas/shader space ambiguity.
        bitmapShader.setLocalMatrix(null)

        runCatching {
            shader.setInputShader("content", bitmapShader)
            shader.setFloatUniform("resolution", w, h)
            shader.setFloatUniform("contentScale", frame.width / w, frame.height / h)
            shader.setFloatUniform("hingeX", hingeX)
            shader.setFloatUniform("side", side)
            shader.setFloatUniform("tiltDegrees", depth * MAX_TILT_DEG * DepthEngine.strength)
            shader.setFloatUniform("eyeDistancePx", EYE_DISTANCE * h)
            shader.setFloatUniform("blurSpread", BLUR_SPREAD)
            shader.setFloatUniform("darkening", DARKENING)
        }.onFailure {
            runtimeFailed = true
            android.util.Log.e("TunerDepth", "AGSL uniforms failed: ${it.message}")
        }
        if (runtimeFailed) return false

        contentPaint.shader = shader
        canvas.drawRect(0f, 0f, w, h, contentPaint)
        contentPaint.shader = null
        return true
    }

    /** API < 33: darken the free edge; the content itself stays flat. */
    private fun drawEdgeFallback(canvas: Canvas, frame: Bitmap, depth: Float, side: Float, w: Float, h: Float) {
        canvas.drawBitmap(frame, null, RectF(0f, 0f, w, h), contentPaint)
        dimPaint.color = Color.argb((255f * 0.18f * depth).toInt().coerceIn(0, 255), 0, 0, 0)
        val band = w * 0.2f
        canvas.drawRect(
            if (side > 0f) 0f else w - band,
            0f,
            if (side > 0f) band else w,
            h,
            dimPaint,
        )
    }

    private companion object {
        /** Visual fold at full depth. */
        const val MAX_TILT_DEG = 20f

        /** Eye distance as a multiple of screen height. */
        const val EYE_DISTANCE = 1.6f

        /** Blur radius gained per pixel of glass/plane separation. */
        const val BLUR_SPREAD = 0.16f

        /** Light lost per pixel of blur radius. */
        const val DARKENING = 0.010f

        /** Degrees needed to swap the bending edge. */
        const val SIDE_HYSTERESIS = 1.2f
    }
}
