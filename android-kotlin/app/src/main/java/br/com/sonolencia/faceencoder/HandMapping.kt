package br.com.sonolencia.faceencoder

import kotlin.math.abs
import kotlin.math.hypot

class MappedHand {
    val landmarks = FloatArray(21 * 3)
    var trackId = 0L
    var pinchRatio = 1f
    var pinching = false

    fun copyInto(target: MappedHand) {
        landmarks.copyInto(target.landmarks)
        target.trackId = trackId
        target.pinchRatio = pinchRatio
        target.pinching = pinching
    }
}

/** Camera-thread buffers; the overlay keeps its own copy. XYZ is normalized image geometry. */
class HandMapping {
    val hands = Array(2) { MappedHand() }
    var handCount = 0
    var handTimestampMs = 0L
    var handsFps = 0f
    val pointCount: Int get() = handCount * 21

    fun copyInto(target: HandMapping) {
        repeat(handCount) { hands[it].copyInto(target.hands[it]) }
        target.handCount = handCount
        target.handTimestampMs = handTimestampMs
        target.handsFps = handsFps
    }
}

data class HandSnapshot(val mapping: HandMapping, val selectedTrackId: Long, val selectedPoint: Int)

object HandConnections {
    val hand = intArrayOf(0,1, 1,2, 2,3, 3,4, 0,5, 5,6, 6,7, 7,8,
        5,9, 9,10, 10,11, 11,12, 9,13, 13,14, 14,15, 15,16,
        13,17, 0,17, 17,18, 18,19, 19,20)
}

/** Short-lived spatial IDs, never person identities. Ambiguous crossings start fresh IDs. */
class HandTracker {
    private data class Track(val id: Long, val x: Float, val y: Float, val span: Float,
        val time: Long, val pinching: Boolean)
    private var previous = emptyList<Track>()
    private var nextId = 1L

    fun update(mapping: HandMapping, aspect: Float, now: Long) {
        val old = previous.filter { now - it.time < 250 }
        val centers = List(mapping.handCount) { i ->
            val p = mapping.hands[i].landmarks
            floatArrayOf((p[0] + p[15] + p[27] + p[51]) / 4f * aspect,
                (p[1] + p[16] + p[28] + p[52]) / 4f,
                hypot((p[15] - p[51]) * aspect, p[16] - p[52]).coerceAtLeast(0.02f))
        }
        fun distance(i: Int, j: Int) = hypot(centers[i][0] - old[j].x, centers[i][1] - old[j].y)
        val assignments = IntArray(mapping.handCount) { -1 }
        if (mapping.handCount == 2 && old.size == 2) {
            val direct = distance(0, 0) + distance(1, 1)
            val swapped = distance(0, 1) + distance(1, 0)
            if (abs(direct - swapped) > 0.035f) {
                assignments[0] = if (direct < swapped) 0 else 1
                assignments[1] = 1 - assignments[0]
            }
        } else if (mapping.handCount == 1 && old.isNotEmpty()) {
            val sorted = old.indices.sortedBy { distance(0, it) }
            if (sorted.size == 1 || distance(0, sorted[1]) - distance(0, sorted[0]) > 0.035f)
                assignments[0] = sorted[0]
        } else if (mapping.handCount == 2 && old.size == 1) {
            if (abs(distance(0, 0) - distance(1, 0)) > 0.035f)
                assignments[if (distance(0, 0) < distance(1, 0)) 0 else 1] = 0
        }
        previous = List(mapping.handCount) { i ->
            val hand = mapping.hands[i]
            val center = centers[i]
            val candidate = assignments[i].takeIf { it >= 0 }?.let { index ->
                old[index].takeIf { distance(i, index) < maxOf(0.1f, minOf(0.35f, maxOf(center[2], it.span) * 2f)) }
            }
            hand.trackId = candidate?.id ?: nextId++
            val p = hand.landmarks
            hand.pinchRatio = hypot((p[12] - p[24]) * aspect, p[13] - p[25]) / center[2]
            hand.pinching = hand.pinchRatio < if (candidate?.pinching == true) 0.45f else 0.30f
            Track(hand.trackId, center[0], center[1], center[2], now, hand.pinching)
        }
    }
}
