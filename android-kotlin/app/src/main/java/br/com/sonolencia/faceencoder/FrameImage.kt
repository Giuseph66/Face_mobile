package br.com.sonolencia.faceencoder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import com.google.mediapipe.framework.image.ByteBufferImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import java.io.Closeable
import java.nio.ByteBuffer

/** Owns one packed RGBA buffer, retained after CameraX releases the source image. */
class FrameImage(val sourceWidth: Int, val sourceHeight: Int, val degrees: Int, val mirrored: Boolean) : Closeable {
    init { require(degrees in intArrayOf(0, 90, 180, 270)) }
    private val rotated = degrees == 90 || degrees == 270
    val width = if (rotated) sourceHeight else sourceWidth
    val height = if (rotated) sourceWidth else sourceHeight
    private val pixels = ByteBuffer.allocateDirect(sourceWidth * sourceHeight * 4)
    val image: MPImage = ByteBufferImageBuilder(pixels, sourceWidth, sourceHeight, MPImage.IMAGE_FORMAT_RGBA).build()
    val options: ImageProcessingOptions = ImageProcessingOptions.builder().setRotationDegrees(degrees).build()

    fun update(buffer: ByteBuffer, rowStride: Int, pixelStride: Int) {
        require(pixelStride == 4) { "A câmera precisa fornecer RGBA8888." }
        val rowBytes = sourceWidth * 4
        require(rowStride >= rowBytes)
        pixels.clear()
        buffer.rewind()
        val limit = buffer.limit()
        try {
            if (rowStride == rowBytes) {
                buffer.limit(rowBytes * sourceHeight)
                pixels.put(buffer)
            } else {
                for (row in 0 until sourceHeight) {
                    buffer.limit(limit)
                    buffer.position(row * rowStride)
                    buffer.limit(buffer.position() + rowBytes)
                    pixels.put(buffer)
                }
            }
        } finally {
            buffer.limit(limit)
            buffer.rewind()
            pixels.rewind()
        }
    }

    /** MediaPipe returns sensor coordinates; publish coordinates of the upright preview/export. */
    fun writePoint(output: FloatArray, offset: Int, x: Float, y: Float, z: Float) {
        val displayX: Float
        val displayY: Float
        when (degrees) {
            90 -> { displayX = 1f - y; displayY = x }
            180 -> { displayX = 1f - x; displayY = 1f - y }
            270 -> { displayX = y; displayY = 1f - x }
            else -> { displayX = x; displayY = y }
        }
        output[offset] = if (mirrored) 1f - displayX else displayX
        output[offset + 1] = displayY
        // MediaPipe's projected Z already follows the normalized ROI. Quarter-turn
        // permutations of normalized X/Y preserve its scale; mirroring preserves depth.
        output[offset + 2] = z
    }

    /** Bilinear RGB sampling in upright, UNMIRRORED pixel coordinates, into an NCHW crop. */
    fun readUprightRgb(x: Float, y: Float, output: FloatArray, index: Int) {
        val sx: Float
        val sy: Float
        when (degrees) {
            90 -> { sx = y; sy = sourceHeight - 1f - x }
            180 -> { sx = sourceWidth - 1f - x; sy = sourceHeight - 1f - y }
            270 -> { sx = sourceWidth - 1f - y; sy = x }
            else -> { sx = x; sy = y }
        }
        if (!sx.isFinite() || !sy.isFinite() || sx < 0 || sy < 0 || sx > sourceWidth - 1 || sy > sourceHeight - 1) {
            repeat(3) { output[index + it * 12544] = 0f }; return
        }
        val ix = sx.toInt(); val iy = sy.toInt()
        val nextX = minOf(ix + 1, sourceWidth - 1); val nextY = minOf(iy + 1, sourceHeight - 1)
        val fx = sx - ix; val fy = sy - iy
        repeat(3) { channel ->
            val p00 = pixels.get((iy * sourceWidth + ix) * 4 + channel).toInt() and 255
            val p10 = pixels.get((iy * sourceWidth + nextX) * 4 + channel).toInt() and 255
            val p01 = pixels.get((nextY * sourceWidth + ix) * 4 + channel).toInt() and 255
            val p11 = pixels.get((nextY * sourceWidth + nextX) * 4 + channel).toInt() and 255
            output[index + channel * 12544] = ((p00 * (1 - fx) + p10 * fx) * (1 - fy) + (p01 * (1 - fx) + p11 * fx) * fy) / 255f
        }
    }

    /** Bitmap allocation and rotation happen only for an explicit capture. */
    fun snapshot(): Bitmap {
        val source = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888)
        pixels.rewind()
        source.copyPixelsFromBuffer(pixels)
        pixels.rewind()
        if (degrees == 0 && !mirrored) return source
        val transform = Matrix().apply {
            postRotate(degrees.toFloat())
            val bounds = RectF(0f, 0f, sourceWidth.toFloat(), sourceHeight.toFloat())
            mapRect(bounds)
            postTranslate(-bounds.left, -bounds.top)
            if (mirrored) postScale(-1f, 1f, width / 2f, height / 2f)
        }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(source, transform, null)
        source.recycle()
        return result
    }

    override fun close() { image.close() }
}
