package ch.obermuhlner.ezrag.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ConfigFileReaderTest {

    @Test
    fun `model defaults to empty string when key is absent from config file`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("config.yml").toFile()
        configFile.writeText("provider: openai\n")

        val config = readConfigFile(configFile.absolutePath)

        assertThat(config).isNotNull()
        assertThat(config!!.model).isEqualTo("")
    }

    @Test
    fun `rerankModel defaults to cross-encoder model when key is absent from config file`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("config.yml").toFile()
        configFile.writeText("provider: openai\n")

        val config = readConfigFile(configFile.absolutePath)

        assertThat(config).isNotNull()
        assertThat(config!!.rerankModel).isEqualTo("cross-encoder/ms-marco-MiniLM-L-6-v2")
    }

    @Test
    fun `rerankModel is empty string when explicitly set to empty in config file`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("config.yml").toFile()
        configFile.writeText("rerank-model: \"\"\n")

        val config = readConfigFile(configFile.absolutePath)

        assertThat(config).isNotNull()
        assertThat(config!!.rerankModel).isEqualTo("")
    }

    @Test
    fun `rerankModel uses config file value when set`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("config.yml").toFile()
        configFile.writeText("rerank-model: cross-encoder/ms-marco-MiniLM-L-6-v2\n")

        val config = readConfigFile(configFile.absolutePath)

        assertThat(config).isNotNull()
        assertThat(config!!.rerankModel).isEqualTo("cross-encoder/ms-marco-MiniLM-L-6-v2")
    }

    @Test
    fun `search-mode kebab-case is parsed and produces searchMode == bm25`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("config.yml").toFile()
        configFile.writeText("search-mode: bm25\n")

        val config = readConfigFile(configFile.absolutePath)

        assertThat(config).isNotNull()
        assertThat(config!!.searchMode).isEqualTo("bm25")
    }

    @Test
    fun `analyzer is parsed and produces analyzer == english`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("config.yml").toFile()
        configFile.writeText("analyzer: english\n")

        val config = readConfigFile(configFile.absolutePath)

        assertThat(config).isNotNull()
        assertThat(config!!.analyzer).isEqualTo("english")
    }

    // ---- readConfigRaw tests ----

    @Test
    fun `readConfigRaw returns null when file does not exist`(@TempDir tempDir: Path) {
        val result = readConfigRaw(tempDir.resolve("nonexistent.yml").toString())
        assertThat(result).isNull()
    }

    @Test
    fun `readConfigRaw returns raw map when file exists`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("config.yml").toFile()
        configFile.writeText("provider: anthropic\nchunk-size: 500\n")

        val result = readConfigRaw(configFile.absolutePath)

        assertThat(result).isNotNull()
        assertThat(result!!["provider"]).isEqualTo("anthropic")
        assertThat(result["chunk-size"]).isEqualTo(500)
    }

    // ---- mergeConfigRaw tests ----

    @Test
    fun `mergeConfigRaw uses home field when local omits it`() {
        val home = mapOf("provider" to "anthropic", "chunk-size" to 500)
        val local = mapOf("search-mode" to "bm25")

        val result = mergeConfigRaw(home, local)

        assertThat(result).isNotNull()
        assertThat(result!!.provider).isEqualTo("anthropic")
        assertThat(result.chunkSize).isEqualTo(500)
    }

    @Test
    fun `mergeConfigRaw uses local field when both home and local set it`() {
        val home = mapOf("provider" to "anthropic")
        val local = mapOf("provider" to "openai")

        val result = mergeConfigRaw(home, local)

        assertThat(result).isNotNull()
        assertThat(result!!.provider).isEqualTo("openai")
    }

    @Test
    fun `mergeConfigRaw strips storeDir from local map`() {
        val home = mapOf("store-dir" to "/home/store")
        val local = mapOf("store-dir" to "/local/store")

        val result = mergeConfigRaw(home, local)

        assertThat(result).isNotNull()
        assertThat(result!!.storeDir).isEqualTo("/home/store")
    }

    @Test
    fun `mergeConfigRaw returns null when both inputs are null`() {
        val result = mergeConfigRaw(null, null)
        assertThat(result).isNull()
    }

    @Test
    fun `mergeConfigRaw returns defaults when both maps are empty`() {
        val result = mergeConfigRaw(emptyMap<String, Any>(), emptyMap<String, Any>())
        assertThat(result).isNotNull()
        assertThat(result!!.provider).isEqualTo("passthrough")
    }

    @Test
    fun `embeddingProvider defaults to provider when provider is openai and embeddingProvider is absent`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("config.yml").toFile()
        configFile.writeText("provider: openai\n")

        val config = readConfigFile(configFile.absolutePath)

        assertThat(config).isNotNull()
        assertThat(config!!.embeddingProvider).isEqualTo("openai")
    }

    @Test
    fun `embeddingModel defaults to text-embedding-3-small when provider is openai and embeddingModel is absent`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("config.yml").toFile()
        configFile.writeText("provider: openai\n")

        val config = readConfigFile(configFile.absolutePath)

        assertThat(config).isNotNull()
        assertThat(config!!.embeddingModel).isEqualTo("text-embedding-3-small")
    }

    @Test
    fun `embeddingProvider explicit override is respected when provider is openai`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("config.yml").toFile()
        configFile.writeText("provider: openai\nembedding-provider: onnx\n")

        val config = readConfigFile(configFile.absolutePath)

        assertThat(config).isNotNull()
        assertThat(config!!.embeddingProvider).isEqualTo("onnx")
    }

    @Test
    fun `embeddingProvider defaults to onnx when no provider is set`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("config.yml").toFile()
        configFile.writeText("chunk-size: 500\n")

        val config = readConfigFile(configFile.absolutePath)

        assertThat(config).isNotNull()
        assertThat(config!!.embeddingProvider).isEqualTo("onnx")
    }

    @Test
    fun `mergeConfigRaw derives embeddingProvider and embeddingModel from home provider when embeddingProvider is absent`() {
        val home = mapOf("provider" to "openai")
        val local = mapOf("search-mode" to "bm25")

        val result = mergeConfigRaw(home, local)

        assertThat(result).isNotNull()
        assertThat(result!!.embeddingProvider).isEqualTo("openai")
        assertThat(result.embeddingModel).isEqualTo("text-embedding-3-small")
    }

    @Test
    fun `resolveConfigSources sets localConfigPath to null when both paths point to the same file`(@TempDir tempDir: Path) {
        val configFile = tempDir.resolve("config.yml").toFile().also { it.writeText("provider: test\n") }
        val configPath = configFile.absolutePath

        val result = resolveConfigSources(configPath, configPath)

        assertThat(result.homeConfigPath).isEqualTo(configPath)
        assertThat(result.localConfigPath).isNull()
    }

    @Test
    fun `EzRagDirResolver locates local config when invoked two levels below the project dir`(@TempDir tempDir: Path) {
        val ezRagDir = tempDir.resolve(".ez-rag").toFile().also { it.mkdirs() }
        val localConfig = ezRagDir.resolve("config.yml").also { it.writeText("provider: local-provider\n") }
        val subSubDir = tempDir.resolve("sub/sub2")
        subSubDir.toFile().mkdirs()

        val resolvedDir = EzRagDirResolver().resolve(subSubDir)
        val localRaw = readConfigRaw(resolvedDir.resolve("config.yml").toString())
        val config = mergeConfigRaw(null, localRaw)

        assertThat(localConfig.exists()).isTrue()
        assertThat(config).isNotNull()
        assertThat(config!!.provider).isEqualTo("local-provider")
    }
}
