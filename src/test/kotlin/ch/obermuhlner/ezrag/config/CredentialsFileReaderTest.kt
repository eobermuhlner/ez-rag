package ch.obermuhlner.ezrag.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

class CredentialsFileReaderTest {

    private val noOpWriter = PrintWriter(StringWriter())

    @Test
    fun `returns null for non-existent path`(@TempDir tempDir: Path) {
        val path = tempDir.resolve("nonexistent-credentials.yml")
        val result = CredentialsFileReader(noOpWriter).read(path.toString())
        assertThat(result).isNull()
    }

    @Test
    fun `parses openai-api-key in kebab-case`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("credentials.yml")
        file.toFile().writeText("openai-api-key: sk-test-kebab\n")
        setPosixPermissions(file, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))

        val result = CredentialsFileReader(noOpWriter).read(file.toString())

        assertThat(result).isNotNull
        assertThat(result!!.openaiApiKey).isEqualTo("sk-test-kebab")
    }

    @Test
    fun `parses anthropic-api-key in kebab-case`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("credentials.yml")
        file.toFile().writeText("anthropic-api-key: sk-ant-test-kebab\n")
        setPosixPermissions(file, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))

        val result = CredentialsFileReader(noOpWriter).read(file.toString())

        assertThat(result).isNotNull
        assertThat(result!!.anthropicApiKey).isEqualTo("sk-ant-test-kebab")
    }

    @Test
    fun `parses openaiApiKey in camelCase as fallback`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("credentials.yml")
        file.toFile().writeText("openaiApiKey: sk-test-camel\n")
        setPosixPermissions(file, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))

        val result = CredentialsFileReader(noOpWriter).read(file.toString())

        assertThat(result).isNotNull
        assertThat(result!!.openaiApiKey).isEqualTo("sk-test-camel")
    }

    @Test
    fun `parses anthropicApiKey in camelCase as fallback`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("credentials.yml")
        file.toFile().writeText("anthropicApiKey: sk-ant-test-camel\n")
        setPosixPermissions(file, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))

        val result = CredentialsFileReader(noOpWriter).read(file.toString())

        assertThat(result).isNotNull
        assertThat(result!!.anthropicApiKey).isEqualTo("sk-ant-test-camel")
    }

    @Test
    fun `emits permission warning when file is group-readable`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("credentials.yml")
        file.toFile().writeText("openai-api-key: sk-test\n")
        setPosixPermissions(file, setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ
        ))

        val warningWriter = StringWriter()
        CredentialsFileReader(PrintWriter(warningWriter)).read(file.toString())

        assertThat(warningWriter.toString()).isNotBlank()
        assertThat(warningWriter.toString()).containsIgnoringCase("permission")
    }

    @Test
    fun `emits permission warning when file is world-readable`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("credentials.yml")
        file.toFile().writeText("openai-api-key: sk-test\n")
        setPosixPermissions(file, setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OTHERS_READ
        ))

        val warningWriter = StringWriter()
        CredentialsFileReader(PrintWriter(warningWriter)).read(file.toString())

        assertThat(warningWriter.toString()).isNotBlank()
    }

    @Test
    fun `emits no warning when file permissions are 0600`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("credentials.yml")
        file.toFile().writeText("openai-api-key: sk-test\n")
        setPosixPermissions(file, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))

        val warningWriter = StringWriter()
        CredentialsFileReader(PrintWriter(warningWriter)).read(file.toString())

        assertThat(warningWriter.toString()).isEmpty()
    }

    private fun setPosixPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (e: UnsupportedOperationException) {
            // Non-POSIX filesystem, skip
        }
    }
}
