package br.com.sonolencia.faceencoder

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

class PeopleActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var rows: LinearLayout
    private lateinit var status: TextView
    private lateinit var gallery: FaceGallery

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gallery = FaceGallery(applicationContext)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(16, 23, 20))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(Button(this).apply { text = "Voltar"; isAllCaps = false; setOnClickListener { finish() } })
        root.addView(TextView(this).apply { text = "Pessoas cadastradas"; textSize = 22f; setTextColor(0xFFF0F6F2.toInt()) })
        status = TextView(this).apply { text = "Carregando…"; textSize = 14f; setTextColor(0xFFAAC0B2.toInt()); setPadding(0, dp(12), 0, dp(12)) }
        root.addView(status)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(rows) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(dp(16) + bars.left, dp(16) + bars.top, dp(16) + bars.right, dp(16) + bars.bottom)
            insets
        }
        refresh()
    }

    private fun refresh() {
        worker.execute {
            try {
                val people = gallery.read()
                runOnUiThread { if (!isDestroyed) showPeople(people) }
            } catch (_: Exception) {
                runOnUiThread { if (!isDestroyed) status.text = "Não foi possível abrir os cadastros protegidos. Nenhum dado foi apagado." }
            }
        }
    }

    private fun showPeople(people: List<EnrolledPerson>) {
        rows.removeAllViews()
        status.text = if (people.isEmpty()) "Nenhum cadastro. Volte à câmera e toque em Cadastrar pessoa."
            else "${people.size} pessoa(s). Vetores protegidos neste celular; fotos não são guardadas no cadastro."
        people.sortedBy { it.name.lowercase() }.forEach { person ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(this).apply {
                text = "${person.name}\n${person.samples.size} amostras · ${if (person.samples.size == EnrollmentCoach.TOTAL_SAMPLES) "5 posições" else "cadastro simples"}"; textSize = 16f
                setTextColor(0xFFF0F6F2.toInt()); setPadding(0, dp(12), dp(8), dp(12))
            }, LinearLayout.LayoutParams(-1, -2))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this).apply {
                text = "Refazer guiado"; isAllCaps = false; contentDescription = "Refazer cadastro guiado de ${person.name}"
                setOnClickListener {
                    startActivity(Intent(this@PeopleActivity, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_ENROLL_ID, person.id)
                        .putExtra(MainActivity.EXTRA_ENROLL_NAME, person.name)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            actions.addView(Button(this).apply {
                text = "Excluir"; isAllCaps = false; contentDescription = "Excluir ${person.name}"
                setOnClickListener {
                    AlertDialog.Builder(this@PeopleActivity).setTitle("Excluir ${person.name}?")
                        .setMessage("As amostras desta pessoa serão removidas deste aparelho.")
                        .setNegativeButton("Cancelar", null).setPositiveButton("Excluir") { _, _ -> delete(person.id, this) }.show()
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            row.addView(actions)
            rows.addView(row)
        }
    }

    private fun delete(id: String, button: View) {
        button.isEnabled = false
        worker.execute {
            try {
                val people = gallery.delete(id)
                runOnUiThread { if (!isDestroyed) showPeople(people) }
            } catch (_: Exception) {
                runOnUiThread { if (!isDestroyed) { status.text = "Não foi possível excluir. Tente novamente."; button.isEnabled = true } }
            }
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDestroy() { worker.shutdown(); super.onDestroy() }
}
