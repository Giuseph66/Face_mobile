package br.com.sonolencia.faceencoder

import org.junit.Assert.*
import org.junit.Test

class FaceEncoderTest {
    private fun points() = FloatArray(478 * 3) { index -> (index % 67) / 100f }.apply {
        this[33 * 3] = 0.3f; this[33 * 3 + 1] = 0.4f
        this[263 * 3] = 0.7f; this[263 * 3 + 1] = 0.4f
    }

    @Test fun normalizesTranslationAndScaleWithoutMutatingInput() {
        val input = points()
        val original = input.copyOf()
        val encoded = FaceEncoder.encode(input, 640, 480)
        assertEquals(1434, encoded.size)
        assertArrayEquals(original, input, 0f)
        assertEquals(0f, encoded[3], 0f)
        assertEquals(0f, encoded[4], 0f)
        assertEquals(0f, encoded[5], 0f)
        val transformed = FloatArray(input.size) { input[it] * 1.7f + floatArrayOf(0.1f, -0.2f, 0.05f)[it % 3] }
        assertArrayEquals(encoded, FaceEncoder.encode(transformed, 640, 480), 1e-5f)
    }

    @Test fun correctsAspectRatioAndReusesOutputBuffer() {
        val input = points()
        val expected = FaceEncoder.encode(input, 640, 480)
        val changedCanvas = FloatArray(input.size) { input[it] * if (it % 3 == 1) 480f / 800f else 640f / 800f }
        val output = FloatArray(1434)
        assertSame(output, FaceEncoder.encode(changedCanvas, 800, 800, output))
        assertArrayEquals(expected, output, 1e-5f)
    }

    @Test fun rejectsInvalidCoordinatesAndDegenerateEyes() {
        for (input in listOf(FloatArray(1434), FloatArray(1434) { Float.NaN }, FloatArray(20))) {
            assertThrows(IllegalArgumentException::class.java) { FaceEncoder.encode(input, 640, 480) }
        }
    }
}
