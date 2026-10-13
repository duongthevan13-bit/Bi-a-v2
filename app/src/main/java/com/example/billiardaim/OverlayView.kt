package com.example.billiardaim

import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lớp Canvas trong suốt vẽ đè lên màn hình.
 *
 * Luồng ngắm (khi overlay nhận cảm ứng, xem CaptureService.setAimMode):
 *  1. Chạm + kéo: vẽ đường chỉ dẫn đứt đoạn, phản xạ tối đa [MAX_BOUNCES] lần
 *     (góc tới = góc phản xạ) trong khung bàn đã phát hiện.
 *     Gốc đường ngắm là bi trắng nếu Detector đã thấy, nếu không thì là điểm chạm đầu.
 *     Hướng ngắm = từ gốc tới ngón tay.
 *  2. Nhấc tay (chọc): đường chỉ dẫn biến mất NGAY, thay bằng một tia sáng ngắn chạy
 *     dọc quỹ đạo, có vòng sóng tại mỗi điểm chạm băng, rồi tắt hẳn.
 *
 * Mọi toạ độ bên trong lớp này là toạ độ MÀN HÌNH THẬT (cùng hệ với Detector).
 * Kích thước lấy từ chính View (MATCH_PARENT + NO_LIMITS) nên tự khớp khi đổi máy / xoay màn hình.
 *
 * Lưu ý quan trọng: MediaProjection chụp cả overlay này. Màu đường kẻ (cam/vàng) cố ý
 * khác dải HSV của bi trắng (S thấp, V cao) để bộ phát hiện không nhận nhầm. Không dùng màu trắng.
 */
class OverlayView(context: Context) : View(context) {

    companion object {
        /** Số lần phản xạ thành băng tối đa của đường chỉ dẫn. */
        const val MAX_BOUNCES = 3
        private const val MIN_DRAG_DP = 12f
        private const val SHOT_ANIM_MS = 450L
    }

    private val density = resources.displayMetrics.density

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density / 2f   // ~2-3 px theo đặc tả
        strokeCap = Paint.Cap.ROUND
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(10f * density, 6f * density), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00")
        style = Paint.Style.FILL
    }
    private val tablePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6D00")
        textSize = 14f * density
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF00FF")   // tím hồng: khác dải trắng và màu vải
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    // Tia sáng sau khi chọc: vàng đậm (S cao) nên không lẫn với bi trắng.
    private val shotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEA00")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val shotHeadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEA00")
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEA00")
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    // ---- Dữ liệu từ Detector (luồng nền ghi, UI đọc) ----
    @Volatile private var tableRect: RectF? = null
    @Volatile private var cueBall: PointF? = null
    @Volatile private var cueRadius = 0f
    @Volatile private var status = "Overlay OK"
    private var path: List<PointF> = emptyList()

    // ---- Trạng thái ngắm (chỉ đụng tới trên luồng UI) ----
    private var aiming = false
    private var aimOrigin: PointF? = null
    private var aimTarget: PointF? = null

    // ---- Hiệu ứng sau khi chọc ----
    private var shotMeasure: PathMeasure? = null
    private var shotVertexDist = FloatArray(0)
    private var shotProgress = 0f
    private var shotAnimator: ValueAnimator? = null
    private val shotSegment = Path()
    private val shotPos = FloatArray(2)

    /** Gọi khi người dùng nhấc tay: nhận quỹ đạo vừa chọc (toạ độ màn hình). */
    var onShot: ((List<PointF>) -> Unit)? = null

    fun setTable(rect: RectF?) { tableRect = rect; postInvalidate() }
    fun setCueBall(center: PointF?, radius: Float = 0f) { cueBall = center; cueRadius = radius; postInvalidate() }
    fun setStatus(text: String) { status = text; postInvalidate() }
    fun setAimPath(points: List<PointF>) { path = points; postInvalidate() }

    // ------------------------------------------------------------------ cảm ứng

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // event.x/y là toạ độ trong View; đổi sang toạ độ màn hình cho cùng hệ với Detector.
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val sx = event.x + loc[0]
        val sy = event.y + loc[1]

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelShot()                       // chạm mới: tắt hiệu ứng cũ ngay
                aiming = true
                aimOrigin = PointF(sx, sy)
                aimTarget = PointF(sx, sy)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!aiming) return false
                aimTarget = PointF(sx, sy)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!aiming) return false
                aimTarget = PointF(sx, sy)
                val shot = currentGuide()          // chốt quỹ đạo lần cuối
                endAim()                           // ẨN đường chỉ dẫn ngay lập tức
                performClick()
                if (shot.size >= 2) {
                    startShotAnimation(shot)
                    onShot?.invoke(shot)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                endAim()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun endAim() {
        aiming = false
        aimOrigin = null
        aimTarget = null
    }

    // ------------------------------------------------------------------ vẽ

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Toạ độ phát hiện là toạ độ màn hình thật; trừ vị trí cửa sổ overlay
        // trên màn hình để khung không bị lệch do tai thỏ / thanh trạng thái.
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

        tableRect?.let { canvas.drawRect(it, tablePaint) }

        when {
            aiming -> drawGuide(canvas)
            shotMeasure != null -> drawShot(canvas)
            path.size >= 2 -> {
                for (i in 0 until path.size - 1) {
                    canvas.drawLine(path[i].x, path[i].y, path[i + 1].x, path[i + 1].y, linePaint)
                }
            }
        }

        cueBall?.let { canvas.drawCircle(it.x, it.y, max(cueRadius, 8f) + 4f, ballPaint) }
        canvas.restore()

        canvas.drawText(status, 24f * density, 48f * density, textPaint)
    }

    /** Đường chỉ dẫn trước khi chọc: đứt đoạn, mờ dần sau mỗi lần phản xạ. */
    private fun drawGuide(canvas: Canvas) {
        val pts = currentGuide()
        if (pts.size < 2) return
        for (i in 0 until pts.size - 1) {
            guidePaint.alpha = max(110, 255 - i * 45)
            canvas.drawLine(pts[i].x, pts[i].y, pts[i + 1].x, pts[i + 1].y, guidePaint)
        }
        // chấm tại mỗi điểm chạm băng
        for (i in 1 until pts.size - 1) canvas.drawCircle(pts[i].x, pts[i].y, 3f * density, dotPaint)
        // tâm ngắm tại ngón tay
        aimTarget?.let { canvas.drawCircle(it.x, it.y, 10f * density, guidePaint.also { p -> p.alpha = 255 }) }
    }

    /** Tia sáng chạy dọc quỹ đạo + vòng sóng tại mỗi điểm chạm băng. */
    private fun drawShot(canvas: Canvas) {
        val pm = shotMeasure ?: return
        val total = pm.length
        if (total <= 0f) return
        val head = total * shotProgress
        val tail = max(0f, head - total * 0.3f)
        val fade = 1f - max(0f, (shotProgress - 0.7f) / 0.3f)

        shotSegment.rewind()
        if (pm.getSegment(tail, head, shotSegment, true)) {
            shotPaint.alpha = (255 * fade).toInt()
            canvas.drawPath(shotSegment, shotPaint)
        }

        val r0 = max(cueRadius, 6f * density)
        for (i in 1 until shotVertexDist.size - 1) {
            val age = (head - shotVertexDist[i]) / (total * 0.25f)
            if (age < 0f || age > 1f) continue
            // vòng sóng nở ra rồi mờ đi
            ringPaint.alpha = (255 * (1f - age) * fade).toInt()
            canvas.drawCircle(shotVerts[i].x, shotVerts[i].y, r0 * (1f + age), ringPaint)
        }

        pm.getPosTan(head, shotPos, null)
        shotHeadPaint.alpha = (255 * fade).toInt()
        canvas.drawCircle(shotPos[0], shotPos[1], max(cueRadius * 0.6f, 4f * density), shotHeadPaint)
    }

    // ------------------------------------------------------------------ hiệu ứng chọc

    private var shotVerts: List<PointF> = emptyList()

    private fun startShotAnimation(pts: List<PointF>) {
        cancelShot()
        val p = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        }
        val pm = PathMeasure(p, false)
        if (pm.length <= 0f) return

        shotVerts = pts
        shotVertexDist = FloatArray(pts.size).also { d ->
            for (i in 1 until pts.size) d[i] = d[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        shotMeasure = pm
        shotProgress = 0f
        shotAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SHOT_ANIM_MS
            interpolator = AccelerateInterpolator(1.2f)
            addUpdateListener { shotProgress = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    shotMeasure = null
                    shotAnimator = null
                    invalidate()
                }
            })
            start()
        }
    }

    private fun cancelShot() {
        shotAnimator?.let { it.removeAllListeners(); it.cancel() }
        shotAnimator = null
        shotMeasure = null
    }

    override fun onDetachedFromWindow() {
        cancelShot()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ hình học

    /** Quỹ đạo hiện tại của đường ngắm (toạ độ màn hình), rỗng nếu kéo quá ngắn. */
    private fun currentGuide(): List<PointF> {
        val target = aimTarget ?: return emptyList()
        val origin = cueBall ?: aimOrigin ?: return emptyList()
        val dx = target.x - origin.x
        val dy = target.y - origin.y
        if (hypot(dx, dy) < MIN_DRAG_DP * density) return emptyList()

        val b = playBounds()
        val xy = bouncePath(origin.x, origin.y, dx, dy, b.left, b.top, b.right, b.bottom, MAX_BOUNCES)
        return List(xy.size / 2) { PointF(xy[it * 2], xy[it * 2 + 1]) }
    }

    /**
     * Vùng tâm bi có thể đi tới: khung bàn Detector tìm được, thu vào một bán kính bi.
     * Chưa có bàn thì dùng đúng kích thước View (chính là kích thước màn hình thật), chừa lề.
     */
    private fun playBounds(): RectF {
        val tr = tableRect
        val b = if (tr != null && tr.width() > 0f && tr.height() > 0f) {
            RectF(tr)
        } else {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            val m = 24f * density
            RectF(loc[0] + m, loc[1] + m, loc[0] + width - m, loc[1] + height - m)
        }
        val inset = max(cueRadius, 0f)
        if (b.width() > 2 * inset && b.height() > 2 * inset) b.inset(inset, inset)
        return b
    }
}

/**
 * Phản xạ tia trong hình chữ nhật [l,r]x[t,b], tối đa [maxBounces] lần chạm băng
 * (đoạn cuối chạy tiếp tới băng kế tiếp). Trả về dãy x0,y0,x1,y1,...
 * Gốc nằm ngoài khung được kẹp vào trong. Góc bàn đảo cả hai thành phần hướng.
 */
internal fun bouncePath(
    ox: Float, oy: Float, dirX: Float, dirY: Float,
    l: Float, t: Float, r: Float, b: Float, maxBounces: Int
): FloatArray {
    var x = ox.coerceIn(min(l, r), max(l, r))
    var y = oy.coerceIn(min(t, b), max(t, b))
    val out = ArrayList<Float>()
    out.add(x); out.add(y)

    val len = hypot(dirX, dirY)
    if (len < 1e-6f || r - l <= 0f || b - t <= 0f) return out.toFloatArray()
    var dx = dirX / len
    var dy = dirY / len

    val eps = 1e-4f
    for (seg in 0..maxBounces) {
        val tx = when {
            dx > eps -> (r - x) / dx
            dx < -eps -> (l - x) / dx
            else -> Float.MAX_VALUE
        }
        val ty = when {
            dy > eps -> (b - y) / dy
            dy < -eps -> (t - y) / dy
            else -> Float.MAX_VALUE
        }
        val step = min(tx, ty)
        if (step == Float.MAX_VALUE) break

        x += dx * step
        y += dy * step
        out.add(x); out.add(y)

        if (abs(tx - ty) <= 1e-3f) { dx = -dx; dy = -dy }      // đúng góc bàn
        else if (tx < ty) dx = -dx
        else dy = -dy
    }
    return out.toFloatArray()
}
