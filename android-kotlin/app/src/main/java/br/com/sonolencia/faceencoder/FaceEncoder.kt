package br.com.sonolencia.faceencoder

import kotlin.math.sqrt

/** Same XYZ convention and normalization as the desktop Python encoder. */
object FaceEncoder {
    fun encode(
        landmarks: FloatArray,
        width: Int,
        height: Int,
        output: FloatArray = FloatArray(landmarks.size),
    ): FloatArray {
        require(landmarks.size >= 264 * 3 && landmarks.size % 3 == 0)
        require(output.size == landmarks.size && output !== landmarks)
        require(width > 0 && height > 0 && landmarks.all { it.isFinite() })
        val aspect = height.toFloat() / width
        val dx = landmarks[33 * 3] - landmarks[263 * 3]
        val dy = (landmarks[33 * 3 + 1] - landmarks[263 * 3 + 1]) * aspect
        val scale = sqrt(dx * dx + dy * dy)
        require(scale >= 1e-6f) { "Distância entre os olhos insuficiente." }
        val noseX = landmarks[3]
        val noseY = landmarks[4] * aspect
        val noseZ = landmarks[5]
        for (index in landmarks.indices step 3) {
            output[index] = (landmarks[index] - noseX) / scale
            output[index + 1] = (landmarks[index + 1] * aspect - noseY) / scale
            output[index + 2] = (landmarks[index + 2] - noseZ) / scale
        }
        return output
    }
}
