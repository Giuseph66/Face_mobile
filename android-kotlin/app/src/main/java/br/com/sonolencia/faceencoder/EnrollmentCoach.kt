package br.com.sonolencia.faceencoder

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Relative pose estimates from landmarks, not calibrated measurements in physical space. */
data class HeadPose(val yaw: Float, val pitch: Float, val centered: Boolean = true) {
    companion object {
        fun measure(points: FloatArray, width: Int, height: Int, mirrored: Boolean): HeadPose {
            val aspect = height.toFloat() / width
            fun x(i: Int) = if (mirrored) 1f - points[i * 3] else points[i * 3]
            fun y(i: Int) = points[i * 3 + 1] * aspect
            fun z(i: Int) = points[i * 3 + 2]
            val leftX = (x(33) + x(133)) / 2f
            val leftY = (y(33) + y(133)) / 2f
            val rightX = (x(362) + x(263)) / 2f
            val rightY = (y(362) + y(263)) / 2f
            val dx = rightX - leftX; val dy = rightY - leftY
            val eyeDistance = hypot(dx, dy).coerceAtLeast(0.0001f)
            val eyeDepth = (z(362) + z(263) - z(33) - z(133)) / 2f
            val yaw = Math.toDegrees(atan2(-eyeDepth, eyeDistance).toDouble()).toFloat()
            val down = ((x(152) - x(10)) * -dy + (y(152) - y(10)) * dx) / eyeDistance
            val pitch = Math.toDegrees(atan2(z(152) - z(10), down.coerceAtLeast(0.0001f)).toDouble()).toFloat()
            val noseOffset = ((x(1) - (leftX + rightX) / 2f) * dx + (y(1) - (leftY + rightY) / 2f) * dy) / (eyeDistance * eyeDistance)
            return HeadPose(yaw, pitch, abs(noseOffset) < 0.23f && abs(yaw) < 18f)
        }
    }
}

enum class EnrollmentPose(val instruction: String, val targetYaw: Float, val targetPitch: Float) {
    FRONT("Olhe de frente para a lente", 0f, 0f),
    RIGHT("Vire um pouco à sua direita", 17f, 0f),
    LEFT("Vire um pouco à sua esquerda", -17f, 0f),
    UP("Levante um pouco o queixo", 0f, -12f),
    DOWN("Abaixe um pouco o queixo", 0f, 12f),
}

data class EnrollmentGuideState(val pose: EnrollmentPose, val stage: Int, val samples: Int,
    val hold: Float, val returning: Boolean = false, val feedback: String = "Siga a demonstração e mova devagar.") {
    val instruction get() = if (returning) "Volte a olhar de frente" else pose.instruction
    val stageText get() = "Posição $stage de 5 · ${samples.coerceAtMost(15)}/15 amostras"
}

/** Three stable captures in each pose. Repeated frontal frames cannot complete the tutorial. */
class EnrollmentCoach {
    var samples = 0
        private set
    val complete get() = samples == TOTAL_SAMPLES
    private var returning = false
    private var baselineYaw = 0f
    private var baselinePitch = 0f
    private var previous: HeadPose? = null
    private var smoothed: HeadPose? = null
    private var heldSince = 0L
    private var ready = false
    private var state = EnrollmentGuideState(EnrollmentPose.FRONT, 1, 0, 0f)
    val guide get() = state
    val stage get() = (samples / SAMPLES_PER_POSE).coerceAtMost(4)

    fun observe(pose: HeadPose?, now: Long, quality: String? = null): Boolean {
        if (complete) return false
        if (pose == null || quality != null) {
            heldSince = 0; ready = false; previous = null; smoothed = null
            state = EnrollmentGuideState(EnrollmentPose.entries[stage], stage + 1, samples, 0f, returning,
                quality ?: "Mantenha somente seu rosto visível na câmera.")
            return false
        }
        val filtered = smoothed?.let { HeadPose(it.yaw * 0.65f + pose.yaw * 0.35f,
            it.pitch * 0.65f + pose.pitch * 0.35f, pose.centered) } ?: pose
        smoothed = filtered
        val stable = previous?.let { abs(it.yaw - filtered.yaw) < 1.6f && abs(it.pitch - filtered.pitch) < 1.6f } ?: false
        previous = filtered
        val inside = matches(filtered)
        if (!inside || !stable) heldSince = 0L
        else if (heldSince == 0L) heldSince = now
        val hold = if (heldSince == 0L) 0f else ((now - heldSince) / HOLD_MS.toFloat()).coerceIn(0f, 1f)
        if (returning && hold == 1f) {
            returning = false; heldSince = 0; ready = false
            state = EnrollmentGuideState(EnrollmentPose.entries[stage], stage + 1, samples, 0f)
            return false
        }
        ready = inside && hold == 1f && !returning
        val feedback = when {
            !inside -> "Mova devagar no sentido mostrado. Uma pequena virada basta."
            !stable || hold < 1f -> "Ótimo. Segure essa posição por um instante."
            else -> "Posição certa. Capturando automaticamente…"
        }
        state = EnrollmentGuideState(EnrollmentPose.entries[stage], stage + 1, samples, hold, returning, feedback)
        return ready
    }

    private fun matches(pose: HeadPose): Boolean {
        if (stage == 0) return pose.centered && abs(pose.pitch) < 35f
        val yaw = pose.yaw - baselineYaw
        val pitch = pose.pitch - baselinePitch
        if (returning) return abs(yaw) < 6f && abs(pitch) < 6f
        return when (EnrollmentPose.entries[stage]) {
            EnrollmentPose.RIGHT -> yaw in 10f..26f && abs(pitch) < 9f
            EnrollmentPose.LEFT -> yaw in -26f..-10f && abs(pitch) < 9f
            EnrollmentPose.UP -> pitch in -21f..-7f && abs(yaw) < 9f
            EnrollmentPose.DOWN -> pitch in 7f..21f && abs(yaw) < 9f
            else -> false
        }
    }

    fun accept(pose: HeadPose, expectedStage: Int): Boolean {
        if (complete || stage != expectedStage || returning || !ready || !matches(pose)) return false
        val oldStage = stage
        if (stage == 0) {
            baselineYaw += pose.yaw / SAMPLES_PER_POSE
            baselinePitch += pose.pitch / SAMPLES_PER_POSE
        }
        samples++
        if (!complete && stage != oldStage) {
            returning = oldStage > 0
            heldSince = 0; ready = false; previous = null; smoothed = null
        }
        state = EnrollmentGuideState(EnrollmentPose.entries[stage], stage + 1, samples, if (complete) 1f else 0f, returning,
            if (complete) "15/15 · Salvando cadastro…" else "Amostra aceita. Continue seguindo a demonstração.")
        return true
    }

    companion object {
        const val SAMPLES_PER_POSE = 3
        const val TOTAL_SAMPLES = 15
        const val HOLD_MS = 400L
    }
}
