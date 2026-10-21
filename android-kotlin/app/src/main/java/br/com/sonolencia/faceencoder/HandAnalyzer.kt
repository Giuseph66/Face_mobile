package br.com.sonolencia.faceencoder

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.Closeable

/** Runs on the camera executor, reusing the camera image and MediaPipe's video tracking. */
class HandAnalyzer(private val context: Context) : Closeable {
    private var hands: HandLandmarker? = null
    private val tracker = HandTracker()
    private var handCostMs = 20f
    private var windowAt = 0L
    private var handFrames = 0

    private fun initialize() {
        if (hands == null) hands = HandLandmarker.createFromOptions(context,
            HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").setDelegate(Delegate.CPU).build())
                .setRunningMode(RunningMode.VIDEO).setNumHands(2)
                .setMinHandDetectionConfidence(0.6f).setMinHandPresenceConfidence(0.6f)
                .setMinTrackingConfidence(0.5f).build())
    }

    fun update(image: FrameImage, output: HandMapping, timestamp: Long) {
        initialize()
        val start = SystemClock.elapsedRealtimeNanos()
        val result = checkNotNull(hands).detectForVideo(image.image, image.options, timestamp)
        handCostMs = handCostMs * 0.8f + (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000f * 0.2f
        output.handCount = 0
        for (points in result.landmarks()) {
            if (points.size != 21 || output.handCount == 2 || points.any { !it.x().isFinite() || !it.y().isFinite() || !it.z().isFinite() }) continue
            val hand = output.hands[output.handCount++]
            points.forEachIndexed { i, p -> image.writePoint(hand.landmarks, i * 3, p.x(), p.y(), p.z()) }
        }
        tracker.update(output, image.width.toFloat() / image.height, timestamp)
        output.handTimestampMs = timestamp
        handFrames++
        if (windowAt == 0L) { windowAt = timestamp; handFrames = 0 }
        val elapsed = timestamp - windowAt
        if (elapsed >= 1000) {
            output.handsFps = handFrames * 1000f / elapsed
            handFrames = 0
            windowAt = timestamp
            Log.d("HandMapping", "handsFps=${output.handsFps} handMs=$handCostMs")
        }
    }

    override fun close() {
        runCatching { hands?.close() }.onFailure { Log.w("HandMapping", "Fechando detector de mãos.", it) }
        hands = null
    }
}
