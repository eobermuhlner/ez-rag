package ch.obermuhlner.ezrag.rag

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.net.URI
import java.nio.LongBuffer
import java.nio.file.Files

class OnnxCrossEncoderReranker(
    private val modelName: String,
    cacheDir: String
) : Reranker {

    override val name: String get() = modelName

    private val modelDir: File = File(cacheDir).resolve(modelName)

    private val tokenizer: HuggingFaceTokenizer by lazy {
        val tokenizerFile = ensureCached("tokenizer.json", "tokenizer.json")
        HuggingFaceTokenizer.newInstance(tokenizerFile.toPath())
    }

    private val ortSession: OrtSession by lazy {
        val modelFile = ensureCachedOnnxModel()
        val env = OrtEnvironment.getEnvironment()
        env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    }

    override fun rerank(query: String, candidates: List<ChunkMatch>): List<ChunkMatch> {
        if (candidates.isEmpty()) return emptyList()

        val env = OrtEnvironment.getEnvironment()
        val session = ortSession

        val scores = candidates.map { candidate ->
            scoreCandidate(env, session, query, candidate.content)
        }

        return candidates.zip(scores)
            .map { (chunk, score) -> chunk.copy(score = score) }
            .sortedByDescending { it.score }
    }

    private fun scoreCandidate(
        env: OrtEnvironment,
        session: OrtSession,
        query: String,
        passage: String
    ): Double {
        val encoding = tokenizer.encode(query, passage)

        val inputIds = encoding.ids
        val attentionMask = encoding.attentionMask
        val tokenTypeIds = encoding.typeIds

        val seqLen = inputIds.size
        val inputIdsBuffer = LongBuffer.wrap(inputIds)
        val attentionMaskBuffer = LongBuffer.wrap(attentionMask)
        val tokenTypeIdsBuffer = LongBuffer.wrap(tokenTypeIds)
        val shape = longArrayOf(1, seqLen.toLong())

        val inputs = mutableMapOf<String, OnnxTensor>()
        try {
            val inputIdsTensor = OnnxTensor.createTensor(env, inputIdsBuffer, shape)
            val attentionMaskTensor = OnnxTensor.createTensor(env, attentionMaskBuffer, shape)
            val tokenTypeIdsTensor = OnnxTensor.createTensor(env, tokenTypeIdsBuffer, shape)

            inputs["input_ids"] = inputIdsTensor
            inputs["attention_mask"] = attentionMaskTensor
            inputs["token_type_ids"] = tokenTypeIdsTensor

            val result = session.run(inputs)
            result.use { output ->
                // The cross-encoder output is logits: shape [1, 1] or [1, 2]
                val logitsTensor = output[0].value
                val score = extractScore(logitsTensor)
                return score
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private fun extractScore(value: Any?): Double {
        return when (value) {
            is Array<*> -> {
                // Shape [1, N] — take value at [0][0] for single-logit or [0][1] for 2-class
                val row = value[0]
                when (row) {
                    is FloatArray -> if (row.size == 1) row[0].toDouble() else row[1].toDouble()
                    is DoubleArray -> if (row.size == 1) row[0] else row[1]
                    is Array<*> -> {
                        val inner = row[0]
                        if (inner is Float) inner.toDouble() else (inner as Double)
                    }
                    else -> 0.0
                }
            }
            is FloatArray -> value[0].toDouble()
            is DoubleArray -> value[0]
            else -> 0.0
        }
    }

    private fun ensureCached(remotePath: String, localFileName: String): File {
        val localFile = modelDir.resolve(localFileName)
        if (!localFile.exists()) {
            modelDir.mkdirs()
            val url = "https://huggingface.co/$modelName/resolve/main/$remotePath"
            downloadFile(url, localFile)
        }
        return localFile
    }

    private fun ensureCachedOnnxModel(): File {
        // Try onnx/model.onnx first, then model.onnx
        val onnxSubDir = modelDir.resolve("onnx")
        val onnxSubDirModel = onnxSubDir.resolve("model.onnx")
        if (onnxSubDirModel.exists()) return onnxSubDirModel

        val directModel = modelDir.resolve("model.onnx")
        if (directModel.exists()) return directModel

        // Try to download from onnx/model.onnx on HuggingFace
        try {
            onnxSubDir.mkdirs()
            val url = "https://huggingface.co/$modelName/resolve/main/onnx/model.onnx"
            downloadFile(url, onnxSubDirModel)
            return onnxSubDirModel
        } catch (e: Exception) {
            // Fall back to model.onnx at root
        }

        modelDir.mkdirs()
        val url = "https://huggingface.co/$modelName/resolve/main/model.onnx"
        downloadFile(url, directModel)
        return directModel
    }

    private fun downloadFile(url: String, destination: File) {
        val tempFile = Files.createTempFile(destination.parentFile.toPath(), "download-", ".tmp").toFile()
        try {
            URI(url).toURL().openStream().use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.renameTo(destination)
        } catch (e: Exception) {
            tempFile.delete()
            throw RuntimeException("Failed to download $url to $destination: ${e.message}", e)
        }
    }
}
