package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
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

class ListCommandTest {

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

    private fun buildStore(storeDir: Path, docs: List<Pair<String, Long>>): VectorStoreRepository {
        val repo = VectorStoreRepository(fakeEmbeddingModel, storeDir)
        repo.load()
        for ((source, mtime) in docs) {
            repo.add(listOf(
                Document.builder()
                    .text("Content of $source")
                    .metadata(mapOf("source" to source, "mtime" to mtime))
                    .build()
            ))
        }
        repo.save()
        return repo
    }

    @Test
    fun `list text output contains one line per document with chunk count`(@TempDir tempDir: Path) {
        val fileA = tempDir.resolve("a.txt").toAbsolutePath().toString()
        val fileB = tempDir.resolve("b.txt").toAbsolutePath().toString()
        buildStore(tempDir, listOf(fileA to 1000L, fileB to 2000L))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ListCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        // Use a probe that returns the stored mtime (files are "fresh")
        // The probe takes a path (String) and returns the current mtime (Long?)
        // Returning the same stored mtime simulates a fresh (non-stale) file
        cmd.filesystemProbeOverride = { path ->
            when (path) {
                fileA -> 1000L
                fileB -> 2000L
                else -> null
            }
        }
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        assertThat(output).contains("(1 chunks)")
    }

    @Test
    fun `list text output shows STALE suffix for stale documents only`(@TempDir tempDir: Path) {
        val freshFile = tempDir.resolve("fresh.txt").toAbsolutePath().toString()
        val staleFile = tempDir.resolve("stale.txt").toAbsolutePath().toString()
        buildStore(tempDir, listOf(freshFile to 1000L, staleFile to 2000L))

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ListCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        // Probe: freshFile returns same mtime (1000L), staleFile returns null (missing)
        cmd.filesystemProbeOverride = { path ->
            when (path) {
                freshFile -> 1000L
                else -> null
            }
        }
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val output = out.toString()
        val lines = output.lines().filter { it.isNotBlank() }
        val freshLine = lines.first { it.contains("fresh.txt") }
        val staleLine = lines.first { it.contains("stale.txt") }
        assertThat(freshLine).doesNotContain("[STALE]")
        assertThat(staleLine).contains("[STALE]")
    }

    @Test
    fun `list text output is sorted alphabetically by path`(@TempDir tempDir: Path) {
        val fileZ = tempDir.resolve("z-file.txt").toAbsolutePath().toString()
        val fileA = tempDir.resolve("a-file.txt").toAbsolutePath().toString()
        buildStore(tempDir, listOf(fileZ to 1000L, fileA to 2000L))

        val out = StringWriter()
        val cmd = ListCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.filesystemProbeOverride = { _ -> null } // all stale, but order matters
        cmd.call()

        val output = out.toString()
        val posA = output.indexOf("a-file.txt")
        val posZ = output.indexOf("z-file.txt")
        assertThat(posA).isGreaterThanOrEqualTo(0)
        assertThat(posZ).isGreaterThanOrEqualTo(0)
        assertThat(posA).isLessThan(posZ)
    }

    @Test
    fun `list JSON output contains path, chunks, and stale fields`(@TempDir tempDir: Path) {
        val fileA = tempDir.resolve("a.txt").toAbsolutePath().toString()
        buildStore(tempDir, listOf(fileA to 1000L))

        val out = StringWriter()
        val cmd = ListCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.filesystemProbeOverride = { _ -> 1000L } // fresh: returns stored mtime
        cmd.outputFormat = "json"
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(0)
        val json = out.toString().trim()
        val mapper = ObjectMapper()
        val node = mapper.readTree(json)
        assertThat(node.isArray).isTrue()
        assertThat(node.size()).isGreaterThanOrEqualTo(1)
        val first = node[0]
        assertThat(first.has("path")).isTrue()
        assertThat(first.get("path").asText()).startsWith("/") // absolute path
        assertThat(first.has("chunks")).isTrue()
        assertThat(first.get("chunks").asInt()).isGreaterThanOrEqualTo(1)
        assertThat(first.has("stale")).isTrue()
        assertThat(first.get("stale").asBoolean()).isFalse()
    }

    @Test
    fun `list JSON output marks stale documents`(@TempDir tempDir: Path) {
        val fileA = tempDir.resolve("a.txt").toAbsolutePath().toString()
        buildStore(tempDir, listOf(fileA to 1000L))

        val out = StringWriter()
        val cmd = ListCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.filesystemProbeOverride = { _ -> null } // missing file → stale
        cmd.outputFormat = "json"
        cmd.call()

        val json = out.toString().trim()
        val mapper = ObjectMapper()
        val node = mapper.readTree(json)
        assertThat(node[0].get("stale").asBoolean()).isTrue()
    }

    @Test
    fun `list exits with code 1 and error message when no store exists`(@TempDir tempDir: Path) {
        val nonExistentDir = tempDir.resolve("nonexistent")

        val out = StringWriter()
        val err = StringWriter()
        val cmd = ListCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = nonExistentDir,
            outputWriter = PrintWriter(out, true),
            errorWriter = PrintWriter(err, true),
        )
        val exitCode = cmd.call()

        assertThat(exitCode).isEqualTo(1)
        val combined = out.toString() + err.toString()
        assertThat(combined).contains(nonExistentDir.resolve("vector-store.json").toAbsolutePath().toString())
        assertThat(combined).contains("ez-rag ingest")
    }

    @Test
    fun `list text output shows absolute path when document is outside CWD`(@TempDir tempDir: Path) {
        val fileA = tempDir.resolve("a.txt").toAbsolutePath().toString()
        buildStore(tempDir, listOf(fileA to 1000L))

        val cwd = java.nio.file.Paths.get("").toAbsolutePath()
        // Skip if tempDir happens to be under CWD (unlikely but guard anyway)
        org.junit.jupiter.api.Assumptions.assumeTrue(!java.nio.file.Paths.get(fileA).startsWith(cwd))

        val out = StringWriter()
        val cmd = ListCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.filesystemProbeOverride = { _ -> 1000L }
        cmd.call()

        val output = out.toString()
        assertThat(output).contains(fileA)
        assertThat(output.lines().filter { it.isNotBlank() }).allSatisfy { line ->
            assertThat(line).doesNotStartWith("..")
        }
    }

    @Test
    fun `list JSON output uses absolute paths`(@TempDir tempDir: Path) {
        val fileA = tempDir.resolve("a.txt").toAbsolutePath().toString()
        buildStore(tempDir, listOf(fileA to 1000L))

        val out = StringWriter()
        val cmd = ListCommand(
            embeddingModel = fakeEmbeddingModel,
            storeDirOverride = tempDir,
            outputWriter = PrintWriter(out, true),
        )
        cmd.filesystemProbeOverride = { _ -> 1000L }
        cmd.outputFormat = "json"
        cmd.call()

        val json = out.toString().trim()
        val mapper = ObjectMapper()
        val node = mapper.readTree(json)
        assertThat(node[0].get("path").asText()).isEqualTo(fileA)
    }
}
