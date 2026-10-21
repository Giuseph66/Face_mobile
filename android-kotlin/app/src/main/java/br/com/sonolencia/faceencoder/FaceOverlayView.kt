package br.com.sonolencia.faceencoder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/** Lightweight landmarks over CameraX's independent native preview. No video bitmap uploads. */
class FaceOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    @Volatile var showIndices = false
    @Volatile var enrollmentGuide: EnrollmentGuideState? = null
    val selectedFace = AtomicInteger(0)
    val selectedPoint = AtomicInteger(1)
    private val frameLock = Any()
    private val landmarks = Array(8) { FloatArray(478 * 3) }
    private val projected = FloatArray(8 * 478 * 2)
    private var faceCount = 0
    private var imageWidth = 480
    private var imageHeight = 640
    private var fps = 0f
    private val density = resources.displayMetrics.density
    private var handsMode = false
    private val hands = HandMapping()
    private val handRenderer = HandOverlayRenderer(density)
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.8f * density
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 213, 112)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(240, 246, 242)
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val labelPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(2f * density, 0f, density, 0xFF101714.toInt())
    }
    private val panelPaint = Paint().apply { color = 0xEE101714.toInt() }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * density }
    private val pointLabels = Array(478) { it.toString() }
    private val faceLabels = Array(8) { "Rosto ${it + 1}" }

    init {
        isClickable = true
        contentDescription = "Pontos dos rostos. Toque para selecionar um ponto de qualquer rosto."
    }

    /** Copy only small coordinate arrays; the full video remains in CameraX's native surface. */
    fun render(frame: FaceFrame) {
        synchronized(frameLock) {
            faceCount = frame.faceCount
            imageWidth = frame.width
            imageHeight = frame.height
            fps = frame.fps
            handsMode = frame.handsMode
            if (handsMode) frame.hands.copyInto(hands)
            repeat(faceCount) { frame.faces[it].landmarks.copyInto(landmarks[it]) }
            repeat(faceCount) { faceLabels[it] = "${it + 1} · ${frame.faces[it].label}" }
        }
        postInvalidateOnAnimation()
    }

    fun clear() {
        synchronized(frameLock) { faceCount = 0; hands.handCount = 0 }
        postInvalidateOnAnimation()
    }

    fun handSelection(): Pair<Long, Int> = synchronized(frameLock) { handRenderer.selectedTrackId to handRenderer.selectedPoint }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(frameLock) {
            if (handsMode) {
                handRenderer.draw(canvas, hands, imageWidth, imageHeight, showIndices)
                return
            }
            val scale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
            val left = (width - imageWidth * scale) / 2f
            val top = (height - imageHeight * scale) / 2f
            val selected = selectedFace.get().coerceIn(0, (faceCount - 1).coerceAtLeast(0))
            val point = selectedPoint.get().coerceIn(0, 477)
            val textSize = min(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics), width / 24f)
            textPaint.textSize = textSize
            labelPaint.textSize = textSize
            for (faceIndex in 0 until faceCount) {
                val raw = landmarks[faceIndex]
                val offset = faceIndex * 478 * 2
                var minX = width.toFloat()
                var minY = height.toFloat()
                var maxX = 0f
                var maxY = 0f
                repeat(478) { index ->
                    val x = left + raw[index * 3] * imageWidth * scale
                    val y = top + raw[index * 3 + 1] * imageHeight * scale
                    projected[offset + index * 2] = x
                    projected[offset + index * 2 + 1] = y
                    minX = min(minX, x)
                    minY = min(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
                pointPaint.color = FaceColors.at(faceIndex)
                labelPaint.color = pointPaint.color
                canvas.drawPoints(projected, offset, 478 * 2, pointPaint)
                val guide = enrollmentGuide
                if (guide != null && faceIndex == 0) {
                    guidePaint.color = if (guide.hold > 0.9f) 0xFF7EF3B4.toInt() else 0xFFFFD570.toInt()
                    canvas.drawOval(minX - 8 * density, minY - 10 * density, maxX + 8 * density, maxY + 10 * density, guidePaint)
                    canvas.drawArc(minX - 12 * density, minY - 14 * density, maxX + 12 * density,
                        maxY + 14 * density, -90f, guide.hold * 360f, false, guidePaint)
                }
                val faceText = android.text.TextUtils.ellipsize(if (guide != null) "Cadastro · ${guide.samples}/15" else faceLabels[faceIndex], labelPaint,
                    width - 8f * density, android.text.TextUtils.TruncateAt.END).toString()
                canvas.drawText(faceText, minX.coerceIn(4f, maxOf(4f, width - labelPaint.measureText(faceText) - 4f)),
                    (minY - 8f * density).coerceAtLeast(textSize), labelPaint)
                if (showIndices) {
                    labelPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
                    repeat(478) { index ->
                        canvas.drawText(pointLabels[index], projected[offset + index * 2],
                            projected[offset + index * 2 + 1], labelPaint)
                    }
                    labelPaint.textSize = textSize
                }
            }
            if (faceCount > 0) {
                val offset = (selected * 478 + point) * 2
                canvas.drawCircle(projected[offset], projected[offset + 1], 5f * density, selectedPaint)
            }
            val spacing = textSize * 1.65f
            val panelTop = height - spacing * 2.5f
            canvas.drawRect(0f, panelTop, width.toFloat(), height.toFloat(), panelPaint)
            canvas.drawText(String.format(Locale.US, "%d rosto%s · análise %.1f FPS",
                faceCount, if (faceCount == 1) "" else "s", fps), 10f * density, panelTop + spacing, textPaint)
            val detail = if (faceCount > 0) {
                val raw = landmarks[selected]
                val index = point * 3
                String.format(Locale.US, "R%d #%d x%.3f y%.3f z%+.3f", selected + 1, point,
                    raw[index], raw[index + 1], raw[index + 2])
            } else "Posicione os rostos na câmera"
            canvas.drawText(detail, 10f * density, panelTop + spacing * 2f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        synchronized(frameLock) {
            if (handsMode) handRenderer.select(event.x, event.y, hands, imageWidth, imageHeight, width, height)
            else if (faceCount > 0) {
                var nearest = 0
                var distance = Float.MAX_VALUE
                repeat(faceCount * 478) { index ->
                    val dx = projected[index * 2] - event.x
                    val dy = projected[index * 2 + 1] - event.y
                    val candidate = dx * dx + dy * dy
                    if (candidate < distance) { nearest = index; distance = candidate }
                }
                selectedFace.set(nearest / 478)
                selectedPoint.set(nearest % 478)
            }
        }
        invalidate()
        performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
