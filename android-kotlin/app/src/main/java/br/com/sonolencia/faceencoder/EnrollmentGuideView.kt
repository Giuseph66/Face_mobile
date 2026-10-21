package br.com.sonolencia.faceencoder

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs

/** Small code-drawn demonstration; no video, network or bitmap allocations. */
class EnrollmentGuideView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    var guide = EnrollmentGuideState(EnrollmentPose.FRONT, 1, 0, 0f)
        set(value) {
            val directionChanged = field.pose != value.pose || field.returning != value.returning
            field = value
            val description = "Demonstração: ${value.instruction}. ${value.stageText}"
            if (contentDescription?.toString() != description) contentDescription = description
            animateIfVisible()
            if (directionChanged) invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private var phase = 0f
    private var animator: ValueAnimator? = null

    private fun animateIfVisible() {
        if (!isAttachedToWindow || !isShown || windowVisibility != VISIBLE || guide.returning || guide.pose == EnrollmentPose.FRONT) {
            animator?.cancel(); animator = null; phase = 0f; return
        }
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = DecelerateInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animateIfVisible() }
    override fun onDetachedFromWindow() { animator?.cancel(); animator = null; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); animateIfVisible() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); animateIfVisible() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = minOf(width, height) / 100f
        canvas.save()
        canvas.translate((width - 100 * unit) / 2f, (height - 100 * unit) / 2f)
        canvas.scale(unit, unit)
        val target = if (guide.returning) EnrollmentPose.FRONT else guide.pose
        val yaw = target.targetYaw / 17f * phase
        val pitch = target.targetPitch / 12f * phase
        paint.color = 0xFFAAC0B2.toInt(); paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
        canvas.drawOval(21f + abs(yaw) * 4f, 12f, 79f - abs(yaw) * 4f, 83f, paint)
        paint.color = 0xFF7EF3B4.toInt()
        paint.style = Paint.Style.FILL
        canvas.drawCircle(38f + yaw * 4f, 40f + pitch * 5f, 3f, paint)
        canvas.drawCircle(62f + yaw * 4f, 40f + pitch * 5f, 3f, paint)
        paint.style = Paint.Style.STROKE
        canvas.drawLine(50f + yaw * 11f, 45f + pitch * 7f, 52f + yaw * 11f, 58f + pitch * 9f, paint)
        canvas.drawLine(42f + yaw * 5f, 68f + pitch * 6f, 58f + yaw * 5f, 68f + pitch * 6f, paint)
        if (target.targetYaw != 0f) {
            val end = if (target.targetYaw > 0f) 91f else 9f
            val sign = if (target.targetYaw > 0f) 1f else -1f
            canvas.drawLine(50f, 92f, end, 92f, paint)
            canvas.drawLine(end - sign * 7f, 87f, end, 92f, paint)
            canvas.drawLine(end - sign * 7f, 97f, end, 92f, paint)
        } else if (target.targetPitch != 0f) {
            val end = if (target.targetPitch > 0f) 83f else 17f
            val sign = if (target.targetPitch > 0f) 1f else -1f
            canvas.drawLine(90f, 50f, 90f, end, paint)
            canvas.drawLine(85f, end - sign * 7f, 90f, end, paint)
            canvas.drawLine(95f, end - sign * 7f, 90f, end, paint)
        }
        canvas.restore()
    }
}
