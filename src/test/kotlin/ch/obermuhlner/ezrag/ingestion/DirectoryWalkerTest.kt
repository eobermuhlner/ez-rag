package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path

class DirectoryWalkerTest {

    @Test
    fun `walk returns all files including those with unknown extensions`(@TempDir tempDir: Path) {
        // Create files of known and unknown types
        tempDir.resolve("alpha.txt").toFile().writeText("text file")
        tempDir.resolve("beta.md").toFile().writeText("# Markdown")
        tempDir.resolve("gamma.pdf").toFile().writeBytes(createMinimalPdfBytes())
        tempDir.resolve("delta.xyz").toFile().writeText("unsupported extension but still returned")

        val walker = DirectoryWalker()
        val paths = walker.walk(tempDir)

        // Should return ALL 4 files — walker no longer filters by extension
        assertThat(paths).hasSize(4)
        val names = paths.map { it.fileName.toString() }
        assertThat(names).containsExactly("alpha.txt", "beta.md", "delta.xyz", "gamma.pdf")
    }

    @Test
    fun `walk returns files with no extension`(@TempDir tempDir: Path) {
        tempDir.resolve("Makefile").toFile().writeText("all:\n\techo hello")
        tempDir.resolve("README.md").toFile().writeText("# Readme")

        val walker = DirectoryWalker()
        val paths = walker.walk(tempDir)

        val names = paths.map { it.fileName.toString() }
        assertThat(names).contains("Makefile")
        assertThat(names).contains("README.md")
    }

    @Test
    fun `walk includes html and htm files in results`(@TempDir tempDir: Path) {
        tempDir.resolve("page.html").toFile().writeText("<html><body><p>html content</p></body></html>")
        tempDir.resolve("page.htm").toFile().writeText("<html><body><p>htm content</p></body></html>")
        tempDir.resolve("extra.yaml").toFile().writeText("key: value")

        val walker = DirectoryWalker()
        val paths = walker.walk(tempDir)

        val names = paths.map { it.fileName.toString() }
        assertThat(names).contains("page.html")
        assertThat(names).contains("page.htm")
        // yaml is now also returned (walker does not filter)
        assertThat(names).contains("extra.yaml")
    }

    @Test
    fun `walk includes rtf files in results`(@TempDir tempDir: Path) {
        tempDir.resolve("doc.rtf").toFile().writeText("{\\rtf1\\ansi rtf content}")
        tempDir.resolve("config.yaml").toFile().writeText("key: value")

        val walker = DirectoryWalker()
        val paths = walker.walk(tempDir)

        val names = paths.map { it.fileName.toString() }
        assertThat(names).contains("doc.rtf")
        // yaml now included too
        assertThat(names).contains("config.yaml")
    }

    @Test
    fun `walk does not emit warnings for any file extension`(@TempDir tempDir: Path) {
        tempDir.resolve("valid.txt").toFile().writeText("text")
        tempDir.resolve("unknown.xyz").toFile().writeText("unknown extension")

        val warningOutput = StringWriter()
        val walker = DirectoryWalker(PrintWriter(warningOutput))
        walker.walk(tempDir)

        // Walker no longer emits warnings for extension mismatches
        val warnings = warningOutput.toString()
        assertThat(warnings).isEmpty()
    }

    @Test
    fun `walk includes csv files in results`(@TempDir tempDir: Path) {
        tempDir.resolve("data.csv").toFile().writeText("col1,col2\nval1,val2")
        tempDir.resolve("config.yaml").toFile().writeText("key: value")

        val warningOutput = StringWriter()
        val walker = DirectoryWalker(PrintWriter(warningOutput))
        val paths = walker.walk(tempDir)

        val names = paths.map { it.fileName.toString() }
        assertThat(names).contains("data.csv")
        // yaml is also returned now
        assertThat(names).contains("config.yaml")

        // No warnings emitted for any files
        val warnings = warningOutput.toString()
        assertThat(warnings).isEmpty()
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
