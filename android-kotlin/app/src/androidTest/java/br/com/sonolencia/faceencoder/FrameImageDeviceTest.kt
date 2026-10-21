package br.com.sonolencia.faceencoder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class FrameImageDeviceTest {
    private fun assertPixelsEqual(message: String, expected: Bitmap, actual: Bitmap) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)
        val left = IntArray(expected.width * expected.height)
        val right = IntArray(left.size)
        expected.getPixels(left, 0, expected.width, 0, 0, expected.width, expected.height)
        actual.getPixels(right, 0, actual.width, 0, 0, actual.width, actual.height)
        assertArrayEquals(message, left, right)
    }

    private fun detector() = FaceLandmarker.createFromOptions(
        InstrumentationRegistry.getInstrumentation().targetContext,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO).setNumFaces(1).build(),
    )

    @Test fun preservesRotatedCoordinatesDepthMirroringAndPaddedPixels() {
        val original = InstrumentationRegistry.getInstrumentation().context.assets.open("portrait.jpg").use {
            BitmapFactory.decodeStream(it)
        }
        val upright = Bitmap.createScaledBitmap(original, 480, 600, true)
        val referenceImage = BitmapImageBuilder(upright).build()
        val reference = detector().use { it.detectForVideo(referenceImage, 1).faceLandmarks().single() }
        for (degrees in intArrayOf(0, 90, 180, 270)) {
            val sensor = Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height,
                Matrix().apply { postRotate(-degrees.toFloat()) }, false)
            val raw = ByteBuffer.allocateDirect(sensor.byteCount)
            sensor.copyPixelsToBuffer(raw)
            val rowBytes = sensor.width * 4
            val stride = rowBytes + 16
            val padded = ByteBuffer.allocateDirect(stride * (sensor.height - 1) + rowBytes)
            repeat(sensor.height) { row ->
                raw.limit(raw.capacity()); raw.position(row * rowBytes); raw.limit((row + 1) * rowBytes)
                padded.position(row * stride); padded.put(raw)
            }
            for (mirrored in listOf(false, true)) {
                FrameImage(sensor.width, sensor.height, degrees, mirrored).use { frame ->
                    frame.update(padded, stride, 4)
                    val result = detector().use { it.detectForVideo(frame.image, frame.options, 1).faceLandmarks().single() }
                    val points = FloatArray(1434)
                    result.forEachIndexed { index, point -> frame.writePoint(points, index * 3, point.x(), point.y(), point.z()) }
                    var xyError = 0f
                    var zError = 0f
                    reference.forEachIndexed { index, point ->
                        xyError += abs(points[index * 3] - if (mirrored) 1f - point.x() else point.x())
                        xyError += abs(points[index * 3 + 1] - point.y())
                        zError += abs(points[index * 3 + 2] - point.z())
                    }
                    assertEquals("XY rotation=$degrees mirror=$mirrored", 0f, xyError / 956, 0.01f)
                    assertEquals("Z rotation=$degrees mirror=$mirrored", 0f, zError / 478, 0.002f)
                    val capture = frame.snapshot()
                    assertTrue(capture.isMutable)
                    val expected = if (mirrored) Bitmap.createBitmap(upright, 0, 0, upright.width, upright.height,
                        Matrix().apply { setScale(-1f, 1f) }, false) else upright
                    assertPixelsEqual("Snapshot rotation=$degrees mirror=$mirrored", expected, capture)
                    capture.recycle()
                    if (expected !== upright) expected.recycle()
                    if (degrees == 90 && !mirrored) {
                        detector().use { task ->
                            assertEquals(1, task.detectForVideo(frame.image, frame.options, 1).faceLandmarks().size)
                            // Destroy the caller's input: an export must retain the analyzed pixels.
                            padded.clear(); while (padded.hasRemaining()) padded.put(0.toByte())
                            val retained = frame.snapshot()
                            assertPixelsEqual("Retained pixels", upright, retained); retained.recycle()
                            frame.update(padded, stride, 4)
                            var count = 1
                            repeat(10) { count = task.detectForVideo(frame.image, frame.options, (it + 2) * 34L).faceLandmarks().size }
                            assertEquals("A reused RGBA MPImage must read updated pixels", 0, count)
                        }
                        // Restore the padded input for the mirrored case.
                        repeat(sensor.height) { row ->
                            raw.limit(raw.capacity()); raw.position(row * rowBytes); raw.limit((row + 1) * rowBytes)
                            padded.limit(padded.capacity()); padded.position(row * stride); padded.put(raw)
                        }
                    }
                    Log.i("FaceEncoderTest", "RGBA rotation=$degrees mirror=$mirrored XY=${xyError / 956} Z=${zError / 478} snapshot OK")
                }
            }
            if (sensor !== upright) sensor.recycle()
        }
        referenceImage.close()
        if (!original.isRecycled) original.recycle()
    }

    @Test fun measuresPreprocessingAgainstBitmapRotation() {
        val sensor = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val rotated = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rotated)
        val transform = Matrix().apply { postRotate(90f); postTranslate(480f, 0f); postScale(-1f, 1f, 240f, 320f) }
        val buffer = ByteBuffer.allocateDirect(sensor.byteCount)
        val legacy = FloatArray(100)
        val direct = FloatArray(100)
        FrameImage(640, 480, 90, true).use { frame ->
            repeat(120) { index ->
                val start = SystemClock.elapsedRealtimeNanos()
                buffer.rewind(); sensor.copyPixelsFromBuffer(buffer)
                canvas.drawBitmap(sensor, transform, null)
                val middle = SystemClock.elapsedRealtimeNanos()
                frame.update(buffer, 640 * 4, 4)
                val end = SystemClock.elapsedRealtimeNanos()
                if (index >= 20) {
                    legacy[index - 20] = (middle - start) / 1_000_000f
                    direct[index - 20] = (end - middle) / 1_000_000f
                }
            }
        }
        legacy.sort(); direct.sort()
        Log.i("FaceEncoderTest", "Preparation median: bitmapRotation=${legacy[50]}ms packedRGBA=${direct[50]}ms")
        sensor.recycle(); rotated.recycle()
    }
}
