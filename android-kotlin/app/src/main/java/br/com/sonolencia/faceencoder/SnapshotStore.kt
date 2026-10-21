package br.com.sonolencia.faceencoder

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.JsonWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SnapshotStore {
    fun save(context: Context, snapshot: FaceSnapshot): String {
        val prefix = if (snapshot.hands != null) "hands" else "face_encoder"
        val name = "${prefix}_${System.currentTimeMillis()}.zip"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/FaceEncoder")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            try {
                checkNotNull(resolver.openOutputStream(uri)).use { writeArchive(it, snapshot) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
            return "Downloads/FaceEncoder/$name"
        }
        val directory = java.io.File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "captures")
        check(directory.isDirectory || directory.mkdirs())
        val file = java.io.File(directory, name)
        try {
            file.outputStream().use { writeArchive(it, snapshot) }
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        return file.absolutePath
    }

    private fun writeArchive(output: OutputStream, snapshot: FaceSnapshot) {
        drawOverlay(snapshot)
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("frame.png"))
            check(snapshot.bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("landmarks.json"))
            val writer = JsonWriter(OutputStreamWriter(zip, Charsets.UTF_8))
            writer.beginObject()
            writer.name("width").value(snapshot.bitmap.width)
            writer.name("height").value(snapshot.bitmap.height)
            writer.name("timestamp_uptime_ms").value(snapshot.timestampMs)
            writer.name("mirrored").value(snapshot.mirrored)
            writer.name("mode").value(if (snapshot.hands != null) "hands" else "faces")
            writer.name("face_count").value(snapshot.faces.size)
            writer.name("selected_face_index").value(snapshot.selectedFace)
            writer.name("selected_point").value(snapshot.selectedPoint)
            writer.name("faces").beginArray()
            snapshot.faces.forEachIndexed { index, face ->
                writer.beginObject()
                writer.name("index").value(index)
                writeFace(writer, face)
                writer.endObject()
            }
            writer.endArray()
            // Preserve the original single-face keys for existing data readers.
            snapshot.faces.firstOrNull()?.let { writeFace(writer, it) }
            snapshot.hands?.let { writeHands(writer, it) }
            writer.endObject()
            writer.flush() // Closing the writer would close the ZIP before its final entry.
            zip.closeEntry()
        }
    }

    private fun writeFace(writer: JsonWriter, face: FaceSnapshotData) {
        writer.name("landmarks").beginArray()
        for (index in face.landmarks.indices step 3) {
            writer.beginArray()
            repeat(3) { axis -> writer.value(face.landmarks[index + axis].toDouble()) }
            writer.endArray()
        }
        writer.endArray()
        writer.name("encoding").beginArray()
        face.encoding.forEach { writer.value(it.toDouble()) }
        writer.endArray()
    }

    private fun writeHands(writer: JsonWriter, snapshot: HandSnapshot) {
        val hands = snapshot.mapping
        writer.name("coordinate_space").value("upright_normalized_image_xyz_relative_depth")
        writer.name("selected_hand_track_id").value(snapshot.selectedTrackId)
        writer.name("selected_hand_point").value(snapshot.selectedPoint)
        writer.name("hand_count").value(hands.handCount)
        writer.name("hands_timestamp_uptime_ms").value(hands.handTimestampMs)
        writer.name("hands").beginArray()
        repeat(hands.handCount) { i ->
            val hand = hands.hands[i]
            writer.beginObject()
            writer.name("track_id").value(hand.trackId)
            writer.name("pinch_ratio").value(hand.pinchRatio.toDouble())
            writer.name("pinching").value(hand.pinching)
            writePoints(writer, hand.landmarks)
            writer.endObject()
        }
        writer.endArray()
    }

    private fun writePoints(writer: JsonWriter, points: FloatArray) {
        writer.name("landmarks").beginArray()
        for (i in points.indices step 3) {
            writer.beginArray()
            repeat(3) { writer.value(points[i + it].toDouble()) }
            writer.endArray()
        }
        writer.endArray()
    }

    private fun drawOverlay(snapshot: FaceSnapshot) {
        val canvas = Canvas(snapshot.bitmap)
        snapshot.hands?.let { hands ->
            HandOverlayRenderer(1f).apply {
                selectedTrackId = hands.selectedTrackId
                selectedPoint = hands.selectedPoint
            }.draw(canvas, hands.mapping, canvas.width, canvas.height, false)
            return
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(126, 243, 180)
            strokeWidth = 2.5f
            strokeCap = Paint.Cap.ROUND
        }
        val xy = FloatArray(478 * 2)
        for ((faceIndex, face) in snapshot.faces.withIndex()) {
            var minX = canvas.width.toFloat()
            var minY = canvas.height.toFloat()
            for (index in xy.indices step 2) {
                xy[index] = face.landmarks[index / 2 * 3] * canvas.width
                xy[index + 1] = face.landmarks[index / 2 * 3 + 1] * canvas.height
                minX = minOf(minX, xy[index])
                minY = minOf(minY, xy[index + 1])
            }
            paint.color = FaceColors.at(faceIndex)
            paint.style = Paint.Style.FILL
            canvas.drawPoints(xy, paint)
            paint.textSize = 14f
            canvas.drawText("Rosto ${faceIndex + 1}", minX.coerceAtLeast(4f), (minY - 8f).coerceAtLeast(65f), paint)
            if (faceIndex == snapshot.selectedFace) {
                paint.color = Color.rgb(255, 213, 112)
                paint.style = Paint.Style.STROKE
                canvas.drawCircle(xy[snapshot.selectedPoint * 2], xy[snapshot.selectedPoint * 2 + 1], 6f, paint)
            }
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xDD101714.toInt()
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), 50f, paint)
        paint.color = Color.WHITE
        paint.textSize = 12f
        canvas.drawText("${snapshot.faces.size} rosto(s) | 478 pontos por rosto", 8f, 19f, paint)
        val offset = snapshot.selectedPoint * 3
        val raw = snapshot.faces[snapshot.selectedFace].landmarks
        canvas.drawText(String.format(Locale.US, "R%d #%d x %.3f y %.3f z %+.3f", snapshot.selectedFace + 1, snapshot.selectedPoint,
            raw[offset], raw[offset + 1], raw[offset + 2]), 8f, 39f, paint)
    }
}
