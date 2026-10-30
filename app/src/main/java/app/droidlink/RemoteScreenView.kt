package app.droidlink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the other phone's screen, letterboxed to fit, and turns each one-finger touch here into
 * a tap, long press or swipe in the other phone's coordinates (0..1 on each axis).
 */
class RemoteScreenView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface GestureSink {
        fun onRemoteGesture(points: FloatArray, durationMs: Int)
        fun onBlockedGesture()
    }

    companion object {
        private const val MAX_POINTS = 60
        private const val MIN_STEP = 0.002f
    }

    var sink: GestureSink? = null
    var controlEnabled = true

    private var frame: Bitmap? = null
    private val dst = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val density = resources.displayMetrics.density
    private val fingerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        style = Paint.Style.FILL
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val points = ArrayList<Float>()
    private val trail = Path()
    private var tracking = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var maxDistance = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    fun setFrame(bitmap: Bitmap) {
        val old = frame
        frame = bitmap
        if (old == null || old.width != bitmap.width || old.height != bitmap.height) updateDestination()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateDestination()
        if (Build.VERSION.SDK_INT >= 29) {
            // Keep edge swipes for the other phone instead of this phone's back gesture.
            systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
        }
    }

    private fun updateDestination() {
        val b = frame ?: return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val scale = min(vw / b.width, vh / b.height)
        val w = b.width * scale
        val h = b.height * scale
        dst.set((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val b = frame ?: return
        canvas.drawBitmap(b, null, dst, bitmapPaint)
        if (tracking) {
            canvas.drawPath(trail, trailPaint)
            canvas.drawCircle(lastX, lastY, 18f * density, fingerPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (frame == null || dst.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!dst.contains(event.x, event.y)) return false
                tracking = true
                points.clear()
                trail.reset()
                trail.moveTo(event.x, event.y)
                downTime = event.eventTime
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                maxDistance = 0f
                addPoint(event.x, event.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                for (i in 0 until event.historySize) {
                    addPoint(event.getHistoricalX(i), event.getHistoricalY(i))
                }
                addPoint(event.x, event.y)
                maxDistance = max(maxDistance, hypot(event.x - downX, event.y - downY))
                trail.lineTo(event.x, event.y)
                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                addPoint(event.x, event.y)
                finishGesture(event.eventTime - downTime)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun addPoint(x: Float, y: Float) {
        val nx = ((x - dst.left) / dst.width()).coerceIn(0f, 1f)
        val ny = ((y - dst.top) / dst.height()).coerceIn(0f, 1f)
        val n = points.size
        if (n >= 2 && abs(points[n - 2] - nx) < MIN_STEP && abs(points[n - 1] - ny) < MIN_STEP) return
        points.add(nx)
        points.add(ny)
    }

    private fun finishGesture(elapsedMs: Long) {
        tracking = false
        invalidate()
        if (points.size < 2) return
        if (!controlEnabled) {
            sink?.onBlockedGesture()
            return
        }
        val out: FloatArray
        val duration: Int
        if (maxDistance < touchSlop) {
            // Tap or long press at the starting point.
            out = floatArrayOf(points[0], points[1])
            duration = if (elapsedMs >= longPressMs) max(elapsedMs, 650L).coerceAtMost(5000L).toInt() else 60
        } else {
            out = downsample()
            duration = elapsedMs.coerceIn(80L, 10_000L).toInt()
        }
        sink?.onRemoteGesture(out, duration)
    }

    private fun downsample(): FloatArray {
        val count = points.size / 2
        if (count <= MAX_POINTS) return points.toFloatArray()
        val out = FloatArray(MAX_POINTS * 2)
        for (i in 0 until MAX_POINTS) {
            val index = ((i.toLong() * (count - 1)) / (MAX_POINTS - 1)).toInt()
            out[2 * i] = points[2 * index]
            out[2 * i + 1] = points[2 * index + 1]
        }
        return out
    }
}
