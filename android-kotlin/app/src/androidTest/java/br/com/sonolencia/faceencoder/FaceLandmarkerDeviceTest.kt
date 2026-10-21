package br.com.sonolencia.faceencoder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
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
import org.json.JSONObject
import java.util.zip.ZipInputStream

@RunWith(AndroidJUnit4::class)
class FaceLandmarkerDeviceTest {
    @Test fun measuresCpuAndGpuOnTheSameFace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val original = instrumentation.context.assets.open("portrait.jpg").use { BitmapFactory.decodeStream(it) }
        val bitmap = Bitmap.createScaledBitmap(original, 480, 600, true)
        val image = BitmapImageBuilder(bitmap).build()
        for (delegate in listOf(Delegate.CPU, Delegate.GPU)) {
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(delegate).build())
                .setRunningMode(RunningMode.VIDEO).setNumFaces(1).build()
            try {
                FaceLandmarker.createFromOptions(instrumentation.targetContext, options).use { detector ->
                    val timings = FloatArray(20)
                    repeat(25) { index ->
                        val start = SystemClock.elapsedRealtimeNanos()
                        val result = detector.detectForVideo(image, (index + 1) * 40L)
                        assertEquals(478, result.faceLandmarks().single().size)
                        if (index >= 5) timings[index - 5] = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000f
                    }
                    timings.sort()
                    Log.i("FaceEncoderTest", "$delegate inference median=${timings[10]}ms p95=${timings[18]}ms")
                }
            } catch (error: RuntimeException) {
                if (delegate == Delegate.CPU) throw error
                Log.i("FaceEncoderTest", "GPU unavailable on this device: ${error.message}")
            }
        }
        image.close()
        if (!original.isRecycled) original.recycle()
    }

    @Test fun detects478PointsAndReadsUpdatedReusableBitmap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.context.assets.open("portrait.jpg").use {
            BitmapFactory.decodeStream(it).copy(Bitmap.Config.ARGB_8888, true)
        }
        val image = BitmapImageBuilder(bitmap).build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(Delegate.CPU).build())
            .setRunningMode(RunningMode.VIDEO).setNumFaces(1).build()
        FaceLandmarker.createFromOptions(instrumentation.targetContext, options).use { detector ->
            val face = detector.detectForVideo(image, 1).faceLandmarks().single()
            assertEquals(478, face.size)
            val raw = FloatArray(1434)
            face.forEachIndexed { index, point ->
                raw[index * 3] = point.x(); raw[index * 3 + 1] = point.y(); raw[index * 3 + 2] = point.z()
            }
            val encoding = FaceEncoder.encode(raw, bitmap.width, bitmap.height)
            assertEquals(1434, encoding.size)
            assertTrue(encoding.all { it.isFinite() })
            assertEquals(0f, encoding[3], 0f)
            if (Build.VERSION.SDK_INT >= 29) {
                val context = instrumentation.targetContext
                val snapshot = FaceSnapshot(bitmap.copy(Bitmap.Config.ARGB_8888, true),
                    listOf(FaceSnapshotData(raw, encoding)), 1, 0, 1, false)
                val path = try { SnapshotStore.save(context, snapshot) } finally { snapshot.bitmap.recycle() }
                val name = path.substringAfterLast('/')
                val resolver = context.contentResolver
                val uri = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Downloads._ID), "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                }
                try {
                    ZipInputStream(resolver.openInputStream(uri)).use { zip ->
                        assertEquals("frame.png", zip.nextEntry.name)
                        val pngBytes = zip.readBytes()
                        val png = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                        assertNotNull(png)
                        assertEquals(bitmap.width, png.width)
                        assertEquals(bitmap.height, png.height)
                        png.recycle()
                        zip.closeEntry()
                        assertEquals("landmarks.json", zip.nextEntry.name)
                        val data = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        assertEquals(478, data.getJSONArray("landmarks").length())
                        assertEquals(1434, data.getJSONArray("encoding").length())
                        assertEquals(bitmap.width, data.getInt("width"))
                        assertEquals(0.0, data.getJSONArray("encoding").getDouble(3), 0.0)
                    }
                    Log.i("FaceEncoderTest", "ZIP export: PNG + 478 XYZ + 1434 encoding values OK")
                } finally { resolver.delete(uri, null, null) }
            }
            Canvas(bitmap).drawColor(Color.BLACK)
            var lastCount = 1
            repeat(10) { lastCount = detector.detectForVideo(image, (it + 2) * 34L).faceLandmarks().size }
            assertEquals("A reused MPImage must read the current bitmap pixels", 0, lastCount)
            Log.i("FaceEncoderTest", "CPU: 478 landmarks, 1434 float32, reusable bitmap OK")
        }
        image.close()
    }
}
