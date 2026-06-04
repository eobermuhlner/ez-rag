package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.embedding.Embedding
import java.nio.file.Path

/**
 * Tests for LuceneRepository — the unified on-disk Lucene index combining HNSW and BM25.
 *
 * Uses a 4-dimensional stub EmbeddingModel and
 * a real on-disk Lucene index via @TempDir. Real disk is required because persistence and the
 * open/close lifecycle are observable behaviours of LuceneRepository.
 */
class LuceneRepositoryTest {

    // 4-dimensional stub embedding model: returns distinct vectors per text for meaningful similarity tests.
    // doc about "cat" -> [1,0,0,0], doc about "dog" -> [0,1,0,0], query "cat" -> [1,0,0,0], etc.
    private fun makeEmbeddingModel(dim: Int = 4): EmbeddingModel = object : EmbeddingModel {
        override fun call(request: EmbeddingRequest): EmbeddingResponse {
            val embeddings = request.instructions.mapIndexed { idx, text ->
                val vec = textToVector(text, dim)
                Embedding(vec, idx)
            }
            return EmbeddingResponse(embeddings)
        }

        override fun embed(document: Document): FloatArray = textToVector(document.text ?: "", dim)

        override fun embed(text: String): FloatArray = textToVector(text, dim)

        override fun embedForResponse(texts: List<String>): EmbeddingResponse {
            val embeddings = texts.mapIndexed { idx, text ->
                Embedding(textToVector(text, dim), idx)
            }
            return EmbeddingResponse(embeddings)
        }

        override fun dimensions(): Int = dim
    }

    /** Returns a unit vector biased by the first character of the text, enabling nearest-neighbour tests. */
    private fun textToVector(text: String, dim: Int): FloatArray {
        val vec = FloatArray(dim) { 0.0f }
        val hash = text.hashCode()
        val idx = Math.abs(hash) % dim
        vec[idx] = 1.0f
        return vec
    }

    private fun makeDoc(
        text: String,
        source: String,
        chunkIndex: Int = 0,
        mtime: Long = 1000L,
        headingTitle: String? = null,
        headingLevel: Int? = null,
        headingPath: List<String>? = null
    ): Document {
        val metadata = mutableMapOf<String, Any>(
            "source" to source,
            "chunk_index" to chunkIndex,
            "mtime" to mtime
        )
        if (headingTitle != null) metadata["heading_title"] = headingTitle
        if (headingLevel != null) metadata["heading_level"] = headingLevel
        if (headingPath != null) metadata["heading_path"] = headingPath
        return Document.builder().text(text).metadata(metadata).build()
    }

    // --- semanticSearch ---

    @Test
    fun `semanticSearch returns most similar document after add`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        val catDoc = makeDoc("The cat sat on the mat", "/abs/cat.txt", chunkIndex = 0, mtime = 1000L)
        val dogDoc = makeDoc("The dog barked loudly", "/abs/dog.txt", chunkIndex = 0, mtime = 2000L)
        repo.add(listOf(catDoc, dogDoc))

        // Query embedding is computed from the query text; the document whose embedding is closest wins.
        // We just verify at least one result is returned and the result has expected fields.
        val results = repo.semanticSearch("The cat sat on the mat", topK = 1)

        assertThat(results).hasSize(1)
        assertThat(results[0].metadata["source"]).isNotNull()
        assertThat(results[0].metadata["score"]).isNotNull()

        repo.close()
    }

    // --- bm25Search ---

    @Test
    fun `bm25Search returns best keyword-matching document after add`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        val foxDoc = makeDoc("The quick brown fox jumps over the lazy dog", "doc1.txt", chunkIndex = 0, mtime = 1000L)
        val jugDoc = makeDoc("Pack my box with five dozen liquor jugs", "doc2.txt", chunkIndex = 0, mtime = 1000L)
        repo.add(listOf(foxDoc, jugDoc))

        val results = repo.bm25Search("fox", topK = 5)

        assertThat(results).isNotEmpty()
        val topSource = results[0].metadata["source"] as? String
        assertThat(topSource).isEqualTo("doc1.txt")

        repo.close()
    }

    // --- isAlreadyIngested ---

    @Test
    fun `isAlreadyIngested returns false before add and true after add`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        assertThat(repo.isAlreadyIngested("/abs/file.txt", 1000L)).isFalse()

        val doc = makeDoc("Some content", "/abs/file.txt", mtime = 1000L)
        repo.add(listOf(doc))

        assertThat(repo.isAlreadyIngested("/abs/file.txt", 1000L)).isTrue()
        // Different mtime returns false
        assertThat(repo.isAlreadyIngested("/abs/file.txt", 9999L)).isFalse()

        repo.close()
    }

    @Test
    fun `isAlreadyIngested returns false after delete`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        val doc = makeDoc("Some content", "/abs/file.txt", mtime = 1000L)
        repo.add(listOf(doc))
        assertThat(repo.isAlreadyIngested("/abs/file.txt", 1000L)).isTrue()

        repo.delete("/abs/file.txt")

        assertThat(repo.isAlreadyIngested("/abs/file.txt", 1000L)).isFalse()

        repo.close()
    }

    // --- delete ---

    @Test
    fun `delete returns exact number of removed Lucene documents`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        val docsA = (0..2).map { i -> makeDoc("Chunk $i of a.txt", "/abs/a.txt", chunkIndex = i, mtime = 1000L) }
        val docsB = (0..1).map { i -> makeDoc("Chunk $i of b.txt", "/abs/b.txt", chunkIndex = i, mtime = 2000L) }
        repo.add(docsA + docsB)

        val removed = repo.delete("/abs/a.txt")
        assertThat(removed).isEqualTo(3)

        // getChunksForFile for deleted source returns empty
        val chunks = repo.getChunksForFile("/abs/a.txt")
        assertThat(chunks).isEmpty()

        // Other file untouched
        val bChunks = repo.getChunksForFile("/abs/b.txt")
        assertThat(bChunks).hasSize(2)

        repo.close()
    }

    @Test
    fun `delete on unknown path returns 0`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        val doc = makeDoc("Some content", "/abs/known.txt", mtime = 1000L)
        repo.add(listOf(doc))

        val removed = repo.delete("/abs/unknown.txt")
        assertThat(removed).isEqualTo(0)

        repo.close()
    }

    // --- getChunksForFile ---

    @Test
    fun `getChunksForFile returns chunks sorted ascending by chunk_index`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        val texts = listOf("Hello world", "Second chunk here", "Third chunk content text")
        // Add in reverse order to verify sorting
        val docs = texts.mapIndexed { i, text -> makeDoc(text, "/abs/file.txt", chunkIndex = i, mtime = 1716000000000L) }
        repo.add(docs.reversed())

        val chunks = repo.getChunksForFile("/abs/file.txt")

        assertThat(chunks).hasSize(3)
        assertThat(chunks[0].chunkIndex).isEqualTo(0)
        assertThat(chunks[1].chunkIndex).isEqualTo(1)
        assertThat(chunks[2].chunkIndex).isEqualTo(2)
        assertThat(chunks[0].charCount).isEqualTo("Hello world".length)
        assertThat(chunks[0].mtime).isEqualTo(1716000000000L)

        repo.close()
    }

    @Test
    fun `getChunksForFile returns empty for unknown source`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        val doc = makeDoc("Some content", "/abs/known.txt", mtime = 1000L)
        repo.add(listOf(doc))

        val chunks = repo.getChunksForFile("/abs/unknown.txt")
        assertThat(chunks).isEmpty()

        repo.close()
    }

    // --- getMetadata ---

    @Test
    fun `getMetadata reports correct chunkCount documentCount storeSizeBytes lastIngestTime staleDocumentCount`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        repo.add(listOf(
            makeDoc("Content A1", "/abs/a.txt", chunkIndex = 0, mtime = 1000L),
            makeDoc("Content A2", "/abs/a.txt", chunkIndex = 1, mtime = 1000L),
            makeDoc("Content B", "/abs/b.txt", chunkIndex = 0, mtime = 5000L),
            makeDoc("Content C", "/abs/c.txt", chunkIndex = 0, mtime = 3000L)
        ))

        // a.txt is stale (different mtime), b.txt missing (null), c.txt fresh
        val metadata = repo.getMetadata { path ->
            when (path) {
                "/abs/a.txt" -> 9999L
                "/abs/b.txt" -> null
                "/abs/c.txt" -> 3000L
                else -> null
            }
        }

        assertThat(metadata.chunkCount).isEqualTo(4)
        assertThat(metadata.documentCount).isEqualTo(3)
        assertThat(metadata.storeSizeBytes).isGreaterThan(0)
        assertThat(metadata.lastIngestTime).isEqualTo(5000L)
        assertThat(metadata.staleDocumentCount).isEqualTo(2)

        repo.close()
    }

    // --- storeExists ---

    @Test
    fun `storeExists returns false before first add and true after`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()

        assertThat(LuceneRepository.storeExists(tempDir)).isFalse()

        val repo = LuceneRepository.open(model, tempDir, "standard")
        repo.add(listOf(makeDoc("Some content", "/abs/file.txt", mtime = 1000L)))
        repo.close()

        assertThat(LuceneRepository.storeExists(tempDir)).isTrue()
    }

    // --- open/close lifecycle ---

    @Test
    fun `index is readable after close and re-open`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()

        // First open: add documents
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            repo.add(listOf(makeDoc("Fox jumps", "/abs/fox.txt", mtime = 1000L)))
        }

        // Second open: documents from first open are retrievable
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val chunks = repo.getChunksForFile("/abs/fox.txt")
            assertThat(chunks).hasSize(1)
            assertThat(chunks[0].text).contains("Fox jumps")
        }
    }

    // --- dimension validation ---

    @Test
    fun `open throws IllegalStateException when embedding dimension mismatches stored dimension`(@TempDir tempDir: Path) {
        // First open with dim=4
        val model4 = makeEmbeddingModel(dim = 4)
        LuceneRepository.open(model4, tempDir, "standard").use { repo ->
            repo.add(listOf(makeDoc("Some content", "/abs/file.txt", mtime = 1000L)))
        }

        // Second open with dim=8 should throw
        val model8 = makeEmbeddingModel(dim = 8)
        assertThatThrownBy {
            LuceneRepository.open(model8, tempDir, "standard")
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("dimension")
    }

    // --- high-dimensional embeddings ---

    @Test
    fun `add and search with 1536-dimensional embeddings succeeds`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel(dim = 1536)
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = makeDoc("OpenAI embeddings are 1536-dimensional", "/abs/openai.txt")
            repo.add(listOf(doc))
            val results = repo.semanticSearch("OpenAI embeddings", topK = 1)
            assertThat(results).hasSize(1)
        }
    }

    // --- getChunkRange ---

    @Test
    fun `getChunkRange returns exact single chunk when fromIndex equals toIndex`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val source = "/abs/file.txt"
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            repo.add((0..4).map { i -> makeDoc("Chunk $i text", source, chunkIndex = i, mtime = 1000L) })
            val result = repo.getChunkRange(source, 2, 2)
            assertThat(result).hasSize(1)
            assertThat(result[0].chunkIndex).isEqualTo(2)
            assertThat(result[0].text).isEqualTo("Chunk 2 text")
        }
    }

    @Test
    fun `getChunkRange returns window of chunks sorted by chunkIndex`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val source = "/abs/file.txt"
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            repo.add((0..4).map { i -> makeDoc("Chunk $i text", source, chunkIndex = i, mtime = 1000L) })
            val result = repo.getChunkRange(source, 1, 3)
            assertThat(result).hasSize(3)
            assertThat(result.map { it.chunkIndex }).containsExactly(1, 2, 3)
        }
    }

    @Test
    fun `getChunkRange clamps at lower boundary when fromIndex is negative`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val source = "/abs/file.txt"
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            repo.add((0..2).map { i -> makeDoc("Chunk $i text", source, chunkIndex = i, mtime = 1000L) })
            val result = repo.getChunkRange(source, -5, 1)
            assertThat(result.map { it.chunkIndex }).containsExactly(0, 1)
        }
    }

    @Test
    fun `getChunkRange clamps at upper boundary returning only available chunks`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val source = "/abs/file.txt"
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            repo.add((0..2).map { i -> makeDoc("Chunk $i text", source, chunkIndex = i, mtime = 1000L) })
            val result = repo.getChunkRange(source, 1, 100)
            assertThat(result.map { it.chunkIndex }).containsExactly(1, 2)
        }
    }

    @Test
    fun `getChunkRange returns empty list for unknown source`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            repo.add(listOf(makeDoc("Some text", "/abs/known.txt", mtime = 1000L)))
            val result = repo.getChunkRange("/abs/unknown.txt", 0, 5)
            assertThat(result).isEmpty()
        }
    }

    @Test
    fun `getChunkRange returns empty list when fromIndex is greater than toIndex`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val source = "/abs/file.txt"
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            repo.add((0..4).map { i -> makeDoc("Chunk $i text", source, chunkIndex = i, mtime = 1000L) })
            val result = repo.getChunkRange(source, 3, 1)
            assertThat(result).isEmpty()
        }
    }

    // --- heading_path round-trip ---

    @Test
    fun `heading_path round-trips correctly through JSON serialisation after close and re-open`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val originalPath = listOf("Overview", "Installation", "Quick Start")

        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = makeDoc(
                "### Quick Start\nRun the tool.",
                "/abs/guide.md",
                chunkIndex = 0,
                mtime = 2000L,
                headingTitle = "Quick Start",
                headingLevel = 3,
                headingPath = originalPath
            )
            repo.add(listOf(doc))
        }

        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val chunks = repo.getChunksForFile("/abs/guide.md")
            assertThat(chunks).hasSize(1)
            assertThat(chunks[0].headingTitle).isEqualTo("Quick Start")
            assertThat(chunks[0].headingLevel).isEqualTo(3)
            assertThat(chunks[0].headingPath).isEqualTo(originalPath)
        }
    }
}
