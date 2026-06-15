package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

class DirectoryWalkerTest {

    @Test
    fun `walk returns only supported extensions sorted alphabetically`(@TempDir tempDir: Path) {
        // Create files of supported and unsupported types
        tempDir.resolve("alpha.txt").toFile().writeText("text file")
        tempDir.resolve("beta.md").toFile().writeText("# Markdown")
        tempDir.resolve("gamma.pdf").toFile().writeBytes(createMinimalPdfBytes())
        tempDir.resolve("delta.xyz").toFile().writeText("unsupported")

        val walker = DirectoryWalker()
        val paths = walker.walk(tempDir)

        // Should return exactly the 3 supported files
        assertThat(paths).hasSize(3)
        val names = paths.map { it.fileName.toString() }
        assertThat(names).containsExactly("alpha.txt", "beta.md", "gamma.pdf")
    }

    @Test
    fun `walk emits a warning for each unsupported file`(@TempDir tempDir: Path) {
        tempDir.resolve("valid.txt").toFile().writeText("text")
        tempDir.resolve("bad.xyz").toFile().writeText("unsupported")
        tempDir.resolve("bad2.csv").toFile().writeText("also unsupported")

        val warningOutput = StringWriter()
        val walker = DirectoryWalker(PrintWriter(warningOutput))
        walker.walk(tempDir)

        val warnings = warningOutput.toString()
        assertThat(warnings).contains("bad.xyz")
        assertThat(warnings).contains("bad2.csv")
    }

    @Test
    fun `walk recurses into subdirectories`(@TempDir tempDir: Path) {
        val subDir = tempDir.resolve("sub")
        subDir.toFile().mkdirs()
        tempDir.resolve("root.txt").toFile().writeText("root text")
        subDir.resolve("nested.md").toFile().writeText("# Nested")

        val walker = DirectoryWalker()
        val paths = walker.walk(tempDir)

        assertThat(paths).hasSize(2)
    }

    private fun createMinimalPdfBytes(): ByteArray {
        // Minimal valid PDF bytes (enough to be a file, but not necessarily parseable by PDF reader)
        return "%PDF-1.4\n%%EOF\n".toByteArray()
    }
}
