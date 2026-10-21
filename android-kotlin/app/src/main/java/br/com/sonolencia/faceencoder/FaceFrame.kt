package br.com.sonolencia.faceencoder

import android.graphics.Bitmap

/** Mutable buffers owned exclusively by the camera executor. */
class EncodedFace {
    val landmarks = FloatArray(478 * 3)
    val encoding = FloatArray(478 * 3)
    var trackId = 0L
    var name: String? = null
    var label = "Desconhecido"
}

class FaceFrame(val width: Int, val height: Int, capacity: Int) {
    val faces = Array(capacity) { EncodedFace() }
    val hands = HandMapping()
    var handsMode = false
    var faceCount = 0
    val pointCount: Int get() = faceCount * 478 + if (handsMode) hands.pointCount else 0
    var timestampMs = 0L
    var inferenceMs = 0f
    var fps = 0f
    var backend = "CPU"
    var mirrored = true
}

/** Copies are made only on explicit save requests, never for the live overlay. */
data class FaceSnapshot(
    val bitmap: Bitmap,
    val faces: List<FaceSnapshotData>,
    val timestampMs: Long,
    val selectedFace: Int,
    val selectedPoint: Int,
    val mirrored: Boolean,
    val hands: HandSnapshot? = null,
)

data class FaceSnapshotData(val landmarks: FloatArray, val encoding: FloatArray)
