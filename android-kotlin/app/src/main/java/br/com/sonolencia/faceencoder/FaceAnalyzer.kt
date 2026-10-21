package br.com.sonolencia.faceencoder

import android.content.Context
import android.os.SystemClock
import android.os.Build
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.io.Closeable
import java.util.Locale

/** Initialization, inference, compositing and cleanup all run on one camera executor. */
class FaceAnalyzer(
    private val context: Context,
    private val surface: FaceOverlayView,
    private val mirrored: Boolean,
    private val maxFaces: Int,
    private val onStatus: (String) -> Unit,
    private val onFaceCountChanged: (Int) -> Unit,
    private val onRecognitionEvent: (RecognitionEvent) -> Unit,
    private val handsMode: Boolean = false,
) : ImageAnalysis.Analyzer, Closeable {
    @Volatile var active = true
    @Volatile var maxFps = 30
    private var detector: FaceLandmarker? = null
    private val preferences = context.getSharedPreferences("performance", Context.MODE_PRIVATE)
    private val preferenceKey = "delegate_1.1.1_model1_rgba2_${Build.FINGERPRINT.hashCode()}_faces$maxFaces"
    private val cachedDelegate = preferences.getString(preferenceKey, null)
    private var useGpu = cachedDelegate == "GPU"
    private var calibrated = cachedDelegate != null
    private var faceFrames = 0
    private var calibrationFaces = 0
    private var cpuEstimateMs = 0f
    private val cpuTimings = FloatArray(8)
    private var lastFaceCount: Int? = null
    private var frame: FaceFrame? = null
    private var frameImage: FrameImage? = null
    private var nextFrameNs = 0L
    private var fpsWindowStartedMs = 0L
    private var fpsWindowFrames = 0
    private var lastTimestamp = 0L
    private var retryAfterMs = 0L
    private var lastLogMs = 0L
    private var totalFrames = 0L
    private var smoothedFps = 0f
    private val recognition = if (handsMode) null else FaceRecognition(context, onRecognitionEvent)
    private val handAnalyzer = if (handsMode) HandAnalyzer(context) else null

    private fun createDetector(delegate: Delegate): FaceLandmarker {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task")
                .setDelegate(delegate).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(maxFaces)
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(false)
            .build()
        return FaceLandmarker.createFromOptions(context, options)
    }

    private fun initialize() {
        if (detector != null) return
        if (useGpu) {
            try {
                detector = createDetector(Delegate.GPU)
                return
            } catch (error: RuntimeException) {
                Log.w(TAG, "GPU indisponível; usando CPU.", error)
                useGpu = false
                calibrated = true
                preferences.edit().putString(preferenceKey, "CPU").apply()
            }
        }
        detector = createDetector(Delegate.CPU)
    }

    /** Profile only once on a real face; retain the faster delegate for future sessions. */
    private fun calibrate(input: MPImage, timestamp: Long, faceCount: Int) {
        onStatus("Otimizando a análise para seu aparelho…")
        var candidate: FaceLandmarker? = null
        var gpuMs = Float.POSITIVE_INFINITY
        try {
            candidate = createDetector(Delegate.GPU)
            val timings = FloatArray(3)
            repeat(5) { index ->
                val started = SystemClock.elapsedRealtimeNanos()
                val result = candidate.detectForVideo(input, checkNotNull(frameImage).options, timestamp + index + 1)
                check(result.faceLandmarks().size == faceCount)
                check(result.faceLandmarks().all { it.size == 478 })
                if (index >= 2) timings[index - 2] = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000f
            }
            timings.sort()
            gpuMs = timings[1]
            if (gpuMs < cpuEstimateMs * 0.85f) {
                closeDetector()
                detector = candidate
                candidate = null
                useGpu = true
                lastTimestamp = timestamp + 5
            }
        } catch (error: RuntimeException) {
            Log.i(TAG, "Mantendo CPU após calibração: ${error.message}")
        } finally {
            candidate?.close()
            calibrated = true
            preferences.edit().putString(preferenceKey, if (useGpu) "GPU" else "CPU").apply()
            lastFaceCount = null
            Log.i(TAG, "Calibration cpuMs=$cpuEstimateMs gpuMs=$gpuMs selected=${if (useGpu) "GPU" else "CPU"}")
        }
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (!active || SystemClock.uptimeMillis() < retryAfterMs) return
            val startedNs = SystemClock.elapsedRealtimeNanos()
            val intervalNs = 1_000_000_000L / maxFps.coerceIn(10, 30)
            if (startedNs + 2_000_000L < nextFrameNs) return
            if (nextFrameNs == 0L || startedNs - nextFrameNs > intervalNs) nextFrameNs = startedNs
            nextFrameNs += intervalNs
            if (!handsMode) initialize()
            val preparationStarted = SystemClock.elapsedRealtimeNanos()
            prepareFrame(image)
            val preparationMs = (SystemClock.elapsedRealtimeNanos() - preparationStarted) / 1_000_000f
            val current = checkNotNull(frame)
            val prepared = checkNotNull(frameImage)
            val input = prepared.image
            val timestamp = maxOf(SystemClock.uptimeMillis(), lastTimestamp + 1)
            lastTimestamp = timestamp
            val inferenceStarted = SystemClock.elapsedRealtimeNanos()
            current.timestampMs = timestamp
            current.backend = if (useGpu) "GPU" else "CPU"
            current.mirrored = mirrored
            current.handsMode = handsMode
            current.faceCount = 0
            if (handsMode) {
                checkNotNull(handAnalyzer).update(prepared, current.hands, timestamp)
                current.backend = "CPU"
                current.inferenceMs = (SystemClock.elapsedRealtimeNanos() - inferenceStarted) / 1_000_000f
            } else {
                val result = detect(input, timestamp)
                current.inferenceMs = (SystemClock.elapsedRealtimeNanos() - inferenceStarted) / 1_000_000f
                for (points in result.faceLandmarks()) {
                    if (current.faceCount >= maxFaces) break
                    if (points.size != 478) continue
                    val face = current.faces[current.faceCount]
                    points.forEachIndexed { index, point ->
                        prepared.writePoint(face.landmarks, index * 3, point.x(), point.y(), point.z())
                    }
                    FaceEncoder.encode(face.landmarks, current.width, current.height, face.encoding)
                    current.faceCount++
                }
                if (!calibrated && current.faceCount > 0) {
                    if (calibrationFaces != current.faceCount) {
                        calibrationFaces = current.faceCount
                        faceFrames = 0
                    }
                    faceFrames++
                    if (faceFrames > 2) cpuTimings[faceFrames - 3] = current.inferenceMs
                    if (faceFrames >= 10) {
                        cpuTimings.sort()
                        cpuEstimateMs = (cpuTimings[3] + cpuTimings[4]) / 2f
                        calibrate(input, timestamp, current.faceCount)
                    }
                }
                recognition?.update(current, prepared)
            }
            val detected = if (handsMode) current.hands.handCount else current.faceCount
            if (lastFaceCount != detected) {
                lastFaceCount = detected
                onFaceCountChanged(detected)
            }
            val now = SystemClock.uptimeMillis()
            if (fpsWindowStartedMs == 0L) {
                fpsWindowStartedMs = now
            } else {
                fpsWindowFrames++
                val elapsed = now - fpsWindowStartedMs
                if (elapsed >= 1000) {
                    smoothedFps = fpsWindowFrames * 1000f / elapsed
                    fpsWindowStartedMs = now
                    fpsWindowFrames = 0
                }
            }
            current.fps = smoothedFps
            val renderStarted = SystemClock.elapsedRealtimeNanos()
            if (active) surface.render(current)
            val publishMs = (SystemClock.elapsedRealtimeNanos() - renderStarted) / 1_000_000f
            totalFrames++
            if (now - lastLogMs >= 5000) {
                lastLogMs = now
                Log.i(TAG, String.format(Locale.US,
                    "frames=%d fps=%.1f inferenceMs=%.1f points=%d backend=%s cap=%d size=%dx%d prepareMs=%.1f publishMs=%.1f faces=%d maxFaces=%d",
                    totalFrames, current.fps, current.inferenceMs, current.pointCount,
                    current.backend, maxFps, current.width, current.height, preparationMs, publishMs, current.faceCount, maxFaces))
            }
        } catch (error: RuntimeException) {
            Log.e(TAG, "Falha na análise.", error)
            closeDetector()
            handAnalyzer?.close()
            frame?.faceCount = 0
            frame?.hands?.handCount = 0
            surface.clear()
            lastFaceCount = null
            onFaceCountChanged(0)
            retryAfterMs = SystemClock.uptimeMillis() + 2000
            onStatus("Falha na análise. Tentando novamente…")
        } finally {
            image.close()
        }
    }

    private fun detect(input: MPImage, timestamp: Long): FaceLandmarkerResult {
        try {
            return checkNotNull(detector).detectForVideo(input, checkNotNull(frameImage).options, timestamp)
        } catch (error: RuntimeException) {
            if (!useGpu) throw error
            Log.w(TAG, "Falha na GPU; repetindo o frame na CPU.", error)
            closeDetector()
            useGpu = false
            calibrated = true
            preferences.edit().putString(preferenceKey, "CPU").apply()
            initialize()
            return checkNotNull(detector).detectForVideo(input, checkNotNull(frameImage).options, timestamp)
        }
    }

    private fun prepareFrame(image: ImageProxy) {
        val degrees = image.imageInfo.rotationDegrees
        val previous = frameImage
        if (previous?.sourceWidth != image.width || previous.sourceHeight != image.height || previous.degrees != degrees) {
            previous?.close()
            val prepared = FrameImage(image.width, image.height, degrees, mirrored)
            frameImage = prepared
            frame = FaceFrame(prepared.width, prepared.height, maxFaces)
        }
        val plane = image.planes[0]
        checkNotNull(frameImage).update(plane.buffer, plane.rowStride, plane.pixelStride)
    }

    /** Must be called on the same executor as analyze(). */
    fun snapshot(): FaceSnapshot? {
        val current = frame?.takeIf { it.pointCount > 0 } ?: return null
        val selection = if (handsMode) surface.handSelection() else null
        return FaceSnapshot(
            checkNotNull(frameImage).snapshot(),
            List(current.faceCount) { index ->
                FaceSnapshotData(current.faces[index].landmarks.copyOf(), current.faces[index].encoding.copyOf())
            }, current.timestampMs,
            surface.selectedFace.get().coerceIn(0, (current.faceCount - 1).coerceAtLeast(0)),
            surface.selectedPoint.get().coerceIn(0, 477), current.mirrored,
            hands = selection?.let { (id, point) ->
                val selectedId = id.takeIf { candidate -> (0 until current.hands.handCount).any { current.hands.hands[it].trackId == candidate } }
                    ?: if (current.hands.handCount > 0) current.hands.hands[0].trackId else 0L
                HandSnapshot(HandMapping().also(current.hands::copyInto), selectedId, point)
            },
        )
    }

    private fun closeDetector() {
        try { detector?.close() } catch (error: RuntimeException) { Log.w(TAG, "Fechando detector.", error) }
        detector = null
    }

    fun startEnrollment(name: String, replaceId: String? = null) { if (active) recognition?.startEnrollment(name, replaceId) }
    fun cancelEnrollment() { recognition?.cancelEnrollment() }

    override fun close() {
        active = false
        closeDetector()
        recognition?.close()
        handAnalyzer?.close()
        frameImage?.close()
        frame = null
        frameImage = null
    }

    companion object { private const val TAG = "FaceEncoder" }
}
