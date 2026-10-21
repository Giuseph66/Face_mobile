package br.com.sonolencia.faceencoder

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class EnrollmentCoachTest {
    private var now = 1000L
    private fun settle(coach: EnrollmentCoach, pose: HeadPose): Boolean {
        var ready = false
        repeat(20) { ready = coach.observe(pose, now); now += 100 }
        return ready
    }

    @Test fun requiresAllFivePosesInsteadOfRepeatedFrontalCaptures() {
        val coach = EnrollmentCoach()
        val front = HeadPose(0f, 0f)
        repeat(3) {
            assertTrue(settle(coach, front))
            assertTrue(coach.accept(front, coach.stage))
        }
        repeat(20) {
            coach.observe(front, now); now += 100
            assertFalse(coach.accept(front, coach.stage))
        }
        assertEquals(3, coach.samples)
        for (pose in EnrollmentPose.entries.drop(1)) {
            if (coach.guide.returning) {
                settle(coach, front)
                assertFalse(coach.guide.returning)
            }
            val target = HeadPose(pose.targetYaw, pose.targetPitch)
            repeat(3) {
                assertTrue(settle(coach, target))
                assertTrue(coach.accept(target, coach.stage))
            }
        }
        assertTrue(coach.complete)
        assertEquals(15, coach.samples)
        assertFalse(coach.accept(front, 4))
    }

    @Test fun rejectsMotionWrongPoseQualityFailureAndLateStageResults() {
        val coach = EnrollmentCoach()
        assertFalse(coach.observe(HeadPose(0f, 0f), now))
        assertFalse(coach.accept(HeadPose(0f, 0f), 0))
        assertFalse(settle(coach, HeadPose(35f, 0f, false)))
        assertFalse(coach.accept(HeadPose(35f, 0f, false), 0))
        assertTrue(settle(coach, HeadPose(0f, 0f)))
        coach.observe(null, now, "Iluminação insuficiente")
        assertFalse(coach.accept(HeadPose(0f, 0f), 0))
        assertEquals("Iluminação insuficiente", coach.guide.feedback)
        repeat(3) {
            settle(coach, HeadPose(0f, 0f))
            assertTrue(coach.accept(HeadPose(0f, 0f), 0))
        }
        assertFalse(coach.accept(HeadPose(0f, 0f), 0))
        assertFalse(settle(coach, HeadPose(45f, 0f)))
        assertEquals(3, coach.samples)
    }

    @Test fun poseEstimatesRespectMirroringAndInPlaneRotation() {
        val raw = FloatArray(1434)
        fun point(index: Int, x: Float, y: Float, z: Float = 0f) {
            raw[index * 3] = x; raw[index * 3 + 1] = y; raw[index * 3 + 2] = z
        }
        val yaw = Math.toRadians(17.0)
        for (i in intArrayOf(33, 133)) point(i, .3f, .4f, (.2 * tan(yaw)).toFloat())
        for (i in intArrayOf(362, 263)) point(i, .7f, .4f, (-.2 * tan(yaw)).toFloat())
        point(10, .5f, .1f)
        point(152, .5f, .9f, (.6 * tan(Math.toRadians(12.0))).toFloat())
        point(1, .5f, .6f)
        val reference = HeadPose.measure(raw, 640, 480, false)
        assertEquals(17f, reference.yaw, .001f)
        assertEquals(12f, reference.pitch, .001f)
        val angle = Math.toRadians(70.0)
        for (i in raw.indices step 3) {
            val x = raw[i] - .5f; val y = (raw[i + 1] - .5f) * .75f
            raw[i] = 1f - (x * cos(angle).toFloat() - y * sin(angle).toFloat() + .5f)
            raw[i + 1] = (x * sin(angle).toFloat() + y * cos(angle).toFloat()) / .75f + .5f
        }
        val changed = HeadPose.measure(raw, 640, 480, true)
        assertEquals(reference.yaw, changed.yaw, .001f)
        assertEquals(reference.pitch, changed.pitch, .001f)
    }
}
