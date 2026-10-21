package br.com.sonolencia.faceencoder

import kotlin.math.hypot

data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun iou(other: FaceBox): Float {
        val area = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f) *
            (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        return area / (width * height + other.width * other.height - area).coerceAtLeast(0.00001f)
    }
    companion object {
        fun from(points: FloatArray): FaceBox {
            var left = 1f; var top = 1f; var right = 0f; var bottom = 0f
            for (i in points.indices step 3) {
                left = minOf(left, points[i]); right = maxOf(right, points[i])
                top = minOf(top, points[i + 1]); bottom = maxOf(bottom, points[i + 1])
            }
            return FaceBox(left, top, right, bottom)
        }
    }
}

/** Spatial tracking never serves as identity evidence. Ambiguous associations discard names. */
class FaceTracker {
    class Track(val id: Long, var box: FaceBox, var seenAt: Long) {
        var revision = 0
        var ambiguous = false
        var checkedAt = 0L
        var person: EnrolledPerson? = null
        private var candidateId: String? = null
        private var votes = 0
        fun reset() { revision++; person = null; candidateId = null; votes = 0 }
        fun needsRecognition(): Boolean = person == null
        fun accept(match: FaceMatch?) {
            if (match == null) { reset(); return }
            if (candidateId == match.person.id) votes++ else { candidateId = match.person.id; votes = 1; person = null }
            if (votes >= 2) person = match.person
        }
        fun name(): String? = person?.name?.takeIf { !ambiguous }
    }
    var tracks: List<Track> = emptyList()
        private set
    private var nextId = 1L

    fun update(boxes: List<FaceBox>, now: Long): List<Track> {
        val used = mutableSetOf<Long>()
        val previous = tracks.filter { now - it.seenAt < 400 }
        tracks = boxes.mapIndexed { index, box ->
            val candidates = previous.filter { it.id !in used && it.box.iou(box) > 0.25f }
                .sortedByDescending { it.box.iou(box) }
            val track = candidates.firstOrNull() ?: Track(nextId++, box, now)
            used.add(track.id)
            val overlaps = boxes.indices.any { it != index && box.iou(boxes[it]) > 0.15f }
            val uncertain = candidates.size > 1 && candidates[0].box.iou(box) - candidates[1].box.iou(box) < 0.20f
            val moved = hypot(box.left - track.box.left, box.top - track.box.top) > maxOf(box.width, box.height) * 0.25f
            if (overlaps || uncertain || moved) track.reset()
            track.ambiguous = overlaps || uncertain
            track.box = box
            track.seenAt = now
            track
        }
        return tracks
    }

    @Suppress("UNUSED_PARAMETER")
    fun accept(id: Long, revision: Int, box: FaceBox, match: FaceMatch?, now: Long) {
        tracks.find { it.id == id && it.revision == revision && !it.ambiguous && it.box.iou(box) > 0.5f }
            ?.accept(match)
    }

}
