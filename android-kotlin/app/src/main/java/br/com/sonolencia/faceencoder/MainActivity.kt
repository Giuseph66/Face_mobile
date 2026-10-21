package br.com.sonolencia.faceencoder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var surface: FaceOverlayView
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var pauseButton: Button
    private lateinit var saveButton: Button
    private lateinit var hint: TextView
    private lateinit var recognitionStatus: TextView
    private var enrolling = false
    private var pendingEnrollmentName: String? = null
    private var enrollmentPersonId: String? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor { work ->
        Thread(work, "face-camera")
    }
    private val ioExecutor = Executors.newSingleThreadExecutor { work -> Thread(work, "face-save") }
    private var provider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var analyzer: FaceAnalyzer? = null
    private var started = false
    private var paused = false
    private var generation = 0
    private var thermalCap = 30
    private var hasFace = false
    private var saving = false
    private val faceLimits = intArrayOf(1, 2, 3, 8)
    private var faceLimitIndex = 0
    private var maxFaces = 1
    private var handsMode = true
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && started && !paused) startCamera()
        else if (!granted) {
            status.text = "Permita o acesso à câmera para começar."
            pauseButton.text = "Permitir"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xFF101714.toInt()),
            navigationBarStyle = SystemBarStyle.dark(0xFF101714.toInt()),
        )
        setContentView(R.layout.activity_main)
        val root = findViewById<android.view.View>(R.id.root)
        val padding = root.paddingLeft
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        surface = findViewById(R.id.face_surface)
        previewView = findViewById(R.id.camera_preview)
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        status = findViewById(R.id.status)
        pauseButton = findViewById(R.id.pause)
        saveButton = findViewById(R.id.save)
        hint = findViewById(R.id.capture_hint)
        recognitionStatus = findViewById(R.id.recognition_status)
        findViewById<Button>(R.id.enroll).setOnClickListener {
            if (findViewById<View>(R.id.enrollment_panel).visibility != View.VISIBLE) showEnrollmentForm(null, null)
        }
        findViewById<Button>(R.id.people).setOnClickListener { startActivity(Intent(this, PeopleActivity::class.java)) }
        findViewById<Button>(R.id.enrollment_start).setOnClickListener { beginEnrollment() }
        findViewById<Button>(R.id.enrollment_cancel).setOnClickListener {
            val current = analyzer
            if (current != null && enrolling) cameraExecutor.execute { current.cancelEnrollment() }
            else {
                findViewById<View>(R.id.enrollment_panel).visibility = View.GONE
                enrollmentPersonId = null
                updateEnrollmentControls()
            }
        }
        updateSaveButton()
        val settings = getSharedPreferences("settings", MODE_PRIVATE)
        handsMode = settings.getBoolean("hands_mode", settings.getBoolean("body_hands_mode", true))
        val mappingSelector = findViewById<Spinner>(R.id.mapping_mode)
        mappingSelector.adapter = ArrayAdapter.createFromResource(this, R.array.mapping_modes, R.layout.face_limit_item).apply {
            setDropDownViewResource(R.layout.face_limit_item)
        }
        mappingSelector.setSelection(if (handsMode) 1 else 0, false)
        mappingSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                setMappingMode(position == 1)
            }
        }
        faceLimitIndex = settings.getInt("face_limit_index", 0).coerceIn(0, 3)
        maxFaces = faceLimits[faceLimitIndex]
        val selector = findViewById<Spinner>(R.id.face_limit)
        selector.adapter = ArrayAdapter.createFromResource(this, R.array.face_limits, R.layout.face_limit_item).apply {
            setDropDownViewResource(R.layout.face_limit_item)
        }
        selector.setSelection(faceLimitIndex, false)
        val limitHint = findViewById<TextView>(R.id.face_limit_hint)
        fun updateLimitHint() {
            limitHint.visibility = if (!handsMode && faceLimitIndex == 3) android.view.View.VISIBLE else android.view.View.GONE
        }
        updateLimitHint()
        selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position == faceLimitIndex) return
                faceLimitIndex = position
                maxFaces = faceLimits[position]
                settings.edit().putInt("face_limit_index", position).apply()
                updateLimitHint()
                if (started && !paused) {
                    stopCamera()
                    startCamera()
                }
            }
        }
        pauseButton.setOnClickListener {
            if (!hasCameraPermission()) {
                permission.launch(Manifest.permission.CAMERA)
            } else {
                paused = !paused
                if (paused) {
                    stopCamera()
                    status.setText(R.string.camera_paused)
                    pauseButton.setText(R.string.resume)
                } else {
                    pauseButton.setText(R.string.pause)
                    startCamera()
                }
            }
        }
        findViewById<Button>(R.id.indices).setOnClickListener {
            surface.showIndices = !surface.showIndices
            surface.invalidate()
            (it as Button).setText(if (surface.showIndices) R.string.numbers_on else R.string.numbers)
            it.isSelected = surface.showIndices
        }
        saveButton.setOnClickListener { saveSnapshot() }
        enrollmentPersonId = savedInstanceState?.getString("enrollment_person_id")
        readEnrollmentIntent(intent)
        updateMappingControls()
        updateEnrollmentControls()
        observeTemperature()
        if (!hasCameraPermission()) permission.launch(Manifest.permission.CAMERA)
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (hasCameraPermission() && !paused) startCamera()
    }

    override fun onStop() {
        started = false
        stopCamera()
        super.onStop()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!started || paused || analysis != null || !hasCameraPermission()) return
        val currentGeneration = ++generation
        status.setText(R.string.camera_starting)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!started || paused || generation != currentGeneration) return@addListener
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val front = cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val capture = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setTargetRotation(surface.display?.rotation ?: Surface.ROTATION_0)
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build())
                    .build()
                val preview = Preview.Builder()
                    .setTargetFrameRate(Range(30, 30))
                    .setTargetRotation(surface.display?.rotation ?: Surface.ROTATION_0)
                    .setResolutionSelector(ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                        .build())
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val processor = FaceAnalyzer(applicationContext, surface, front, if (enrolling) maxOf(2, maxFaces) else maxFaces,
                    onStatus = { message -> runOnUiThread {
                        if (started && !paused && generation == currentGeneration) status.text = message
                    } },
                    onFaceCountChanged = { detected -> runOnUiThread {
                        if (started && !paused && generation == currentGeneration) {
                            hasFace = detected > 0
                            status.text = if (handsMode) {
                                if (detected > 0) "Acompanhando mãos · análise local" else "Mostre suas mãos na câmera"
                            } else if (detected > 0) resources.getQuantityString(R.plurals.faces_found, detected, detected)
                                else getString(R.string.position_face)
                            updateSaveButton()
                        }
                    } },
                    onRecognitionEvent = { event -> runOnUiThread {
                        if (started && !paused && generation == currentGeneration) {
                            if (event.count != null) findViewById<Button>(R.id.people).text = "Pessoas (${event.count})"
                            val formVisible = findViewById<View>(R.id.enrollment_panel).visibility == View.VISIBLE
                            if ((!formVisible || event.count == null || event.finished) && recognitionStatus.text.toString() != event.message)
                                recognitionStatus.text = event.message
                            if (enrolling) {
                                findViewById<View>(R.id.enrollment_cancel).isEnabled = event.samples < EnrollmentCoach.TOTAL_SAMPLES
                                event.guide?.let { renderGuide(it) }
                            }
                            if (event.finished) {
                                val restore = enrolling
                                enrolling = false
                                pendingEnrollmentName = null
                                surface.enrollmentGuide = null
                                if (event.success) {
                                    enrollmentPersonId = null
                                    findViewById<View>(R.id.enrollment_panel).visibility = View.GONE
                                    findViewById<EditText>(R.id.person_name).text.clear()
                                }
                                updateEnrollmentControls()
                                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
                                if (restore) { stopCamera(); startCamera() }
                            }
                        }
                    } },
                    handsMode = handsMode,
                )
                processor.maxFps = thermalCap
                analyzer = processor
                analysis = capture
                capture.setAnalyzer(cameraExecutor, processor)
                cameraProvider.bindToLifecycle(this, selector, preview, capture)
                pendingEnrollmentName?.let { name ->
                    pendingEnrollmentName = null
                    val replaceId = enrollmentPersonId
                    cameraExecutor.execute { processor.startEnrollment(name, replaceId) }
                }
                pauseButton.setText(R.string.pause)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } catch (error: Exception) {
                Log.e("FaceEncoder", "Não foi possível iniciar a câmera.", error)
                stopCamera()
                status.text = "Não foi possível abrir a câmera. Toque em Retomar."
                paused = true
                pauseButton.text = "Retomar"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        generation++
        analyzer?.active = false
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        val previous = analyzer
        if (previous != null) cameraExecutor.execute { previous.close() }
        analyzer = null
        analysis = null
        surface.enrollmentGuide = null
        if (enrolling) {
            enrolling = false
            pendingEnrollmentName = null
            recognitionStatus.text = "Cadastro interrompido. Toque em Iniciar tutorial para começar novamente."
            updateEnrollmentControls()
        }
        surface.clear()
        hasFace = false
        updateSaveButton()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun beginEnrollment() {
        val name = findViewById<EditText>(R.id.person_name).text.toString().trim()
        if (name.isEmpty()) { findViewById<EditText>(R.id.person_name).error = "Informe o nome"; return }
        if (paused || analysis == null) { recognitionStatus.text = "Retome a câmera antes de cadastrar."; return }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        stopCamera()
        enrolling = true
        pendingEnrollmentName = name
        updateEnrollmentControls()
        recognitionStatus.text = "Preparando captura. Mantenha apenas uma pessoa na câmera."
        renderGuide(EnrollmentGuideState(EnrollmentPose.FRONT, 1, 0, 0f))
        startCamera()
    }

    private fun updateEnrollmentControls() {
        findViewById<View>(R.id.mapping_mode).isEnabled = !enrolling
        findViewById<View>(R.id.face_limit).isEnabled = !enrolling
        findViewById<View>(R.id.enroll).isEnabled = !enrolling
        findViewById<View>(R.id.people).isEnabled = !enrolling
        findViewById<View>(R.id.person_name).isEnabled = !enrolling && enrollmentPersonId == null
        findViewById<View>(R.id.person_name).visibility = if (enrolling) View.GONE else View.VISIBLE
        findViewById<View>(R.id.enrollment_start).visibility = if (enrolling) View.GONE else View.VISIBLE
        findViewById<View>(R.id.enrollment_start).isEnabled = !enrolling
        findViewById<View>(R.id.enrollment_cancel).isEnabled = true
    }

    private fun showEnrollmentForm(personId: String?, name: String?) {
        if (handsMode) setMappingMode(false)
        enrollmentPersonId = personId
        if (name != null) findViewById<EditText>(R.id.person_name).setText(name)
        findViewById<View>(R.id.enrollment_panel).visibility = View.VISIBLE
        findViewById<EnrollmentGuideView>(R.id.enrollment_demo).guide = EnrollmentGuideState(EnrollmentPose.FRONT, 1, 0, 0f)
        findViewById<TextView>(R.id.enrollment_step).setText(R.string.enrollment_step)
        findViewById<TextView>(R.id.enrollment_instruction).setText(R.string.enrollment_front)
        findViewById<android.widget.ProgressBar>(R.id.enrollment_progress).progress = 0
        recognitionStatus.text = if (personId == null) "Siga a demonstração: frente, lados e queixo. A captura é automática."
            else "Refazendo $name. O cadastro atual só será substituído ao concluir as cinco posições."
        updateEnrollmentControls()
    }

    private fun renderGuide(guide: EnrollmentGuideState) {
        findViewById<EnrollmentGuideView>(R.id.enrollment_demo).guide = guide
        val step = findViewById<TextView>(R.id.enrollment_step)
        val instruction = findViewById<TextView>(R.id.enrollment_instruction)
        if (step.text.toString() != guide.stageText) step.text = guide.stageText
        if (instruction.text.toString() != guide.instruction) instruction.text = guide.instruction
        findViewById<android.widget.ProgressBar>(R.id.enrollment_progress).progress = guide.samples
        surface.enrollmentGuide = guide
        surface.invalidate()
    }

    private fun readEnrollmentIntent(source: Intent) {
        val id = source.getStringExtra(EXTRA_ENROLL_ID) ?: return
        val name = source.getStringExtra(EXTRA_ENROLL_NAME) ?: return
        showEnrollmentForm(id, name)
        source.removeExtra(EXTRA_ENROLL_ID)
        source.removeExtra(EXTRA_ENROLL_NAME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readEnrollmentIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("enrollment_person_id", enrollmentPersonId)
        super.onSaveInstanceState(outState)
    }

    private fun saveSnapshot() {
        val current = analyzer
        if (current == null || paused) {
            Toast.makeText(this, "Retome a câmera antes de salvar.", Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        updateSaveButton()
        cameraExecutor.execute {
            val snapshot = current.snapshot()
            if (snapshot == null) {
                runOnUiThread {
                    if (!isDestroyed) {
                        saving = false
                        updateSaveButton()
                        Toast.makeText(this, if (handsMode) "Mostre suas mãos antes de salvar."
                            else "Posicione seu rosto antes de salvar.", Toast.LENGTH_SHORT).show()
                    }
                }
                return@execute
            }
            ioExecutor.execute {
                var savedDirectory: String? = null
                val message = try {
                    val path = SnapshotStore.save(applicationContext, snapshot)
                    savedDirectory = path.substringBeforeLast('/')
                    "Salvo em $path"
                }
                catch (error: Exception) {
                    Log.e("FaceEncoder", "Não foi possível salvar.", error)
                    "Não foi possível salvar. Confira o espaço livre."
                } finally { snapshot.bitmap.recycle() }
                runOnUiThread {
                    if (!isDestroyed) {
                        saving = false
                        updateSaveButton()
                        hint.text = savedDirectory?.let { getString(R.string.capture_saved, it) } ?: message
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun updateSaveButton() {
        saveButton.isEnabled = hasFace && !paused && !saving
        saveButton.alpha = if (saveButton.isEnabled) 1f else 0.45f
        saveButton.setText(if (saving) R.string.saving else R.string.save)
    }

    private fun setMappingMode(enabled: Boolean) {
        if (handsMode == enabled) return
        stopCamera()
        handsMode = enabled
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("hands_mode", enabled).apply()
        findViewById<Spinner>(R.id.mapping_mode).setSelection(if (enabled) 1 else 0)
        updateMappingControls()
        if (started && !paused) startCamera()
    }

    private fun updateMappingControls() {
        val faceVisibility = if (handsMode) View.GONE else View.VISIBLE
        findViewById<View>(R.id.face_controls).visibility = faceVisibility
        findViewById<View>(R.id.face_actions).visibility = faceVisibility
        recognitionStatus.visibility = faceVisibility
        findViewById<View>(R.id.face_limit_hint).visibility = if (!handsMode && faceLimitIndex == 3) View.VISIBLE else View.GONE
        if (handsMode) findViewById<View>(R.id.enrollment_panel).visibility = View.GONE
        hint.setText(if (handsMode) R.string.hands_hint else R.string.touch_hint)
        surface.contentDescription = if (handsMode) "Pontos das mãos. Toque em um ponto da mão para selecionar."
            else "Pontos dos rostos. Toque para selecionar um ponto de qualquer rosto."
    }

    private fun observeTemperature() {
        if (Build.VERSION.SDK_INT < 29) return
        val power = getSystemService(PowerManager::class.java)
        val listener = PowerManager.OnThermalStatusChangedListener { thermalStatus ->
            thermalCap = when {
                thermalStatus >= PowerManager.THERMAL_STATUS_EMERGENCY -> 10
                thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL -> 15
                thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE -> 24
                else -> 30
            }
            analyzer?.maxFps = thermalCap
        }
        thermalListener = listener
        power.addThermalStatusListener(ContextCompat.getMainExecutor(this), listener)
    }

    override fun onDestroy() {
        stopCamera()
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        }
        // Let queued snapshot requests reach the IO executor before shutting it down.
        cameraExecutor.execute { ioExecutor.shutdown() }
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ENROLL_ID = "enroll_person_id"
        const val EXTRA_ENROLL_NAME = "enroll_person_name"
    }
}
