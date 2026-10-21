package br.com.sonolencia.faceencoder

import kotlin.math.sqrt

data class EnrolledPerson(val id: String, val name: String, val samples: List<FloatArray>)
data class FaceMatch(val person: EnrolledPerson, val similarity: Float)

object FaceMatcher {
    // Conservative starting point for this prototype, not a calibrated probability.
    const val MIN_SIMILARITY = 0.70f
    const val MIN_MARGIN = 0.10f

    fun normalize(vector: FloatArray): FloatArray {
        require(vector.size == 128 && vector.all { it.isFinite() })
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        require(norm > 0.00001f)
        return FloatArray(vector.size) { vector[it] / norm }
    }

    fun cosine(left: FloatArray, right: FloatArray): Float {
        require(left.size == right.size)
        var value = 0f
        for (i in left.indices) value += left[i] * right[i]
        return value.coerceIn(-1f, 1f)
    }

    fun match(vector: FloatArray, people: List<EnrolledPerson>): FaceMatch? {
        var best: EnrolledPerson? = null
        var first = -1f
        var second = -1f
        for (person in people) {
            var highest = -1f
            var next = -1f
            for (sample in person.samples) {
                val score = cosine(vector, sample)
                if (score > highest) { next = highest; highest = score }
                else if (score > next) next = score
            }
            val score = if (person.samples.size > 1) (highest + next) / 2f else highest
            if (score > first) { second = first; first = score; best = person }
            else if (score > second) second = score
        }
        return best?.takeIf { first >= MIN_SIMILARITY && first - second >= MIN_MARGIN }
            ?.let { FaceMatch(it, first) }
    }
}
