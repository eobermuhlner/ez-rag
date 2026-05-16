package ch.obermuhlner.ezrag.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ConfigFileReaderTest {

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
}
