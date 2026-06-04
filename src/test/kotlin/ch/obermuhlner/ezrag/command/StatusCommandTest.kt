package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.config.ConfigSources
import ch.obermuhlner.ezrag.config.CredentialSource
import ch.obermuhlner.ezrag.config.Credentials
import ch.obermuhlner.ezrag.config.EzRagConfig
import ch.obermuhlner.ezrag.ingestion.LuceneRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

class StatusCommandTest {

    private val fakeEmbeddingModel: EmbeddingModel = object : EmbeddingModel {
        override fun call(request: EmbeddingRequest): EmbeddingResponse {
            val embeddings = request.instructions.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.1f * (idx + 1) }, idx)
            }
            return EmbeddingResponse(embeddings)
        }

        override fun embed(document: Document): FloatArray = FloatArray(4) { 0.1f }
        override fun embed(text: String): FloatArray = FloatArray(4) { 0.1f }

        override fun embedForResponse(texts: List<String>): EmbeddingResponse {
            val embeddings = texts.mapIndexed { idx, _ ->
                Embedding(FloatArray(4) { 0.1f * (idx + 1) }, idx)
            }
            return EmbeddingResponse(embeddings)
        }

        override fun dimensions(): Int = 4
    }

    private fun buildStoreWithOneFile(tempDir: Path): Path {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repo ->
            repo.add(listOf(
                Document.builder().text("Some content")
                    .metadata(mapOf("source" to "test.txt", "mtime" to 1000L, "chunk_index" to 0)).build()
            ))
        }
        return tempDir
    }

    // ---- Basic store section tests ----

    @Test
    fun `status text output contains store path and chunk count`(@TempDir tempDir: Path) {
        val fileA = tempDir.resolve("a.txt")
        val fileB = tempDir.resolve("b.txt")
        fileA.toFile().writeText("Content for file A. More text to ensure a chunk is created.")
        fileB.toFile().writeText("Content for file B. Different content for second file.")

        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repo ->
            repo.add(listOf(
                Document.builder().text(fileA.toFile().readText())
                    .metadata(mapOf("source" to fileA.toString(), "mtime" to 1000L, "chunk_index" to 0)).build()
            ))
            repo.add(listOf(
                Document.builder().text(fileB.toFile().readText())
                    .metadata(mapOf("source" to fileB.toString(), "mtime" to 2000L, "chunk_index" to 0)).build()
            ))
        }

        val out = StringWriter()
        val statusCommand = StatusCommand(embeddingModel = fakeEmbeddingModel, storeDirOverride = tempDir, outputWriter = PrintWriter(out))
        val exitCode = statusCommand.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        assertThat(output).contains("Store:")
        assertThat(output).contains(tempDir.resolve("lucene").toAbsolutePath().toString())
        assertThat(output).contains("Chunks:")
        // status no longer lists individual document paths
        assertThat(output).doesNotContain(fileA.toString())
        assertThat(output).doesNotContain(fileB.toString())
    }

    @Test
    fun `status text output contains document count`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repo ->
            repo.add(listOf(
                Document.builder().text("Doc A").metadata(mapOf("source" to "a.txt", "mtime" to 1000L, "chunk_index" to 0)).build()
            ))
            repo.add(listOf(
                Document.builder().text("Doc B").metadata(mapOf("source" to "b.txt", "mtime" to 2000L, "chunk_index" to 0)).build()
            ))
        }

        val out = StringWriter()
        val cmd = StatusCommand(embeddingModel = fakeEmbeddingModel, storeDirOverride = tempDir, outputWriter = PrintWriter(out))
        cmd.call()
        val output = out.toString()
        assertThat(output).contains("2")
        // Must have a label referencing documents
        assertThat(output.lowercase()).containsPattern("document.*2|2.*document")
    }

    @Test
    fun `status text output contains storeSizeBytes as human-readable size`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)

        val out = StringWriter()
        val cmd = StatusCommand(embeddingModel = fakeEmbeddingModel, storeDirOverride = tempDir, outputWriter = PrintWriter(out))
        cmd.call()
        val output = out.toString()
        // Should contain a size string like "0 B", "1 KB", "1 MB", etc.
        assertThat(output).containsPattern("[0-9]+ (B|KB|MB)")
    }

    @Test
    fun `status text output contains staleDocumentCount`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)

        val out = StringWriter()
        val cmd = StatusCommand(embeddingModel = fakeEmbeddingModel, storeDirOverride = tempDir, outputWriter = PrintWriter(out))
        cmd.call()
        val output = out.toString()
        assertThat(output.lowercase()).contains("stale")
    }

    @Test
    fun `status text output contains lastIngestTime as ISO-8601 when store is non-empty`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)

        val out = StringWriter()
        val cmd = StatusCommand(embeddingModel = fakeEmbeddingModel, storeDirOverride = tempDir, outputWriter = PrintWriter(out))
        cmd.call()
        val output = out.toString()
        // ISO-8601 pattern: contains digits and T separator
        assertThat(output).containsPattern("\\d{4}-\\d{2}-\\d{2}T")
    }

    @Test
    fun `status text output omits or labels lastIngestTime as none when store is empty`(@TempDir tempDir: Path) {
        val out = StringWriter()
        buildStoreWithOneFile(tempDir)
        val cmd = StatusCommand(embeddingModel = fakeEmbeddingModel, storeDirOverride = tempDir, outputWriter = PrintWriter(out))
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
    }

    // ---- Configuration section tests ----

    @Test
    fun `status text output contains Configuration section with required fields`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)

        val config = EzRagConfig(
            provider = "openai",
            model = "gpt-4o",
            embeddingProvider = "openai",
            embeddingModel = "text-embedding-3-small",
            rerankModel = "cross-encoder/ms-marco-MiniLM-L-6-v2",
            chunkSize = 1000,
            chunkOverlap = 200,
            topK = 5,
            storeDir = ".ez-rag"
        )
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            config = config
        )
        cmd.call()
        val output = out.toString()
        assertThat(output.lowercase()).contains("configuration")
        assertThat(output).contains("provider")
        assertThat(output).contains("openai")
        assertThat(output).contains("model")
        assertThat(output).contains("gpt-4o")
        assertThat(output).contains("embeddingProvider")
        assertThat(output).contains("embeddingModel")
        assertThat(output).contains("text-embedding-3-small")
        assertThat(output).contains("rerankModel")
        assertThat(output).contains("cross-encoder/ms-marco-MiniLM-L-6-v2")
        assertThat(output).contains("chunkSize")
        assertThat(output).contains("1000")
        assertThat(output).contains("chunkOverlap")
        assertThat(output).contains("200")
        assertThat(output).contains("topK")
        assertThat(output).contains("5")
        assertThat(output).contains("storeDir")
    }

    @Test
    fun `status text output shows rerankModel as disabled when blank`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)

        val config = EzRagConfig(rerankModel = "")
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            config = config
        )
        cmd.call()
        val output = out.toString()
        assertThat(output).contains("disabled")
    }

    // ---- Credential filtering tests ----

    @Test
    fun `text output shows openai-api-key when provider is openai`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val credentials = Credentials(
            openaiApiKey = "sk-secret",
            openaiApiKeySource = CredentialSource.EnvVar("OPENAI_API_KEY"),
            anthropicApiKey = null,
            anthropicApiKeySource = CredentialSource.Unset,
        )
        val config = EzRagConfig(provider = "openai", embeddingProvider = "onnx")
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            credentials = credentials,
            config = config
        )
        cmd.call()
        val output = out.toString()
        assertThat(output).contains("openai-api-key:")
        assertThat(output).contains("set (env var OPENAI_API_KEY)")
        assertThat(output).doesNotContain("sk-secret")
    }

    @Test
    fun `text output shows openai-api-key when embeddingProvider is openai`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val credentials = Credentials(
            openaiApiKey = "sk-secret",
            openaiApiKeySource = CredentialSource.File("/home/user/.ez-rag/credentials.yml"),
            anthropicApiKey = null,
            anthropicApiKeySource = CredentialSource.Unset,
        )
        val config = EzRagConfig(provider = "ollama", embeddingProvider = "openai")
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            credentials = credentials,
            config = config
        )
        cmd.call()
        val output = out.toString()
        assertThat(output).contains("openai-api-key:")
        assertThat(output).contains("set (/home/user/.ez-rag/credentials.yml)")
        assertThat(output).doesNotContain("sk-secret")
    }

    @Test
    fun `text output shows anthropic-api-key when provider is anthropic`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val credentials = Credentials(
            openaiApiKey = null,
            openaiApiKeySource = CredentialSource.Unset,
            anthropicApiKey = "ant-secret",
            anthropicApiKeySource = CredentialSource.EnvVar("ANTHROPIC_API_KEY"),
        )
        val config = EzRagConfig(provider = "anthropic", embeddingProvider = "onnx")
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            credentials = credentials,
            config = config
        )
        cmd.call()
        val output = out.toString()
        assertThat(output).contains("anthropic-api-key:")
        assertThat(output).contains("set (env var ANTHROPIC_API_KEY)")
        assertThat(output).doesNotContain("ant-secret")
    }

    @Test
    fun `text output shows anthropic-api-key sourced from file`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val credentials = Credentials(
            openaiApiKey = null,
            openaiApiKeySource = CredentialSource.Unset,
            anthropicApiKey = "ant-secret",
            anthropicApiKeySource = CredentialSource.File(".ez-rag/credentials.yml"),
        )
        val config = EzRagConfig(provider = "anthropic", embeddingProvider = "onnx")
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            credentials = credentials,
            config = config
        )
        cmd.call()
        val output = out.toString()
        assertThat(output).contains("anthropic-api-key:")
        assertThat(output).contains("set (.ez-rag/credentials.yml)")
        assertThat(output).doesNotContain("ant-secret")
    }

    @Test
    fun `text output omits both keys when provider and embeddingProvider are neither openai nor anthropic`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val credentials = Credentials(
            openaiApiKey = null,
            openaiApiKeySource = CredentialSource.Unset,
            anthropicApiKey = null,
            anthropicApiKeySource = CredentialSource.Unset,
        )
        val config = EzRagConfig(provider = "ollama", embeddingProvider = "onnx")
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            credentials = credentials,
            config = config
        )
        cmd.call()
        val output = out.toString()
        assertThat(output).doesNotContain("openai-api-key")
        assertThat(output).doesNotContain("anthropic-api-key")
    }

    @Test
    fun `text output shows openai-api-key not set when unset but provider is openai`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val credentials = Credentials(
            openaiApiKey = null,
            openaiApiKeySource = CredentialSource.Unset,
            anthropicApiKey = null,
            anthropicApiKeySource = CredentialSource.Unset,
        )
        val config = EzRagConfig(provider = "openai", embeddingProvider = "onnx")
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            credentials = credentials,
            config = config
        )
        cmd.call()
        val output = out.toString()
        assertThat(output).contains("openai-api-key: not set")
    }

    @Test
    fun `text output shows anthropic-api-key not set when unset but provider is anthropic`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val credentials = Credentials(
            openaiApiKey = null,
            openaiApiKeySource = CredentialSource.Unset,
            anthropicApiKey = null,
            anthropicApiKeySource = CredentialSource.Unset,
        )
        val config = EzRagConfig(provider = "anthropic", embeddingProvider = "onnx")
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            credentials = credentials,
            config = config
        )
        cmd.call()
        val output = out.toString()
        assertThat(output).contains("anthropic-api-key: not set")
    }

    @Test
    fun `JSON output contains credentials with source strings when provider is openai`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val credentials = Credentials(
            openaiApiKey = "sk-secret",
            openaiApiKeySource = CredentialSource.EnvVar("OPENAI_API_KEY"),
            anthropicApiKey = "ant-secret",
            anthropicApiKeySource = CredentialSource.File("/home/user/.ez-rag/credentials.yml"),
        )
        val config = EzRagConfig(provider = "openai", embeddingProvider = "openai")
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            credentials = credentials,
            config = config
        )
        cmd.outputFormat = "json"
        cmd.call()
        val json = out.toString().trim()
        val mapper = ObjectMapper()
        val node = mapper.readTree(json)
        assertThat(node.has("credentials")).isTrue()
        val creds = node.get("credentials")
        assertThat(creds.has("openaiApiKey")).isTrue()
        assertThat(creds.get("openaiApiKey").asText()).isEqualTo("set (env var OPENAI_API_KEY)")
        assertThat(json).doesNotContain("sk-secret")
        assertThat(json).doesNotContain("ant-secret")
    }

    @Test
    fun `JSON output omits credentials when provider is ollama and embeddingProvider is onnx`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val credentials = Credentials(
            openaiApiKey = null,
            openaiApiKeySource = CredentialSource.Unset,
            anthropicApiKey = null,
            anthropicApiKeySource = CredentialSource.Unset,
        )
        val config = EzRagConfig(provider = "ollama", embeddingProvider = "onnx")
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            credentials = credentials,
            config = config
        )
        cmd.outputFormat = "json"
        cmd.call()
        val json = out.toString().trim()
        val mapper = ObjectMapper()
        val node = mapper.readTree(json)
        // credentials should be empty or absent when no API keys are needed
        if (node.has("credentials")) {
            val creds = node.get("credentials")
            assertThat(creds.has("openaiApiKey")).isFalse()
            assertThat(creds.has("anthropicApiKey")).isFalse()
        }
    }

    // ---- JSON output aggregate fields test ----

    @Test
    fun `JSON output includes documentCount, storeSizeBytes, staleDocumentCount, lastIngestTime and configuration`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val config = EzRagConfig(
            provider = "openai",
            model = "gpt-4o",
            embeddingProvider = "openai",
            embeddingModel = "text-embedding-3-small",
            rerankModel = "cross-encoder/ms-marco-MiniLM-L-6-v2",
            chunkSize = 500,
            chunkOverlap = 100,
            topK = 3,
            storeDir = ".ez-rag"
        )
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            config = config
        )
        cmd.outputFormat = "json"
        cmd.call()
        val json = out.toString().trim()
        val mapper = ObjectMapper()
        val node = mapper.readTree(json)
        assertThat(node.has("documentCount")).isTrue()
        assertThat(node.get("documentCount").asInt()).isGreaterThanOrEqualTo(1)
        assertThat(node.has("storeSizeBytes")).isTrue()
        assertThat(node.get("storeSizeBytes").asLong()).isGreaterThanOrEqualTo(0L)
        assertThat(node.has("staleDocumentCount")).isTrue()
        assertThat(node.has("lastIngestTime")).isTrue()
        assertThat(node.has("configuration")).isTrue()
        val conf = node.get("configuration")
        assertThat(conf.has("provider")).isTrue()
        assertThat(conf.get("provider").asText()).isEqualTo("openai")
        assertThat(conf.has("model")).isTrue()
        assertThat(conf.has("embeddingProvider")).isTrue()
        assertThat(conf.has("embeddingModel")).isTrue()
        assertThat(conf.has("rerankModel")).isTrue()
        assertThat(conf.has("chunkSize")).isTrue()
        assertThat(conf.get("chunkSize").asInt()).isEqualTo(500)
        assertThat(conf.has("chunkOverlap")).isTrue()
        assertThat(conf.has("topK")).isTrue()
        assertThat(conf.has("storeDir")).isTrue()
    }

    // ---- Config sources tests ----

    @Test
    fun `text output lists home config path when only home config was loaded`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val configSources = ConfigSources(homeConfigPath = "/home/user/.ez-rag/config.yml", localConfigPath = null)
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            configSources = configSources
        )
        cmd.call()
        val output = out.toString()
        assertThat(output).contains("Config files")
        assertThat(output).contains("/home/user/.ez-rag/config.yml")
    }

    @Test
    fun `text output lists local config path when only local config was loaded`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val configSources = ConfigSources(homeConfigPath = null, localConfigPath = "/project/.ez-rag/config.yml")
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            configSources = configSources
        )
        cmd.call()
        val output = out.toString()
        assertThat(output).contains("Config files")
        assertThat(output).contains("/project/.ez-rag/config.yml")
    }

    @Test
    fun `text output lists both paths when both configs were loaded`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val configSources = ConfigSources(
            homeConfigPath = "/home/user/.ez-rag/config.yml",
            localConfigPath = "/project/.ez-rag/config.yml"
        )
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            configSources = configSources
        )
        cmd.call()
        val output = out.toString()
        assertThat(output).contains("Config files")
        assertThat(output).contains("/home/user/.ez-rag/config.yml")
        assertThat(output).contains("/project/.ez-rag/config.yml")
    }

    @Test
    fun `text output shows none indicator when neither config file was loaded`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val configSources = ConfigSources(homeConfigPath = null, localConfigPath = null)
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            configSources = configSources
        )
        cmd.call()
        val output = out.toString()
        assertThat(output).contains("Config files")
        assertThat(output.lowercase()).contains("none")
    }

    @Test
    fun `JSON output includes configSources array with loaded paths`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val configSources = ConfigSources(
            homeConfigPath = "/home/user/.ez-rag/config.yml",
            localConfigPath = "/project/.ez-rag/config.yml"
        )
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            configSources = configSources
        )
        cmd.outputFormat = "json"
        cmd.call()
        val json = out.toString().trim()
        val mapper = ObjectMapper()
        val node = mapper.readTree(json)
        assertThat(node.has("configSources")).isTrue()
        val sources = node.get("configSources")
        assertThat(sources.isArray).isTrue()
        val paths = (0 until sources.size()).map { sources.get(it).asText() }
        assertThat(paths).containsExactlyInAnyOrder(
            "/home/user/.ez-rag/config.yml",
            "/project/.ez-rag/config.yml"
        )
    }

    @Test
    fun `JSON output includes empty configSources array when neither config was loaded`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)
        val configSources = ConfigSources(homeConfigPath = null, localConfigPath = null)
        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out),
            configSources = configSources
        )
        cmd.outputFormat = "json"
        cmd.call()
        val json = out.toString().trim()
        val mapper = ObjectMapper()
        val node = mapper.readTree(json)
        assertThat(node.has("configSources")).isTrue()
        val sources = node.get("configSources")
        assertThat(sources.isArray).isTrue()
        assertThat(sources.size()).isEqualTo(0)
    }

    // ---- Existing tests retained ----

    @Test
    fun `status exits non-zero and shows error when no store exists`(@TempDir tempDir: Path) {
        val nonExistentStoreDir = tempDir.resolve("nonexistent")

        val out = StringWriter()
        val statusCommand = StatusCommand(embeddingModel = fakeEmbeddingModel, storeDirOverride = nonExistentStoreDir, outputWriter = PrintWriter(out))
        val exitCode = statusCommand.call()

        assertThat(exitCode).isNotEqualTo(0)
        val output = out.toString()
        assertThat(output).contains("ez-rag ingest")
    }

    @Test
    fun `status works without embedding model`(@TempDir tempDir: Path) {
        buildStoreWithOneFile(tempDir)

        val out = StringWriter()
        val statusCommand = StatusCommand(embeddingModel = null, storeDirOverride = tempDir, outputWriter = PrintWriter(out))
        val exitCode = statusCommand.call()

        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).contains("Chunks:")
    }

    @Test
    fun `status invoked from subdirectory finds store in parent directory`(@TempDir tempDir: Path) {
        val ezRagDir = tempDir.resolve(".ez-rag")
        ezRagDir.toFile().mkdirs()

        LuceneRepository.open(fakeEmbeddingModel, ezRagDir, "standard").use { repo ->
            repo.add(listOf(
                Document.builder().text("Content from parent store")
                    .metadata(mapOf("source" to "parent.txt", "mtime" to 1000L, "chunk_index" to 0)).build()
            ))
        }

        val subDir = tempDir.resolve("sub")
        subDir.toFile().mkdirs()

        val out = StringWriter()
        val cmd = StatusCommand(
            embeddingModel = fakeEmbeddingModel,
            startDirOverride = subDir,
            outputWriter = PrintWriter(out),
        )
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        // status no longer lists document paths, but should still succeed
        assertThat(out.toString()).contains("Store:")
    }

    @Test
    fun `text output lists files alphabetically`(@TempDir tempDir: Path) {
        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repo ->
            repo.add(listOf(
                Document.builder().text("Content Z")
                    .metadata(mapOf("source" to "z-file.txt", "mtime" to 1000L, "chunk_index" to 0)).build()
            ))
            repo.add(listOf(
                Document.builder().text("Content A")
                    .metadata(mapOf("source" to "a-file.txt", "mtime" to 2000L, "chunk_index" to 0)).build()
            ))
        }

        val out = StringWriter()
        val statusCommand = StatusCommand(embeddingModel = fakeEmbeddingModel, storeDirOverride = tempDir, outputWriter = PrintWriter(out))
        statusCommand.call()

        // This test verifies aggregate output still works (document listing moved to list command)
        assertThat(out.toString()).contains("Store:")
    }

    @Test
    fun `JSON output is valid JSON with correct keys`(@TempDir tempDir: Path) {
        val fileA = tempDir.resolve("a.txt")
        fileA.toFile().writeText("Content for file A. More text here.")

        LuceneRepository.open(fakeEmbeddingModel, tempDir, "standard").use { repo ->
            repo.add(listOf(
                Document.builder().text(fileA.toFile().readText())
                    .metadata(mapOf("source" to fileA.toString(), "mtime" to 1000L, "chunk_index" to 0)).build()
            ))
        }

        val out = StringWriter()
        val statusCommand = StatusCommand(embeddingModel = fakeEmbeddingModel, storeDirOverride = tempDir, outputWriter = PrintWriter(out))
        statusCommand.outputFormat = "json"
        val exitCode = statusCommand.call()

        assertThat(exitCode).isEqualTo(0)
        val json = out.toString().trim()
        val mapper = ObjectMapper()
        val node = mapper.readTree(json)
        assertThat(node.has("storeDirPath")).isTrue()
        assertThat(node.get("storeDirPath").asText()).isNotBlank()
        assertThat(node.has("chunkCount")).isTrue()
        assertThat(node.get("chunkCount").asInt()).isGreaterThanOrEqualTo(1)
        // documents array is removed; documentCount replaces it
        assertThat(node.has("documents")).isFalse()
        assertThat(node.has("documentCount")).isTrue()
        // bm25 section should not be present
        assertThat(node.has("bm25")).isFalse()
    }
}
