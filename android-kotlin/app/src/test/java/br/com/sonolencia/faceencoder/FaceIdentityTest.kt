package br.com.sonolencia.faceencoder

import org.junit.Assert.*
import org.junit.Test

class FaceIdentityTest {
    private val a = FloatArray(128).apply { this[0] = 1f }
    private val b = FloatArray(128).apply { this[1] = 1f }
    private fun person(id: String, vector: FloatArray) = EnrolledPerson(id, id, List(5) { vector.copyOf() })
    private val boxA = FaceBox(0.05f, 0.1f, 0.35f, 0.5f)
    private val boxB = FaceBox(0.65f, 0.1f, 0.95f, 0.5f)

    @Test fun rejectsUnknownAndAmbiguousMatches() {
        val alice = person("A", a)
        assertEquals("A", FaceMatcher.match(a, listOf(alice))?.person?.id)
        assertNull(FaceMatcher.match(b, listOf(alice)))
        assertNull(FaceMatcher.match(a, listOf(alice, person("duplicate", a))))
        assertNull(FaceMatcher.match(a, emptyList()))
        assertEquals(1f, FaceMatcher.normalize(FloatArray(128).apply { this[0] = 10f })[0], 0f)
        assertThrows(IllegalArgumentException::class.java) { FaceMatcher.normalize(FloatArray(128)) }
    }

    @Test fun identitiesFollowTracksAndPersistUntilTrackLoss() {
        val tracker = FaceTracker()
        val tracks = tracker.update(listOf(boxA, boxB), 100)
        val idA = tracks[0].id
        val idB = tracks[1].id
        val match = FaceMatch(person("A", a), 0.95f)
        tracker.accept(idA, 0, boxA, match, 100)
        assertNull(tracks[0].name())
        tracker.accept(idA, 0, boxA, match, 200)
        val reordered = tracker.update(listOf(boxB, boxA), 250)
        assertEquals(idB, reordered[0].id)
        assertEquals(idA, reordered[1].id)
        assertEquals("A", reordered[1].name())
        assertFalse(reordered[1].needsRecognition())
        var time = 250L
        repeat(5) {
            time += 300
            val continued = tracker.update(listOf(boxB, boxA), time)[1]
            assertEquals("A", continued.name())
            assertFalse(continued.needsRecognition())
        }
        tracker.update(emptyList(), time + 50)
        val replacement = tracker.update(listOf(boxA), time + 60).single()
        assertNotEquals(idA, replacement.id)
        assertNull(replacement.name())
        assertTrue(replacement.needsRecognition())
    }

    @Test fun crossingDropsNamesAndRejectsDelayedResults() {
        val tracker = FaceTracker()
        val track = tracker.update(listOf(boxA), 100).single()
        val match = FaceMatch(person("A", a), 0.95f)
        tracker.accept(track.id, 0, boxA, match, 100)
        tracker.accept(track.id, 0, boxA, match, 110)
        assertEquals("A", track.name())
        tracker.update(listOf(boxA, FaceBox(0.1f, 0.1f, 0.4f, 0.5f)), 120)
        assertTrue(track.ambiguous)
        assertNull(track.name())
        tracker.accept(track.id, 0, boxA, match, 130)
        assertNull(track.name())
        tracker.update(listOf(boxA), 150)
        tracker.accept(track.id, 0, boxA, match, 160)
        assertNull(track.name())
    }
}
