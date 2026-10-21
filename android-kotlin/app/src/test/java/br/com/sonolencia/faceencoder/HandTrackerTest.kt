package br.com.sonolencia.faceencoder

import org.junit.Assert.*
import org.junit.Test

class HandTrackerTest {
    private fun frame(vararg centers: Float) = HandMapping().apply {
        handCount = centers.size
        centers.forEachIndexed { index, center ->
            val p = hands[index].landmarks
            repeat(21) { p[it * 3] = center; p[it * 3 + 1] = 0.4f }
            p[1] = 0.55f
            p[15] = center - 0.05f; p[16] = 0.45f
            p[28] = 0.42f
            p[51] = center + 0.05f; p[52] = 0.45f
            p[12] = center - 0.01f; p[13] = 0.35f
            p[24] = center + 0.01f; p[25] = 0.35f
        }
    }

    @Test fun followsHandsAcrossDetectionOrderAndStartsNewIdsAfterLoss() {
        val tracker = HandTracker()
        val first = frame(0.2f, 0.8f)
        tracker.update(first, 0.75f, 100)
        val leftId = first.hands[0].trackId
        val rightId = first.hands[1].trackId
        val reversed = frame(0.79f, 0.21f)
        tracker.update(reversed, 0.75f, 133)
        assertEquals(rightId, reversed.hands[0].trackId)
        assertEquals(leftId, reversed.hands[1].trackId)
        tracker.update(frame(), 0.75f, 166)
        val returned = frame(0.2f)
        tracker.update(returned, 0.75f, 199)
        assertNotEquals(leftId, returned.hands[0].trackId)
        val delayed = frame(0.2f)
        tracker.update(delayed, 0.75f, 500)
        assertNotEquals(returned.hands[0].trackId, delayed.hands[0].trackId)
    }

    @Test fun ambiguousCrossingDiscardsSpatialIds() {
        val tracker = HandTracker()
        val first = frame(0.3f, 0.7f)
        tracker.update(first, 0.75f, 100)
        val ids = first.hands.map { it.trackId }
        val crossed = frame(0.5f, 0.5f)
        tracker.update(crossed, 0.75f, 133)
        assertFalse(crossed.hands[0].trackId in ids)
        assertFalse(crossed.hands[1].trackId in ids)
        assertNotEquals(crossed.hands[0].trackId, crossed.hands[1].trackId)
    }

    @Test fun pinchHysteresisAvoidsFlickeringBetweenThresholds() {
        val tracker = HandTracker()
        fun sample(ratio: Float, timestamp: Long): MappedHand {
            val data = frame(0.5f)
            data.hands[0].landmarks[12] = 0.5f - ratio * 0.05f
            data.hands[0].landmarks[24] = 0.5f + ratio * 0.05f
            tracker.update(data, 0.75f, timestamp)
            return data.hands[0]
        }
        val closed = sample(0.2f, 100)
        assertTrue(closed.pinching)
        val near = sample(0.4f, 133)
        assertEquals(closed.trackId, near.trackId)
        assertTrue(near.pinching)
        assertFalse(sample(0.5f, 166).pinching)
        assertFalse(sample(0.4f, 199).pinching)
    }
}
