package br.com.sonolencia.faceencoder

import android.graphics.Canvas
import android.graphics.Paint
import java.util.Locale
import kotlin.math.min

/** Shared lightweight skeleton renderer for the native preview and explicit saved captures. */
class HandOverlayRenderer(private val density: Float) {
    var selectedTrackId = 0L
    var selectedPoint = 8
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val point = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.MONOSPACE
        setShadowLayer(2f * density, 0f, density, 0xFF101714.toInt())
    }
    private val panel = Paint().apply { color = 0xEE101714.toInt() }
    private val indices = Array(21) { it.toString() }

    fun draw(canvas: Canvas, data: HandMapping, imageWidth: Int, imageHeight: Int, showIndices: Boolean) {
        val scale = min(canvas.width.toFloat() / imageWidth, canvas.height.toFloat() / imageHeight)
        val w = imageWidth * scale
        val h = imageHeight * scale
        val left = (canvas.width - w) / 2f
        val top = (canvas.height - h) / 2f
        fun x(p: FloatArray, i: Int) = left + p[i * 3] * w
        fun y(p: FloatArray, i: Int) = top + p[i * 3 + 1] * h
        text.textSize = min(13f * density, canvas.width / 32f)
        val textSize = text.textSize
        canvas.save()
        canvas.clipRect(left, top, left + w, top + h)
        if (data.handCount > 0 && (0 until data.handCount).none { data.hands[it].trackId == selectedTrackId })
            selectedTrackId = data.hands[0].trackId
        for (index in 0 until data.handCount) {
            val hand = data.hands[index]
            val p = hand.landmarks
            val color = FaceColors.at((hand.trackId % 8).toInt())
            line.color = color
            line.strokeWidth = 2.4f * density
            point.color = color
            for (i in HandConnections.hand.indices step 2) {
                val a = HandConnections.hand[i]; val b = HandConnections.hand[i + 1]
                canvas.drawLine(x(p, a), y(p, a), x(p, b), y(p, b), line)
            }
            for (i in 0 until 21) {
                val tip = i > 0 && i % 4 == 0
                canvas.drawCircle(x(p, i), y(p, i), (if (tip) 4f else 2.6f) * density, point)
                if (showIndices) {
                    text.color = color; text.textSize = 10f * density
                    canvas.drawText(indices[i], x(p, i) + 4f * density, y(p, i), text)
                }
            }
            if (hand.pinching) {
                line.color = 0xFFFFD570.toInt()
                canvas.drawLine(x(p, 4), y(p, 4), x(p, 8), y(p, 8), line)
            }
            text.textSize = textSize
            text.color = color
            val label = "Mão #${hand.trackId}${if (hand.pinching) " · Pinça" else ""}"
            canvas.drawText(label, x(p, 0).coerceIn(4f, maxOf(4f, canvas.width - text.measureText(label) - 4f)),
                (y(p, 0) + textSize * 1.5f).coerceIn(textSize, canvas.height - textSize), text)
            if (hand.trackId == selectedTrackId) {
                point.color = 0xFFFFD570.toInt()
                canvas.drawCircle(x(p, selectedPoint), y(p, selectedPoint), 5.5f * density, point)
            }
        }
        canvas.restore()
        text.textSize = textSize
        text.color = 0xFFF0F6F2.toInt()
        val spacing = textSize * 1.65f
        val panelTop = canvas.height - spacing * 2.5f
        canvas.drawRect(0f, panelTop, canvas.width.toFloat(), canvas.height.toFloat(), panel)
        canvas.drawText(String.format(Locale.US, "%d mão%s · análise %.1f FPS", data.handCount, if (data.handCount == 1) "" else "s",
            data.handsFps), 10f * density, panelTop + spacing, text)
        val selected = (0 until data.handCount).firstOrNull { data.hands[it].trackId == selectedTrackId }?.let { data.hands[it] }
        val detail = if (selected != null) {
            val i = selectedPoint * 3
            String.format(Locale.US, "M%d #%d x%.3f y%.3f z%+.3f", selected.trackId, selectedPoint,
                selected.landmarks[i], selected.landmarks[i + 1], selected.landmarks[i + 2])
        } else "Mostre suas mãos na câmera"
        canvas.drawText(detail, 10f * density, panelTop + spacing * 2f, text)
    }

    fun select(x: Float, y: Float, data: HandMapping, imageWidth: Int, imageHeight: Int, width: Int, height: Int) {
        val scale = min(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val left = (width - imageWidth * scale) / 2f
        val top = (height - imageHeight * scale) / 2f
        var distance = (48f * density) * (48f * density)
        for (hand in 0 until data.handCount) for (i in 0 until 21) {
            val p = data.hands[hand].landmarks
            val dx = left + p[i * 3] * imageWidth * scale - x
            val dy = top + p[i * 3 + 1] * imageHeight * scale - y
            val candidate = dx * dx + dy * dy
            if (candidate < distance) {
                distance = candidate
                selectedTrackId = data.hands[hand].trackId
                selectedPoint = i
            }
        }
    }
}
