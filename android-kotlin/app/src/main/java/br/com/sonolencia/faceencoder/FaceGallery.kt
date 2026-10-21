package br.com.sonolencia.faceencoder

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted, atomic local storage. Call only from a worker; photos are never stored here. */
class FaceGallery(context: Context, fileName: String = "faces-v1.bin") {
    val file = AtomicFile(File(context.noBackupFilesDir, fileName))
    private val alias = "${context.packageName}.face-gallery.$fileName"

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "A chave dos cadastros não está disponível neste aparelho." }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun read(): List<EnrolledPerson> = synchronized(LOCK) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@synchronized emptyList()
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size in 32..MAX_BYTES) { "Arquivo de cadastros inválido." }
        val input = DataInputStream(bytes.inputStream())
        check(input.readInt() == MAGIC) { "Formato de cadastros desconhecido." }
        val iv = ByteArray(12).also { input.readFully(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, iv))
        cipher.updateAAD(FaceEmbedder.MODEL_ID.toByteArray())
        val data = JSONObject(cipher.doFinal(input.readBytes()).toString(Charsets.UTF_8))
        check(data.getInt("version") == 1 && data.getString("model") == FaceEmbedder.MODEL_ID)
        val people = data.getJSONArray("people")
        List(people.length()) { index ->
            val person = people.getJSONObject(index)
            val samples = person.getJSONArray("samples")
            require(samples.length() in 1..EnrollmentCoach.TOTAL_SAMPLES)
            EnrolledPerson(person.getString("id"), person.getString("name"), List(samples.length()) { sampleIndex ->
                val values = samples.getJSONArray(sampleIndex)
                require(values.length() == 128)
                FaceMatcher.normalize(FloatArray(128) { values.getDouble(it).toFloat() })
            })
        }
    }

    fun add(name: String, samples: List<FloatArray>): List<EnrolledPerson> = synchronized(LOCK) {
        val cleanName = name.trim()
        require(cleanName.length in 1..40 && cleanName.none { it.isISOControl() }) { "Use um nome com 1 a 40 caracteres." }
        require(samples.size == 5 || samples.size == EnrollmentCoach.TOTAL_SAMPLES) { "O cadastro guiado precisa de 15 amostras." }
        val people = read()
        require(people.none { it.name.equals(cleanName, ignoreCase = true) }) { "Esse nome já está cadastrado." }
        val updated = people + EnrolledPerson(UUID.randomUUID().toString(), cleanName, samples.map(FaceMatcher::normalize))
        write(updated)
        updated
    }

    fun delete(id: String): List<EnrolledPerson> = synchronized(LOCK) {
        val updated = read().filterNot { it.id == id }
        write(updated)
        updated
    }

    /** Replaces only a completed enrollment, preserving the person's ID and all other records. */
    fun replace(id: String, samples: List<FloatArray>): List<EnrolledPerson> = synchronized(LOCK) {
        require(samples.size == EnrollmentCoach.TOTAL_SAMPLES) { "Conclua as cinco posições antes de atualizar." }
        val people = read()
        require(people.any { it.id == id }) { "Esse cadastro não está mais disponível." }
        val normalized = samples.map(FaceMatcher::normalize)
        val updated = people.map { if (it.id == id) it.copy(samples = normalized) else it }
        write(updated)
        updated
    }

    private fun write(people: List<EnrolledPerson>) {
        val rows = JSONArray()
        people.forEach { person ->
            val samples = JSONArray()
            person.samples.forEach { sample -> samples.put(JSONArray(sample.map { it.toDouble() })) }
            rows.put(JSONObject().put("id", person.id).put("name", person.name).put("samples", samples))
        }
        val plain = JSONObject().put("version", 1).put("model", FaceEmbedder.MODEL_ID).put("people", rows).toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(FaceEmbedder.MODEL_ID.toByteArray())
        val bytes = ByteArrayOutputStream().also { stream ->
            DataOutputStream(stream).use { it.writeInt(MAGIC); it.write(cipher.iv); it.write(cipher.doFinal(plain)) }
        }.toByteArray()
        require(bytes.size <= MAX_BYTES) { "O cadastro local atingiu o limite de armazenamento." }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    companion object {
        private val LOCK = Any()
        private const val MAGIC = 0x46414731
        private const val MAX_BYTES = 8 * 1024 * 1024
    }
}
