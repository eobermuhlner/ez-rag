package ch.obermuhlner.ezrag.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EzRagDirResolverTest {

    private val resolver = EzRagDirResolver()

    @Test
    fun `returns ez-rag in start directory when it exists there`(@TempDir tempDir: Path) {
        val ezRagDir = tempDir.resolve(".ez-rag")
        ezRagDir.toFile().mkdirs()

        val result = resolver.resolve(tempDir)

        assertThat(result).isEqualTo(ezRagDir)
    }

    @Test
    fun `finds ez-rag in a parent directory when not in start dir`(@TempDir tempDir: Path) {
        val ezRagDir = tempDir.resolve(".ez-rag")
        ezRagDir.toFile().mkdirs()

        val childDir = tempDir.resolve("child")
        childDir.toFile().mkdirs()

        val result = resolver.resolve(childDir)

        assertThat(result).isEqualTo(ezRagDir)
    }

    @Test
    fun `finds ez-rag in a grandparent directory`(@TempDir tempDir: Path) {
        val ezRagDir = tempDir.resolve(".ez-rag")
        ezRagDir.toFile().mkdirs()

        val childDir = tempDir.resolve("child")
        childDir.toFile().mkdirs()
        val grandchildDir = childDir.resolve("grandchild")
        grandchildDir.toFile().mkdirs()

        val result = resolver.resolve(grandchildDir)

        assertThat(result).isEqualTo(ezRagDir)
    }

    @Test
    fun `falls back to ez-rag in start directory when no ez-rag exists anywhere`(@TempDir tempDir: Path) {
        val childDir = tempDir.resolve("child")
        childDir.toFile().mkdirs()

        val result = resolver.resolve(childDir)

        assertThat(result).isEqualTo(childDir.resolve(".ez-rag"))
    }

    @Test
    fun `does not treat a ez-rag file (not a directory) as a valid store directory`(@TempDir tempDir: Path) {
        // Create .ez-rag as a file, not a directory
        val ezRagFile = tempDir.resolve(".ez-rag")
        ezRagFile.toFile().writeText("not a directory")

        val childDir = tempDir.resolve("child")
        childDir.toFile().mkdirs()

        // Should not pick up the .ez-rag file in tempDir; should fall back to childDir/.ez-rag
        val result = resolver.resolve(childDir)

        assertThat(result).isEqualTo(childDir.resolve(".ez-rag"))
    }

    @Test
    fun `terminates without error when start directory is the filesystem root`() {
        val root = Path.of("/")

        // Should not throw; returns root/.ez-rag as fallback
        val result = resolver.resolve(root)

        assertThat(result).isEqualTo(root.resolve(".ez-rag"))
    }
}
