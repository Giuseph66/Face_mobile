package br.com.sonolencia.faceencoder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.UUID
import kotlin.math.sqrt
import kotlin.math.hypot
import kotlin.math.tan

@RunWith(AndroidJUnit4::class)
class FaceRecognitionDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun image(name: String): Bitmap = instrumentation.context.assets.open(name).use { BitmapFactory.decodeStream(it) }

    private fun landmarks(bitmap: Bitmap): FloatArray {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").build())
            .setRunningMode(RunningMode.IMAGE).setNumFaces(1).build()
        val mpImage = BitmapImageBuilder(bitmap.copy(Bitmap.Config.ARGB_8888, false)).build()
        return try {
            FaceLandmarker.createFromOptions(instrumentation.targetContext, options).use { detector ->
                val points = detector.detect(mpImage).faceLandmarks().single()
                FloatArray(1434).also { raw -> points.forEachIndexed { i, p ->
                    raw[i * 3] = p.x(); raw[i * 3 + 1] = p.y(); raw[i * 3 + 2] = p.z()
                } }
            }
        } finally { mpImage.close() }
    }

    private fun crop(bitmap: Bitmap, points: FloatArray, degrees: Int = 0, mirrored: Boolean = false): FloatArray {
        val sensor = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(-degrees.toFloat()) }, false)
        val pixels = ByteBuffer.allocateDirect(sensor.byteCount)
        sensor.copyPixelsToBuffer(pixels)
        val display = points.copyOf()
        if (mirrored) for (i in display.indices step 3) display[i] = 1f - display[i]
        val result = FloatArray(3 * 112 * 112)
        FrameImage(sensor.width, sensor.height, degrees, mirrored).use { frame ->
            frame.update(pixels, sensor.width * 4, 4)
            assertNull("Crop quality", FaceCropper.crop(frame, display, result))
        }
        if (sensor !== bitmap) sensor.recycle()
        return result
    }

    @Test fun recognizesHeldOutVariationsRejectsAnotherPersonAndPreservesCropOrientation() {
        val first = image("portrait.jpg")
        val second = image("another_face.png")
        val points = landmarks(first)
        val a = crop(first, points)
        val b = crop(second, landmarks(second))
        val transformed = crop(first, points, 90, true)
        for (i in a.indices) assertEquals("Crop rotation/mirror at $i", a[i], transformed[i], 0.0002f)
        FaceEmbedder(instrumentation.targetContext).use { model ->
            val reference = model.embed(a, b)
            assertEquals(128, reference[0].size)
            assertEquals(1.0, sqrt(reference[0].sumOf { (it * it).toDouble() }), 0.0001)
            val person = EnrolledPerson("fixture-a", "Pessoa de teste", List(5) { reference[0].copyOf() })
            assertNull("Unenrolled identity must remain unknown", FaceMatcher.match(reference[1], listOf(person)))
            val timings = FloatArray(8)
            for (i in 0..7) {
                // Held-out photometric variations, not independent real-world validation samples.
                val variation = FloatArray(a.size) { (a[it] * (0.82f + i * 0.025f) + 0.02f).coerceIn(0f, 1f) }
                val start = SystemClock.elapsedRealtimeNanos()
                val result = model.embed(variation, b)
                timings[i] = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000f
                assertEquals("Known identity variation $i", person.id, FaceMatcher.match(result[0], listOf(person))?.person?.id)
                assertNull(FaceMatcher.match(result[1], listOf(person)))
            }
            timings.sort()
            Log.i("FaceEncoderTest", "Identity fixtures: genuine=8/8 impostor rejected=8/8 cosineDifferent=${FaceMatcher.cosine(reference[0], reference[1])} batch2MedianMs=${timings[4]}")
        }
        first.recycle(); second.recycle()
    }

    @Test fun encryptsPersistsDeletesAndDetectsTamperingWithoutTouchingUserGallery() {
        val context = instrumentation.targetContext
        val fileName = "test-gallery-${UUID.randomUUID()}.bin"
        val gallery = FaceGallery(context, fileName)
        val vector = FloatArray(128).apply { this[0] = 1f }
        try {
            assertTrue(gallery.read().isEmpty())
            val people = gallery.add("Test Person Private Name", List(5) { vector.copyOf() })
            assertEquals(1, people.size)
            val restored = FaceGallery(context, fileName).read().single()
            assertEquals(people[0].id, restored.id)
            assertEquals("Test Person Private Name", restored.name)
            assertEquals(5, restored.samples.size)
            val encrypted = gallery.file.baseFile.readBytes()
            assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains(restored.name))
            assertThrows(IllegalArgumentException::class.java) { gallery.add(restored.name, List(5) { vector.copyOf() }) }
            val other = gallery.add("Other fixture", List(5) { vector.copyOf() }).last()
            assertThrows(IllegalArgumentException::class.java) { gallery.replace(restored.id, List(3) { vector.copyOf() }) }
            assertEquals(5, gallery.read().first { it.id == restored.id }.samples.size)
            gallery.replace(restored.id, List(15) { vector.copyOf() })
            val upgraded = FaceGallery(context, fileName).read()
            assertEquals(restored.name, upgraded.first { it.id == restored.id }.name)
            assertEquals(15, upgraded.first { it.id == restored.id }.samples.size)
            assertEquals(5, upgraded.first { it.id == other.id }.samples.size)
            assertEquals(1, gallery.delete(restored.id).size)
            assertTrue(gallery.delete(other.id).isEmpty())
            assertTrue(FaceGallery(context, fileName).read().isEmpty())
            val bytes = gallery.file.baseFile.readBytes()
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            gallery.file.baseFile.writeBytes(bytes)
            assertThrows(Exception::class.java) { gallery.read() }
        } finally {
            gallery.file.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("${context.packageName}.face-gallery.$fileName") }
        }
    }

    @Test fun enrollsGuidedRecognizesAfterReopeningAndCancelsWhenAnotherFaceEnters() {
        val context = instrumentation.targetContext
        val fileName = "test-enrollment-${UUID.randomUUID()}.bin"
        val gallery = FaceGallery(context, fileName)
        val bitmap = image("portrait.jpg")
        val raw = landmarks(bitmap)
        val frame = FaceFrame(bitmap.width, bitmap.height, 2).apply {
            faceCount = 1
            raw.copyInto(faces[0].landmarks)
            raw.copyInto(faces[1].landmarks)
        }
        val pixels = ByteBuffer.allocateDirect(bitmap.byteCount)
        bitmap.copyPixelsToBuffer(pixels)
        val prepared = FrameImage(bitmap.width, bitmap.height, 0, false)
        prepared.update(pixels, bitmap.width * 4, 4)
        var engine: FaceRecognition? = null
        var guide = EnrollmentGuideState(EnrollmentPose.FRONT, 1, 0, 0f)
        fun followGuide() {
            // Synthetic landmark-depth changes exercise the live protocol. These are
            // not independent photographs of actual head turns or accuracy evidence.
            val target = if (guide.returning) EnrollmentPose.FRONT else guide.pose
            val yaw = target.targetYaw
            val pitch = target.targetPitch
            val points = frame.faces[0].landmarks
            raw.copyInto(points)
            val aspect = bitmap.height.toFloat() / bitmap.width
            val dx = (points[362 * 3] + points[263 * 3] - points[33 * 3] - points[133 * 3]) / 2f
            val dy = (points[362 * 3 + 1] + points[263 * 3 + 1] - points[33 * 3 + 1] - points[133 * 3 + 1]) / 2f * aspect
            val distance = hypot(dx, dy)
            val depth = distance * tan(Math.toRadians(yaw.toDouble())).toFloat() / 2f
            for (i in intArrayOf(33, 133)) points[i * 3 + 2] = depth
            for (i in intArrayOf(362, 263)) points[i * 3 + 2] = -depth
            val down = ((points[152 * 3] - points[10 * 3]) * -dy +
                (points[152 * 3 + 1] - points[10 * 3 + 1]) * aspect * dx) / distance
            points[10 * 3 + 2] = 0f
            points[152 * 3 + 2] = down * tan(Math.toRadians(pitch.toDouble())).toFloat()
        }
        try {
            var saved = false
            engine = FaceRecognition(context, { if (it.success) saved = true; it.guide?.let { next -> guide = next } }, gallery)
            engine.startEnrollment("Fixture enrollment")
            val deadline = SystemClock.uptimeMillis() + 30000
            while (!saved && SystemClock.uptimeMillis() < deadline) {
                followGuide()
                engine.update(frame, prepared)
                SystemClock.sleep(50)
            }
            assertTrue("All five poses must persist 15 enrollment samples", saved)
            assertEquals(15, gallery.read().single().samples.size)
            engine.close()
            engine = FaceRecognition(context, {}, gallery)
            val recognitionDeadline = SystemClock.uptimeMillis() + 6000
            while (frame.faces[0].name != "Fixture enrollment" && SystemClock.uptimeMillis() < recognitionDeadline) {
                engine.update(frame, prepared)
                SystemClock.sleep(50)
            }
            assertEquals("Fixture enrollment", frame.faces[0].name)
            var sampleCount = 0
            var cancelled = false
            engine.close()
            guide = EnrollmentGuideState(EnrollmentPose.FRONT, 1, 0, 0f)
            engine = FaceRecognition(context, {
                sampleCount = maxOf(sampleCount, it.samples)
                if (it.finished && !it.success) cancelled = true
                it.guide?.let { next -> guide = next }
            }, gallery)
            engine.startEnrollment("Must not be saved")
            val firstSampleDeadline = SystemClock.uptimeMillis() + 6000
            while (sampleCount == 0 && SystemClock.uptimeMillis() < firstSampleDeadline) {
                followGuide()
                engine.update(frame, prepared)
                SystemClock.sleep(50)
            }
            assertTrue(sampleCount > 0)
            frame.faceCount = 2
            engine.update(frame, prepared)
            assertTrue("An extra face must cancel an in-progress enrollment", cancelled)
            assertEquals(1, gallery.read().size)
        } finally {
            engine?.close()
            prepared.close(); bitmap.recycle()
            gallery.file.delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("${context.packageName}.face-gallery.$fileName") }
        }
    }
}
