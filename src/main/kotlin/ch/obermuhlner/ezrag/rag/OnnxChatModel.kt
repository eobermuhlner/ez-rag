package ch.obermuhlner.ezrag.rag

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.prompt.Prompt
import java.io.File
import ai.onnxruntime.OnnxJavaType
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * ONNX-based chat model that runs a causal language model locally.
 *
 * Supports ChatML-format decoder-only ONNX models (SmolLM2, Qwen2, and similar)
 * and Zephyr-format models (TinyLlama and similar).
 * On first [call], the model files are downloaded from HuggingFace into the cache root
 * and a progress message is printed to stderr.
 *
 * Use the primary constructor for production (lazy download + real ONNX session).
 * Use the [backend] constructor parameter for unit testing with a stub backend.
 */
class OnnxChatModel private constructor(
    private val modelName: String,
    private val cacheRoot: File,
    private val injectedBackend: GenerationBackend?,
    private val injectedTemplateFormat: TemplateFormat,
) : ChatModel {

    enum class TemplateFormat { CHATML, ZEPHYR }

    companion object {
        private const val MAX_NEW_TOKENS = 256
        private const val DEFAULT_CONTEXT_TOKENS = 32768
        private const val MAX_REPETITION_PATTERN_LEN = 8

        fun isRepeatingPattern(tokens: List<Long>): Boolean {
            for (patLen in 1..MAX_REPETITION_PATTERN_LEN) {
                if (tokens.size < patLen * 3) continue
                val n = tokens.size
                val b1 = tokens.subList(n - patLen * 3, n - patLen * 2)
                val b2 = tokens.subList(n - patLen * 2, n - patLen)
                val b3 = tokens.subList(n - patLen, n)
                if (b1 == b2 && b2 == b3) return true
            }
            return false
        }

        fun parseEosTokenId(configJson: String): Long? =
            Regex(""""eos_token_id"\s*:\s*(\d+)""").find(configJson)
                ?.groupValues?.get(1)?.toLongOrNull()

        fun parseMaxContextTokens(configJson: String): Int? =
            Regex(""""max_position_embeddings"\s*:\s*(\d+)""").find(configJson)
                ?.groupValues?.get(1)?.toIntOrNull()

        fun parseKvConfig(configJson: String): Pair<Int, Int> {
            val kvHeads = Regex(""""num_key_value_heads"\s*:\s*(\d+)""").find(configJson)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex(""""num_attention_heads"\s*:\s*(\d+)""").find(configJson)
                    ?.groupValues?.get(1)?.toIntOrNull()
                ?: 1
            val hiddenSize = Regex(""""hidden_size"\s*:\s*(\d+)""").find(configJson)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 512
            val numHeads = Regex(""""num_attention_heads"\s*:\s*(\d+)""").find(configJson)
                ?.groupValues?.get(1)?.toIntOrNull() ?: 1
            return Pair(kvHeads, hiddenSize / numHeads)
        }

        fun detectTemplateFormat(chatTemplate: String): TemplateFormat = when {
            "<|im_start|>" in chatTemplate -> TemplateFormat.CHATML
            "<|user|>" in chatTemplate -> TemplateFormat.ZEPHYR
            else -> TemplateFormat.CHATML
        }

        operator fun invoke(modelName: String, cacheRoot: File): OnnxChatModel =
            OnnxChatModel(
                modelName = modelName,
                cacheRoot = cacheRoot,
                injectedBackend = null,
                injectedTemplateFormat = TemplateFormat.CHATML,
            )

        operator fun invoke(
            backend: GenerationBackend,
            templateFormat: TemplateFormat = TemplateFormat.CHATML,
        ): OnnxChatModel =
            OnnxChatModel(
                modelName = "",
                cacheRoot = File("."),
                injectedBackend = backend,
                injectedTemplateFormat = templateFormat,
            )
    }

    interface GenerationBackend {
        val eosTokenId: Long
        val maxContextTokens: Int
        fun tokenize(text: String): LongArray
        fun decode(tokenIds: LongArray): String
        fun nextToken(inputIds: LongArray): Long
    }

    private data class ModelResources(
        val backend: GenerationBackend,
        val templateFormat: TemplateFormat,
    )

    private val modelResources: ModelResources by lazy {
        if (injectedBackend != null) {
            ModelResources(injectedBackend, injectedTemplateFormat)
        } else {
            loadModelResources()
        }
    }

    private fun loadModelResources(): ModelResources {
        val modelDir = cacheRoot.resolve(modelName)
        val isCached = modelDir.resolve("onnx").resolve("decoder_model_merged.onnx").exists() ||
                       modelDir.resolve("onnx").resolve("model.onnx").exists()
        if (!isCached) {
            System.err.println("Downloading ONNX chat model '$modelName' to cache...")
        }
        val downloader = OnnxModelDownloader(modelName, cacheRoot)
        val tokenizerFile = downloader.ensureFile("tokenizer.json", "tokenizer.json")
        val tokenizerConfigFile = downloader.ensureFile("tokenizer_config.json", "tokenizer_config.json")
        val configFile = downloader.ensureFile("config.json", "config.json")
        val modelFile = downloader.ensureCachedOnnxModel(
            "onnx/decoder_model_merged.onnx",
            "onnx/model.onnx"
        )
        downloader.ensureOnnxDataFile(modelFile)

        val templateFormat = try {
            val configContent = tokenizerConfigFile.readText()
            detectTemplateFormat(configContent)
        } catch (_: Exception) {
            TemplateFormat.CHATML
        }

        val tokenizer = HuggingFaceTokenizer.newInstance(tokenizerFile.toPath())
        val env = OrtEnvironment.getEnvironment()
        val session = env.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
        val outputNames = session.outputNames

        val configJson = configFile.readText()
        val eosIdFallback = when (templateFormat) {
            TemplateFormat.CHATML -> 151645L
            TemplateFormat.ZEPHYR -> 2L
        }
        val eosId = parseEosTokenId(configJson) ?: eosIdFallback
        val maxContextTokens = parseMaxContextTokens(configJson) ?: DEFAULT_CONTEXT_TOKENS

        val (kvHeads, headDim) = parseKvConfig(configJson)

        return ModelResources(
            OnnxRealBackend(env, session, tokenizer, eosId, maxContextTokens, outputNames, kvHeads, headDim),
            templateFormat,
        )
    }

    override fun call(prompt: Prompt): ChatResponse {
        val resources = modelResources
        val formattedInput = formatPrompt(prompt, resources.templateFormat)

        val inputTokens = resources.backend.tokenize(formattedInput)
        val effectiveTokens = if (inputTokens.size > resources.backend.maxContextTokens) {
            System.err.println(
                "Warning: input token count (${inputTokens.size}) exceeds model context limit " +
                "(${resources.backend.maxContextTokens}). Input will be truncated."
            )
            inputTokens.copyOfRange(inputTokens.size - resources.backend.maxContextTokens, inputTokens.size)
        } else {
            inputTokens
        }

        val generatedTokenIds = mutableListOf<Long>()
        val currentIds = effectiveTokens.toMutableList()

        for (step in 0 until MAX_NEW_TOKENS) {
            val nextToken = resources.backend.nextToken(currentIds.toLongArray())
            if (nextToken == resources.backend.eosTokenId) break
            generatedTokenIds.add(nextToken)
            currentIds.add(nextToken)
            if (isRepeatingPattern(generatedTokenIds)) break
        }

        val generatedText = if (generatedTokenIds.isEmpty()) ""
        else resources.backend.decode(generatedTokenIds.toLongArray())

        return ChatResponse(listOf(Generation(AssistantMessage(generatedText))))
    }

    private fun formatPrompt(prompt: Prompt, templateFormat: TemplateFormat): String =
        when (templateFormat) {
            TemplateFormat.CHATML -> formatChatML(prompt)
            TemplateFormat.ZEPHYR -> formatZephyr(prompt)
        }

    private fun formatChatML(prompt: Prompt): String {
        val systemMessage = prompt.instructions.filterIsInstance<SystemMessage>().firstOrNull()
        val userMessage = prompt.instructions.filterIsInstance<UserMessage>().firstOrNull()
        return buildString {
            if (systemMessage != null) {
                append("<|im_start|>system\n")
                append(systemMessage.text)
                append("<|im_end|>\n")
            }
            if (userMessage != null) {
                append("<|im_start|>user\n")
                append(userMessage.text)
                append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }

    private fun formatZephyr(prompt: Prompt): String {
        val systemMessage = prompt.instructions.filterIsInstance<SystemMessage>().firstOrNull()
        val userMessage = prompt.instructions.filterIsInstance<UserMessage>().firstOrNull()
        return buildString {
            if (systemMessage != null) {
                append("<|system|>\n")
                append(systemMessage.text)
                append("</s>\n")
            }
            if (userMessage != null) {
                append("<|user|>\n")
                append(userMessage.text)
                append("</s>\n")
            }
            append("<|assistant|>\n")
        }
    }

    private class OnnxRealBackend(
        private val env: OrtEnvironment,
        private val session: OrtSession,
        private val tokenizer: HuggingFaceTokenizer,
        override val eosTokenId: Long,
        override val maxContextTokens: Int,
        private val outputNames: Set<String>,
        private val kvHeads: Int,
        private val headDim: Int,
    ) : GenerationBackend {

        private val hasCacheBranch = "use_cache_branch" in session.inputInfo
        private val kvInputNames = session.inputInfo.keys.filter { it.startsWith("past_key_values") }

        // KV cache stored as plain FloatArrays + shapes to avoid dangling OnnxTensor references.
        // Result.close() frees all output tensors, so we copy data out before the result closes.
        private data class KvEntry(val data: FloatArray, val shape: LongArray)
        private var kvCache: Map<String, KvEntry> = emptyMap()
        private var pastLength: Int = 0

        override fun tokenize(text: String): LongArray = tokenizer.encode(text).ids

        override fun decode(tokenIds: LongArray): String = tokenizer.decode(tokenIds, true)

        override fun nextToken(inputIds: LongArray): Long {
            val inputs = mutableMapOf<String, OnnxTensor>()
            try {
                val isPrefill = pastLength == 0
                val stepIds = if (isPrefill) inputIds else longArrayOf(inputIds.last())
                val stepLen = stepIds.size.toLong()
                val stepShape = longArrayOf(1, stepLen)

                inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(stepIds), stepShape)

                val totalLen = pastLength + stepLen.toInt()
                inputs["attention_mask"] = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(LongArray(totalLen) { 1L }), longArrayOf(1, totalLen.toLong())
                )
                inputs["position_ids"] = OnnxTensor.createTensor(
                    env, LongBuffer.wrap(LongArray(stepLen.toInt()) { i -> (pastLength + i).toLong() }), stepShape
                )

                if (hasCacheBranch) {
                    // use_cache_branch: bool scalar [] — false=prefill, true=decode
                    val buf = ByteBuffer.allocateDirect(1)
                    buf.put(if (isPrefill) 0.toByte() else 1.toByte())
                    buf.flip()
                    inputs["use_cache_branch"] = OnnxTensor.createTensor(env, buf, longArrayOf(), OnnxJavaType.BOOL)
                }

                // KV cache: empty tensors on prefill (shape [1, kvHeads, 0, headDim]),
                // reconstructed from stored FloatArrays on decode steps.
                for (name in kvInputNames) {
                    val tensor = if (isPrefill) {
                        OnnxTensor.createTensor(
                            env, FloatBuffer.wrap(FloatArray(0)),
                            longArrayOf(1L, kvHeads.toLong(), 0L, headDim.toLong())
                        )
                    } else {
                        val entry = kvCache[name] ?: continue
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(entry.data), entry.shape)
                    }
                    inputs[name] = tensor
                }

                val result = session.run(inputs)
                // Copy present tensors to FloatArrays BEFORE result.close() frees them.
                result.use { output ->
                    val logitsTensor = output["logits"].get().value as Array<*>
                    val lastTokenLogits = (logitsTensor[0] as Array<*>).last() as FloatArray
                    val nextTokenId = argmax(lastTokenLogits)

                    val newKvCache = mutableMapOf<String, KvEntry>()
                    for (name in outputNames) {
                        if (!name.startsWith("present")) continue
                        val tensor = output[name].get()
                        if (tensor is OnnxTensor) {
                            val buf = tensor.floatBuffer
                            val data = FloatArray(buf.remaining()).also { buf.get(it) }
                            val shape = tensor.info.shape.clone()
                            newKvCache["past_key_values.${name.removePrefix("present.")}"] = KvEntry(data, shape)
                        }
                    }
                    kvCache = newKvCache
                    pastLength += stepLen.toInt()

                    return nextTokenId.toLong()
                }
            } finally {
                inputs.values.forEach { it.close() }
            }
        }

        private fun argmax(logits: FloatArray): Int {
            var maxIdx = 0
            var maxVal = logits[0]
            for (i in 1 until logits.size) {
                if (logits[i] > maxVal) { maxVal = logits[i]; maxIdx = i }
            }
            return maxIdx
        }
    }
}
