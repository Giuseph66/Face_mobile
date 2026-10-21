package br.com.sonolencia.faceencoder

import kotlin.math.hypot

/** Five-point similarity alignment. Only the 112x112 recognition crops are sampled. */
object FaceCropper {
    private val reference = floatArrayOf(38.2946f, 51.6963f, 73.5318f, 51.5014f,
        56.0252f, 71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f)
    private val pairs = intArrayOf(33, 133, 362, 263, 1, 1, 61, 61, 291, 291)

    fun crop(image: FrameImage, points: FloatArray, output: FloatArray): String? {
        val src = FloatArray(10)
        repeat(5) { i ->
            var x = (points[pairs[i * 2] * 3] + points[pairs[i * 2 + 1] * 3]) / 2f
            if (image.mirrored) x = 1f - x
            src[i * 2] = x * image.width
            src[i * 2 + 1] = (points[pairs[i * 2] * 3 + 1] + points[pairs[i * 2 + 1] * 3 + 1]) / 2f * image.height
        }
        val eyeDistance = hypot(src[2] - src[0], src[3] - src[1])
        if (eyeDistance < 28f) return "Aproxime o rosto da câmera."
        val noseLeft = hypot(src[4] - src[0], src[5] - src[1])
        val noseRight = hypot(src[4] - src[2], src[5] - src[3])
        if (minOf(noseLeft, noseRight) < maxOf(noseLeft, noseRight) * 0.45f) return "Vire o rosto um pouco para a câmera."
        val rx = (0..4).sumOf { reference[it * 2].toDouble() }.toFloat() / 5f
        val ry = (0..4).sumOf { reference[it * 2 + 1].toDouble() }.toFloat() / 5f
        val sx = (0..4).sumOf { src[it * 2].toDouble() }.toFloat() / 5f
        val sy = (0..4).sumOf { src[it * 2 + 1].toDouble() }.toFloat() / 5f
        var denominator = 0f; var a = 0f; var b = 0f
        repeat(5) { i ->
            val u = reference[i * 2] - rx; val v = reference[i * 2 + 1] - ry
            val x = src[i * 2] - sx; val y = src[i * 2 + 1] - sy
            denominator += u * u + v * v
            a += u * x + v * y; b += u * y - v * x
        }
        a /= denominator; b /= denominator
        val tx = sx - a * rx + b * ry
        val ty = sy - b * rx - a * ry
        var sum = 0f; var sum2 = 0f; var gradient = 0f
        repeat(112) { y -> repeat(112) { x ->
            val i = y * 112 + x
            image.readUprightRgb(a * x - b * y + tx, b * x + a * y + ty, output, i)
            val value = (output[i] + output[i + 12544] + output[i + 25088]) / 3f
            sum += value; sum2 += value * value
            if (x > 0) gradient += kotlin.math.abs(output[i] - output[i - 1])
        } }
        val mean = sum / 12544f
        if (mean < 0.10f || mean > 0.94f) return "Procure uma iluminação mais uniforme."
        if (sum2 / 12544f - mean * mean < 0.002f || gradient / 12432f < 0.012f) return "Mantenha o rosto parado e confira o foco."
        return null
    }
}
