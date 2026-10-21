package br.com.sonolencia.faceencoder

/** Stable colors for detection slots, not persistent person identities. */
internal object FaceColors {
    private val colors = intArrayOf(
        0xFF7EF3B4.toInt(), 0xFF75D9F1.toInt(), 0xFFFFCF75.toInt(), 0xFFFF9D9D.toInt(),
        0xFFC7B0FF.toInt(), 0xFFFFA9DC.toInt(), 0xFFD5E888.toInt(), 0xFFA8C4FF.toInt(),
    )
    fun at(index: Int): Int = colors[index % colors.size]
}
