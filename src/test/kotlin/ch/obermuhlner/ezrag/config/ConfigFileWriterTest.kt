package ch.obermuhlner.ezrag.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ConfigFileWriterTest {

    @Test
    fun `round-trip write and read returns same values`(@TempDir tempDir: Path) {
        val configPath = tempDir.resolve("config.yml")
        val writer = ConfigFileWriter()

        writer.write(configPath, mapOf("embeddingProvider" to "openai", "embeddingModel" to "text-embedding-3-small"))

        val raw = readConfigRaw(configPath.toString())
        assertThat(raw).isNotNull()
        assertThat(raw!!["embeddingProvider"]).isEqualTo("openai")
        assertThat(raw["embeddingModel"]).isEqualTo("text-embedding-3-small")
    }

    @Test
    fun `merge-write preserves existing keys not in new map`(@TempDir tempDir: Path) {
        val configPath = tempDir.resolve("config.yml")
        val writer = ConfigFileWriter()

        writer.write(configPath, mapOf("embeddingProvider" to "onnx"))
        writer.write(configPath, mapOf("chunkSize" to 500))

        val raw = readConfigRaw(configPath.toString())
        assertThat(raw).isNotNull()
        assertThat(raw!!["embeddingProvider"]).isEqualTo("onnx")
        assertThat(raw["chunkSize"]).isEqualTo(500)
    }

    @Test
    fun `write overwrites existing value for same key`(@TempDir tempDir: Path) {
        val configPath = tempDir.resolve("config.yml")
        val writer = ConfigFileWriter()

        writer.write(configPath, mapOf("embeddingProvider" to "onnx"))
        writer.write(configPath, mapOf("embeddingProvider" to "openai"))

        val raw = readConfigRaw(configPath.toString())
        assertThat(raw).isNotNull()
        assertThat(raw!!["embeddingProvider"]).isEqualTo("openai")
    }

    @Test
    fun `write creates file if it does not exist`(@TempDir tempDir: Path) {
        val configPath = tempDir.resolve("subdir").resolve("config.yml")
        configPath.parent.toFile().mkdirs()
        val writer = ConfigFileWriter()

        writer.write(configPath, mapOf("chunkOverlap" to 50))

        assertThat(configPath.toFile().exists()).isTrue()
        val raw = readConfigRaw(configPath.toString())
        assertThat(raw!!["chunkOverlap"]).isEqualTo(50)
    }
}
