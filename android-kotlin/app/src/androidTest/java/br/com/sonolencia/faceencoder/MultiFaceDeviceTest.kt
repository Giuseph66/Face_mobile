package br.com.sonolencia.faceencoder

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.ZipInputStream
import java.nio.ByteBuffer

@RunWith(AndroidJUnit4::class)
class MultiFaceDeviceTest {
    @Test fun honorsAllFourLimitsAndExportsEachFace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap = instrumentation.context.assets.open("four_faces.jpg").use { BitmapFactory.decodeStream(it) }
        val sensor = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(-90f) }, false)
        val buffer = ByteBuffer.allocateDirect(sensor.byteCount)
        sensor.copyPixelsToBuffer(buffer)
        val prepared = FrameImage(sensor.width, sensor.height, 90, true)
        prepared.update(buffer, sensor.width * 4, 4)
        sensor.recycle()
        var exportedFaces = emptyList<FaceSnapshotData>()
        for (limit in intArrayOf(1, 2, 3, 8)) {
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task").setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.VIDEO).setNumFaces(limit).build()
            FaceLandmarker.createFromOptions(context, options).use { detector ->
                val faces = detector.detectForVideo(prepared.image, prepared.options, 1).faceLandmarks()
                assertEquals("Maximum $limit", minOf(limit, 4), faces.size)
                if (limit == 8) exportedFaces = faces.map { points ->
                    assertEquals(478, points.size)
                    val raw = FloatArray(1434)
                    points.forEachIndexed { index, point ->
                        prepared.writePoint(raw, index * 3, point.x(), point.y(), point.z())
                    }
                    FaceSnapshotData(raw, FaceEncoder.encode(raw, bitmap.width, bitmap.height))
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val snapshot = FaceSnapshot(prepared.snapshot(), exportedFaces, 123, 1, 33, true)
            val name = try { SnapshotStore.save(context, snapshot).substringAfterLast('/') }
                finally { snapshot.bitmap.recycle() }
            val resolver = context.contentResolver
            val uri = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name), null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cursor.getLong(0))
            }
            try {
                ZipInputStream(resolver.openInputStream(uri)).use { zip ->
                    assertEquals("frame.png", zip.nextEntry.name)
                    val pngBytes = zip.readBytes()
                    val saved = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                    assertEquals(bitmap.width, saved.width)
                    saved.recycle()
                    zip.closeEntry()
                    assertEquals("landmarks.json", zip.nextEntry.name)
                    val data = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    assertEquals(4, data.getInt("face_count"))
                    assertTrue(data.getBoolean("mirrored"))
                    assertEquals(1, data.getInt("selected_face_index"))
                    assertEquals(4, data.getJSONArray("faces").length())
                    repeat(4) { index ->
                        val face = data.getJSONArray("faces").getJSONObject(index)
                        assertEquals(478, face.getJSONArray("landmarks").length())
                        assertEquals(1434, face.getJSONArray("encoding").length())
                        assertEquals(0.0, face.getJSONArray("encoding").getDouble(3), 0.0)
                    }
                }
            } finally { resolver.delete(uri, null, null) }
        }
        prepared.close()
        bitmap.recycle()
    }
}
