package ch.obermuhlner.ezrag.rag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Unit tests for [OnnxChatModel].
 *
 * Uses a [TestGenerationBackend] to avoid any network access, file downloads, or real ONNX models.
 */
class OnnxChatModelTest {

    companion object {
        /** Token ID used as EOS by the fake backend. */
        const val EOS_TOKEN_ID = 151645L

        /**
         * A simple word-level vocabulary for testing:
         * We map a few token IDs to words so the decoded output is predictable.
         */
        val VOCAB = mapOf(
            1000L to "Hello",
            1001L to " world",
            1002L to "!"
        )
    }

    /**
     * Stub backend that accepts arbitrary decode-step behaviour.
     * [tokensToReturn] is the sequence of token IDs emitted one per decode step (before EOS).
     * The backend always appends EOS after the last token.
     */
    open class TestGenerationBackend(
        private val tokensToReturn: LongArray,
        override val eosTokenId: Long = EOS_TOKEN_ID,
        override val maxContextTokens: Int = 32768
    ) : OnnxChatModel.GenerationBackend {

        var lastInputTokens: LongArray = LongArray(0)
            private set
        var decodeCallCount: Int = 0
            private set

        override fun tokenize(text: String): LongArray {
            // Simple stub: return token IDs based on splitting by space
            // Each word maps to 1000, 1001, ... for test predictability
            return text.split(" ").mapIndexed { i, _ -> (1000L + i) }.toLongArray()
        }

        override fun decode(tokenIds: LongArray): String {
            return tokenIds.joinToString("") { id -> VOCAB[id] ?: "" }
        }

        override fun nextToken(inputIds: LongArray): Long {
            lastInputTokens = inputIds.copyOf()
            val newTokenIndex = decodeCallCount
            decodeCallCount++
            return if (newTokenIndex < tokensToReturn.size) {
                tokensToReturn[newTokenIndex]
            } else {
                eosTokenId
            }
        }
    }

    private fun createModel(
        backend: OnnxChatModel.GenerationBackend,
        templateFormat: OnnxChatModel.TemplateFormat = OnnxChatModel.TemplateFormat.CHATML
    ): OnnxChatModel {
        return OnnxChatModel(backend = backend, templateFormat = templateFormat)
    }

    @Test
    fun `OnnxChatModel implements ChatModel`() {
        val backend = TestGenerationBackend(longArrayOf(1000L, 1001L))
        val model = createModel(backend)
        assertThat(model).isInstanceOf(ChatModel::class.java)
    }

    @Test
    fun `given Prompt with SystemMessage and UserMessage returns ChatResponse with non-empty text`() {
        val backend = TestGenerationBackend(longArrayOf(1000L, 1001L, 1002L))
        val model = createModel(backend)

        val prompt = Prompt(listOf(
            SystemMessage("You are a helpful assistant."),
            UserMessage("What is the capital of France?")
        ))
        val response = model.call(prompt)

        assertThat(response).isNotNull()
        assertThat(response.result).isNotNull()
        assertThat(response.result.output.text).isNotEmpty()
    }

    @Test
    fun `given Prompt with only UserMessage call succeeds and returns non-empty text`() {
        val backend = TestGenerationBackend(longArrayOf(1000L, 1001L))
        val model = createModel(backend)

        val prompt = Prompt(listOf(
            UserMessage("What is the capital of France?")
        ))
        val response = model.call(prompt)

        assertThat(response).isNotNull()
        assertThat(response.result.output.text).isNotEmpty()
    }

    @Test
    fun `when EOS is the first generated token call returns immediately with empty generated text`() {
        val backend = TestGenerationBackend(longArrayOf())  // No tokens before EOS
        val model = createModel(backend)

        val prompt = Prompt(listOf(
            UserMessage("Test")
        ))
        val response = model.call(prompt)

        assertThat(response).isNotNull()
        // EOS as first token means no new tokens were generated — no infinite loop
        assertThat(backend.decodeCallCount).isEqualTo(1)
    }

    @Test
    fun `when input tokens exceed 32768 a warning is written to stderr and generation proceeds`() {
        // Backend reports a tiny context limit to force truncation warning
        val backend = object : TestGenerationBackend(longArrayOf(1000L)) {
            override val maxContextTokens: Int = 5

            // Override tokenize to always return more tokens than the limit
            override fun tokenize(text: String): LongArray =
                LongArray(10) { (1000L + it) }  // 10 tokens, exceeds limit of 5
        }
        val model = createModel(backend)

        val stderr = ByteArrayOutputStream()
        val originalErr = System.err
        System.setErr(PrintStream(stderr))
        try {
            val prompt = Prompt(listOf(
                UserMessage("Some very long text that exceeds the context limit")
            ))
            val response = model.call(prompt)

            // Should proceed with generation despite truncation
            assertThat(response).isNotNull()
        } finally {
            System.setErr(originalErr)
        }

        val stderrOutput = stderr.toString()
        assertThat(stderrOutput).contains("context")
    }

    @Test
    fun `generation stops after maxNewTokens even if EOS is not reached`() {
        // Backend returns tokens indefinitely; model should stop at maxNewTokens
        val infiniteTokens = LongArray(500) { 1000L }  // 500 tokens, more than maxNewTokens=256
        val backend = TestGenerationBackend(infiniteTokens)
        val model = createModel(backend)

        val prompt = Prompt(listOf(
            UserMessage("Test")
        ))
        val response = model.call(prompt)

        // Should stop at 256 new tokens (maxNewTokens)
        assertThat(backend.decodeCallCount).isLessThanOrEqualTo(257)  // 256 + possible final EOS check
        assertThat(response.result.output.text).isNotEmpty()
    }

    @Test
    fun `generation stops early when same single token repeats beyond threshold`() {
        // Backend emits the same token 500 times — a single-token repetition loop
        val loopingTokens = LongArray(500) { 1000L }
        val backend = TestGenerationBackend(loopingTokens)
        val model = createModel(backend)

        val response = model.call(Prompt(listOf(UserMessage("Test"))))

        // Must stop well before maxNewTokens (256) due to repetition detection
        assertThat(backend.decodeCallCount).isLessThan(30)
        assertThat(response).isNotNull()
    }

    @Test
    fun `generation stops early when two tokens alternate in a loop`() {
        // Backend alternates between token 1000 and 1001 forever — a 2-token ping-pong loop
        val pingPongTokens = LongArray(500) { i -> if (i % 2 == 0) 1000L else 1001L }
        val backend = TestGenerationBackend(pingPongTokens)
        val model = createModel(backend)

        val response = model.call(Prompt(listOf(UserMessage("Test"))))

        assertThat(backend.decodeCallCount).isLessThan(30)
        assertThat(response).isNotNull()
    }

    @Test
    fun `generation stops early when a multi-token phrase repeats in a loop`() {
        // Simulates "addEventListener" + "er" + "er" repeating — a 4-token cycle
        val phraseTokens = LongArray(500) { i -> (1000L + (i % 4)) }
        val backend = TestGenerationBackend(phraseTokens)
        val model = createModel(backend)

        val response = model.call(Prompt(listOf(UserMessage("Test"))))

        assertThat(backend.decodeCallCount).isLessThan(40)
        assertThat(response).isNotNull()
    }

    @Test
    fun `given ZEPHYR format prompt uses pipe-tag headers and not im_start`() {
        var capturedText: String? = null
        val backend = object : TestGenerationBackend(longArrayOf()) {
            override fun tokenize(text: String): LongArray {
                capturedText = text
                return LongArray(5)
            }
        }
        val model = createModel(backend, OnnxChatModel.TemplateFormat.ZEPHYR)

        model.call(Prompt(listOf(
            SystemMessage("Be helpful."),
            UserMessage("Hi")
        )))

        assertThat(capturedText).contains("<|system|>")
        assertThat(capturedText).contains("<|user|>")
        assertThat(capturedText).contains("<|assistant|>")
        assertThat(capturedText).doesNotContain("<|im_start|>")
    }

    @Test
    fun `parseKvConfig extracts num_key_value_heads and head_dim from config json`() {
        val json = """{"hidden_size": 2048, "num_attention_heads": 32, "num_key_value_heads": 4}"""
        val (kvHeads, headDim) = OnnxChatModel.parseKvConfig(json)
        assertThat(kvHeads).isEqualTo(4)
        assertThat(headDim).isEqualTo(64)
    }

    @Test
    fun `parseKvConfig falls back to num_attention_heads when num_key_value_heads is absent`() {
        val json = """{"hidden_size": 1024, "num_attention_heads": 16}"""
        val (kvHeads, headDim) = OnnxChatModel.parseKvConfig(json)
        assertThat(kvHeads).isEqualTo(16)
        assertThat(headDim).isEqualTo(64)
    }

    @Test
    fun `parseEosTokenId extracts eos_token_id from config json`() {
        val json = """{"eos_token_id": 2, "bos_token_id": 1}"""
        assertThat(OnnxChatModel.parseEosTokenId(json)).isEqualTo(2L)
    }

    @Test
    fun `parseEosTokenId returns null when eos_token_id is absent`() {
        val json = """{"bos_token_id": 1}"""
        assertThat(OnnxChatModel.parseEosTokenId(json)).isNull()
    }

    @Test
    fun `parseMaxContextTokens extracts max_position_embeddings from config json`() {
        val json = """{"max_position_embeddings": 2048}"""
        assertThat(OnnxChatModel.parseMaxContextTokens(json)).isEqualTo(2048)
    }

    @Test
    fun `parseMaxContextTokens returns null when max_position_embeddings is absent`() {
        val json = """{"hidden_size": 512}"""
        assertThat(OnnxChatModel.parseMaxContextTokens(json)).isNull()
    }

    @Test
    fun `detectTemplateFormat returns CHATML for template containing im_start`() {
        val template = "{% for message in messages %}{{ '<|im_start|>' + message['role'] }}"
        assertThat(OnnxChatModel.detectTemplateFormat(template))
            .isEqualTo(OnnxChatModel.TemplateFormat.CHATML)
    }

    @Test
    fun `detectTemplateFormat returns ZEPHYR for template containing pipe-user tag`() {
        val template = "{% if message['role'] == 'user' %}{{ '<|user|>' + message['content'] }}"
        assertThat(OnnxChatModel.detectTemplateFormat(template))
            .isEqualTo(OnnxChatModel.TemplateFormat.ZEPHYR)
    }

    @Test
    fun `system prompt is included in the input passed to backend`() {
        val capturedInputs = mutableListOf<LongArray>()
        val backend = object : TestGenerationBackend(longArrayOf(1000L)) {
            override fun nextToken(inputIds: LongArray): Long {
                capturedInputs.add(inputIds.copyOf())
                return super.nextToken(inputIds)
            }

            override fun tokenize(text: String): LongArray {
                // Map text to predictable token IDs based on content
                return if (text.contains("custom-system")) {
                    longArrayOf(9999L)  // Special marker for system prompt
                } else {
                    longArrayOf(1000L)
                }
            }
        }
        val model = createModel(backend)

        val prompt = Prompt(listOf(
            SystemMessage("custom-system prompt"),
            UserMessage("user question")
        ))
        model.call(prompt)

        // The first call to nextToken receives the full formatted input
        assertThat(capturedInputs).isNotEmpty()
        // The input should contain the system prompt token (9999)
        val firstInput = capturedInputs.first()
        assertThat(firstInput).contains(9999L)
    }
}
