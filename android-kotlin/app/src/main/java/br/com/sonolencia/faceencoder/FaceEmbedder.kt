package br.com.sonolencia.faceencoder

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** This pinned AI Hub export accepts TWO NCHW RGB [0,1] crops and applies flip TTA internally. */
class FaceEmbedder(context: Context) : Closeable {
    private val interpreter = context.assets.openFd("mobilefacenet.tflite").use { asset ->
        FileInputStream(asset.fileDescriptor).use { stream ->
            val model = stream.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            val digest = MessageDigest.getInstance("SHA-256").apply { update(model.duplicate()) }.digest()
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            check(digest == MODEL_SHA256) { "O modelo facial não corresponde à galeria desta versão." }
            Interpreter(model,
                Interpreter.Options().setNumThreads(2).setUseXNNPACK(true))
        }
    }
    private val input1 = ByteBuffer.allocateDirect(3 * 112 * 112 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val input2 = ByteBuffer.allocateDirect(3 * 112 * 112 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val output = Array(2) { FloatArray(128) }

    init {
        check(interpreter.inputTensorCount == 2 && interpreter.outputTensorCount == 1)
        repeat(2) { check(interpreter.getInputTensor(it).shape().contentEquals(intArrayOf(1, 3, 112, 112))) }
        check(interpreter.getOutputTensor(0).shape().contentEquals(intArrayOf(2, 128)))
    }

    fun embed(first: FloatArray, second: FloatArray = first): List<FloatArray> {
        require(first.size == 3 * 112 * 112 && second.size == first.size)
        input1.rewind(); input1.put(first); input1.rewind()
        input2.rewind(); input2.put(second); input2.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf(input1, input2), mutableMapOf<Int, Any>(0 to output))
        return output.map(FaceMatcher::normalize)
    }

    override fun close() { interpreter.close() }

    companion object {
        const val MODEL_SHA256 = "2046ab776fba2ec7e420767ffe7eb74a7402a8412d3d7ea8b9f4ca8420e5f3d7"
        const val MODEL_ID = "mobilefacenet-qai-v0.64.0-2046ab77-rgb-tta-v1"
    }
}
