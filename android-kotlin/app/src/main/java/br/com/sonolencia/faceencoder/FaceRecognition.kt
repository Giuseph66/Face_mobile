package br.com.sonolencia.faceencoder

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class RecognitionEvent(val message: String, val samples: Int = 0, val finished: Boolean = false,
    val success: Boolean = false, val count: Int? = null, val guide: EnrollmentGuideState? = null)

/** Camera thread owns tracking/enrollment. One bounded worker owns model and disk operations. */
class FaceRecognition(private val context: Context, private val onEvent: (RecognitionEvent) -> Unit,
    private val gallery: FaceGallery = FaceGallery(context)) : Closeable {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "face-recognition") }
    private val busy = AtomicBoolean(true)
    private val active = AtomicBoolean(true)
    @Volatile private var people = emptyList<EnrolledPerson>()
    @Volatile private var loadError: String? = null
    private var embedder: FaceEmbedder? = null
    private val tracker = FaceTracker()
    private val crops = Array(2) { FloatArray(3 * 112 * 112) }
    private var lastJobAt = 0L
    private var epoch = 0
    private var enrollment: Enrollment? = null
    private var lastGuideAt = 0L
    private var lastPoseLogAt = 0L
    private val results = ConcurrentLinkedQueue<Result>()
    private data class Enrollment(val name: String, val epoch: Int, val started: Long, val replaceId: String?,
        val coach: EnrollmentCoach = EnrollmentCoach(), val samples: MutableList<FloatArray> = mutableListOf(), var saving: Boolean = false,
        var qualityWarning: String? = null, var qualityWarningAt: Long = 0L)
    private data class JobFace(val id: Long, val revision: Int, val box: FaceBox, val pose: HeadPose? = null, val stage: Int = 0)
    private data class Result(val epoch: Int, val faces: List<JobFace> = emptyList(), val vectors: List<FloatArray> = emptyList(),
        val saved: List<EnrolledPerson>? = null, val error: String? = null)

    init {
        worker.execute {
            try {
                people = gallery.read()
                if (active.get()) onEvent(RecognitionEvent("${people.size} pessoa(s) cadastrada(s) neste aparelho.", count = people.size))
            } catch (error: Exception) {
                loadError = "Não foi possível abrir os cadastros locais."
                Log.e(TAG, "Abrindo galeria protegida.", error)
                if (active.get()) onEvent(RecognitionEvent(checkNotNull(loadError), finished = true))
            } finally { busy.set(false) }
        }
    }

    fun startEnrollment(name: String, replaceId: String? = null) {
        if (!active.get()) return
        val clean = name.trim()
        val error = when {
            clean.length !in 1..40 || clean.any { it.isISOControl() } -> "Use um nome com 1 a 40 caracteres."
            loadError != null -> loadError
            people.any { it.id != replaceId && it.name.equals(clean, ignoreCase = true) } -> "Nome já cadastrado. Abra Pessoas e toque em Refazer guiado."
            else -> null
        }
        if (error != null) { onEvent(RecognitionEvent(error, finished = true)); return }
        epoch++
        enrollment = Enrollment(clean, epoch, SystemClock.uptimeMillis(), replaceId)
        showGuide(checkNotNull(enrollment), force = true)
    }

    fun cancelEnrollment() {
        if (enrollment?.coach?.complete == true) {
            onEvent(RecognitionEvent("Salvando o cadastro. Aguarde…", EnrollmentCoach.TOTAL_SAMPLES))
            return
        }
        endEnrollment("Cadastro cancelado.")
    }

    private fun endEnrollment(message: String) {
        epoch++
        enrollment = null
        onEvent(RecognitionEvent(message, finished = true))
    }

    private fun showGuide(current: Enrollment, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastGuideAt < 120) return
        lastGuideAt = now
        val guide = current.coach.guide.let { state ->
            if (current.qualityWarning != null && now - current.qualityWarningAt < 800)
                state.copy(feedback = checkNotNull(current.qualityWarning)) else state
        }
        onEvent(RecognitionEvent(guide.feedback, current.samples.size, guide = guide))
    }

    fun update(frame: FaceFrame, image: FrameImage) {
        val now = SystemClock.uptimeMillis()
        val tracks = tracker.update(List(frame.faceCount) { FaceBox.from(frame.faces[it].landmarks) }, now)
        while (true) {
            val result = results.poll() ?: break
            if (result.epoch != epoch) continue
            if (result.error != null) {
                enrollment = null
                onEvent(RecognitionEvent(result.error, finished = true))
            } else if (result.saved != null) {
                people = result.saved
                enrollment = null
                tracks.forEach { it.reset() }
                onEvent(RecognitionEvent("Cadastro guiado salvo. Reconhecimento ativo.", EnrollmentCoach.TOTAL_SAMPLES, true, true, people.size))
            } else if (enrollment != null) {
                val current = checkNotNull(enrollment)
                val job = result.faces.single()
                val track = tracks.singleOrNull()?.takeIf { it.id == job.id && it.revision == job.revision && it.box.iou(job.box) > 0.5f }
                if (track != null) {
                    val vector = result.vectors.first()
                    val consistency = current.samples.maxOfOrNull { FaceMatcher.cosine(it, vector) } ?: 1f
                    if (consistency < 0.60f) {
                        Log.d(TAG, "Guided sample rejected: similarity=$consistency stage=${job.stage}")
                        current.qualityWarning = "Volte um pouco ao centro, mantendo a mesma pessoa na câmera."
                        current.qualityWarningAt = now
                        current.coach.observe(null, now, current.qualityWarning)
                        showGuide(current, force = true)
                    } else if (job.pose != null && current.coach.accept(job.pose, job.stage)) {
                        current.samples.add(vector)
                        showGuide(current, force = true)
                    }
                }
            } else {
                result.faces.forEachIndexed { index, job ->
                    tracker.accept(job.id, job.revision, job.box, FaceMatcher.match(result.vectors[index], people), now)
                }
            }
        }
        for (i in tracks.indices) {
            frame.faces[i].trackId = tracks[i].id
            frame.faces[i].name = tracks[i].name()
            frame.faces[i].label = when {
                tracks[i].ambiguous -> "Separe os rostos"
                loadError != null -> "Reconhecimento indisponível"
                else -> tracks[i].name() ?: "Desconhecido"
            }
        }
        val current = enrollment
        if (current != null && current.samples.isEmpty() && !busy.get() &&
            (people.any { it.id != current.replaceId && it.name.equals(current.name, ignoreCase = true) } ||
                (current.replaceId != null && people.none { it.id == current.replaceId && it.name == current.name }))) {
            enrollment = null
            onEvent(RecognitionEvent("Confira o cadastro em Pessoas e tente novamente.", finished = true))
            return
        }
        if (current != null && now - current.started > 120000 && !current.saving) {
            endEnrollment("Cadastro expirou. Confira luz e enquadramento e tente novamente.")
            return
        }
        if (current != null && !current.coach.complete && tracks.size > 1) {
            endEnrollment("Mantenha somente a pessoa a cadastrar na câmera e reinicie o tutorial.")
            return
        }
        val pose = if (current != null && tracks.size == 1) HeadPose.measure(frame.faces[0].landmarks,
            frame.width, frame.height, image.mirrored) else null
        val poseReady = if (current != null && !current.coach.complete) current.coach.observe(pose, now) else false
        if (current != null && !current.coach.complete) showGuide(current)
        if (current != null && pose != null && now - lastPoseLogAt >= 1000) {
            lastPoseLogAt = now
            Log.d(TAG, "Guide stage=${current.coach.stage} samples=${current.samples.size} yaw=${pose.yaw} pitch=${pose.pitch} centered=${pose.centered}")
        }
        if (loadError != null || busy.get() || now - lastJobAt < if (current == null) 300 else 350) return
        if (current?.coach?.complete == true && !current.saving) {
            current.saving = true
            busy.set(true)
            worker.execute {
                try {
                    val saved = if (current.replaceId == null) gallery.add(current.name, current.samples)
                        else gallery.replace(current.replaceId, current.samples)
                    results.add(Result(current.epoch, saved = saved))
                } catch (error: Exception) { results.add(Result(current.epoch, error = error.message ?: "Não foi possível salvar o cadastro.")) }
                finally { busy.set(false) }
            }
            return
        }
        if (current == null && people.isEmpty()) return
        if (current != null && (tracks.size != 1 || !poseReady)) return
        val candidates = tracks.indices.filter { !tracks[it].ambiguous && tracks[it].needsRecognition() }
            .sortedBy { tracks[it].checkedAt }.take(if (current != null) 1 else 2)
        val jobs = mutableListOf<JobFace>()
        for (i in candidates) {
            val track = tracks[i]
            track.checkedAt = now
            val qualityError = FaceCropper.crop(image, frame.faces[i].landmarks, crops[jobs.size])
            if (qualityError != null) {
                track.reset()
                frame.faces[i].name = null
                frame.faces[i].label = "Ajuste luz e posição"
                if (current != null) {
                    current.qualityWarning = qualityError
                    current.qualityWarningAt = now
                    current.coach.observe(null, now, qualityError)
                    showGuide(current, force = true)
                }
                continue
            }
            if (current != null) current.qualityWarning = null
            jobs.add(JobFace(track.id, track.revision, track.box, pose, current?.coach?.stage ?: 0))
        }
        lastJobAt = now
        if (jobs.isEmpty()) return
        val jobEpoch = epoch
        busy.set(true)
        worker.execute {
            try {
                val started = SystemClock.elapsedRealtimeNanos()
                val model = embedder ?: FaceEmbedder(context).also { embedder = it }
                val vectors = model.embed(crops[0], if (jobs.size == 2) crops[1] else crops[0])
                results.add(Result(jobEpoch, jobs, vectors.take(jobs.size)))
                Log.d(TAG, "Embedding faces=${jobs.size} ms=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000f}")
            } catch (error: Exception) {
                Log.e(TAG, "Calculando identidade facial.", error)
                loadError = "Reconhecimento indisponível. Feche e abra o app para tentar novamente."
                results.add(Result(jobEpoch, error = checkNotNull(loadError)))
            } finally { busy.set(false) }
        }
    }

    override fun close() {
        if (!active.getAndSet(false)) return
        epoch++
        enrollment = null
        worker.execute { embedder?.close(); embedder = null }
        worker.shutdown()
    }

    companion object { private const val TAG = "FaceRecognition" }
}
