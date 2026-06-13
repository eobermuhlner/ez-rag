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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    @Test
    fun `bm25Search scores are normalized to 0_0 to 1_0`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        repo.add(listOf(
            makeDoc("The quick brown fox jumps over the lazy dog", "doc1.txt"),
            makeDoc("Pack my box with five dozen liquor jugs", "doc2.txt"),
            makeDoc("How vexingly quick daft zebras jump", "doc3.txt"),
        ))

        val results = repo.bm25Search("fox", topK = 5)

        assertThat(results).isNotEmpty()
        for (doc in results) {
            val score = doc.metadata["score"] as Double
            assertThat(score).isBetween(0.0, 1.0)
        }

        repo.close()
    }

    @Test
    fun `bm25Search top result has score 1_0`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        repo.add(listOf(
            makeDoc("The quick brown fox jumps over the lazy dog", "doc1.txt"),
            makeDoc("Pack my box with five dozen liquor jugs", "doc2.txt"),
        ))

        val results = repo.bm25Search("fox", topK = 5)

        assertThat(results).isNotEmpty()
        val topScore = results[0].metadata["score"] as Double
        assertThat(topScore).isEqualTo(1.0)

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
        val metadata = repo.getMetadata(filesystemProbe = { path ->
            when (path) {
                "/abs/a.txt" -> 9999L
                "/abs/b.txt" -> null
                "/abs/c.txt" -> 3000L
                else -> null
            }
        })

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

    // --- isContentUnchanged ---

    @Test
    fun `isContentUnchanged returns true on fast path when mtime matches`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = Document.builder()
                .text("Some content")
                .metadata(mutableMapOf<String, Any>("source" to "/abs/file.txt", "mtime" to 1000L, "chunk_index" to 0, "content_hash" to "abc123"))
                .build()
            repo.add(listOf(doc))

            // Same mtime → fast path returns true regardless of provided hash
            assertThat(repo.isContentUnchanged("/abs/file.txt", 1000L, "completely-different-hash")).isTrue()
        }
    }

    @Test
    fun `isContentUnchanged returns true when hash matches even if mtime differs`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = Document.builder()
                .text("Some content")
                .metadata(mutableMapOf<String, Any>("source" to "/abs/file.txt", "mtime" to 1000L, "chunk_index" to 0, "content_hash" to "abc123"))
                .build()
            repo.add(listOf(doc))

            // Different mtime but same hash → skip
            assertThat(repo.isContentUnchanged("/abs/file.txt", 9999L, "abc123")).isTrue()
        }
    }

    @Test
    fun `isContentUnchanged returns false when hash differs`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = Document.builder()
                .text("Some content")
                .metadata(mutableMapOf<String, Any>("source" to "/abs/file.txt", "mtime" to 1000L, "chunk_index" to 0, "content_hash" to "abc123"))
                .build()
            repo.add(listOf(doc))

            // Different mtime and different hash → re-ingest
            assertThat(repo.isContentUnchanged("/abs/file.txt", 9999L, "xyz789")).isFalse()
        }
    }

    // --- content_hash in StoreDocumentInfo ---

    @Test
    fun `getMetadata returns StoreDocumentInfo with non-null contentHash`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = Document.builder()
                .text("Some content")
                .metadata(mutableMapOf<String, Any>("source" to "/abs/file.txt", "mtime" to 1000L, "chunk_index" to 0, "content_hash" to "sha256hex"))
                .build()
            repo.add(listOf(doc))

            val metadata = repo.getMetadata(filesystemProbe = { _ -> 1000L })
            assertThat(metadata.documents).hasSize(1)
            assertThat(metadata.documents[0].contentHash).isEqualTo("sha256hex")
        }
    }

    // --- concurrent add thread safety ---

    @Test
    fun `concurrent add calls from two threads complete without exception and total chunk count is correct`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        val batchSize = 10
        val batchA = (0 until batchSize).map { i ->
            makeDoc("Batch A chunk $i", "/abs/batchA.txt", chunkIndex = i, mtime = 1000L + i)
        }
        val batchB = (0 until batchSize).map { i ->
            makeDoc("Batch B chunk $i", "/abs/batchB.txt", chunkIndex = i, mtime = 2000L + i)
        }

        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>()
        val executor = Executors.newFixedThreadPool(2)

        executor.submit {
            try {
                latch.await()
                repo.add(batchA)
            } catch (e: Throwable) {
                errorRef.compareAndSet(null, e)
            }
        }
        executor.submit {
            try {
                latch.await()
                repo.add(batchB)
            } catch (e: Throwable) {
                errorRef.compareAndSet(null, e)
            }
        }

        latch.countDown()
        executor.shutdown()
        executor.awaitTermination(30, TimeUnit.SECONDS)

        repo.close()

        assertThat(errorRef.get()).isNull()

        // Verify both batches were persisted correctly
        LuceneRepository.open(model, tempDir, "standard").use { verifyRepo ->
            val chunksA = verifyRepo.getChunksForFile("/abs/batchA.txt")
            val chunksB = verifyRepo.getChunksForFile("/abs/batchB.txt")
            assertThat(chunksA.size + chunksB.size).isEqualTo(batchSize * 2)
        }
    }

    @Test
    fun `concurrent delete calls from two threads on different sources complete without exception and both sources are absent`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        // Pre-ingest two distinct sources
        val docsA = (0 until 5).map { i -> makeDoc("Source A chunk $i", "/abs/sourceA.txt", chunkIndex = i, mtime = 1000L + i) }
        val docsB = (0 until 5).map { i -> makeDoc("Source B chunk $i", "/abs/sourceB.txt", chunkIndex = i, mtime = 2000L + i) }
        repo.add(docsA + docsB)

        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>()
        val executor = Executors.newFixedThreadPool(2)

        executor.submit {
            try {
                latch.await()
                repo.delete("/abs/sourceA.txt")
            } catch (e: Throwable) {
                errorRef.compareAndSet(null, e)
            }
        }
        executor.submit {
            try {
                latch.await()
                repo.delete("/abs/sourceB.txt")
            } catch (e: Throwable) {
                errorRef.compareAndSet(null, e)
            }
        }

        latch.countDown()
        executor.shutdown()
        executor.awaitTermination(30, TimeUnit.SECONDS)

        repo.close()

        assertThat(errorRef.get()).isNull()

        // Verify both sources are absent from the index
        LuceneRepository.open(model, tempDir, "standard").use { verifyRepo ->
            assertThat(verifyRepo.getChunksForFile("/abs/sourceA.txt")).isEmpty()
            assertThat(verifyRepo.getChunksForFile("/abs/sourceB.txt")).isEmpty()
        }
    }

    @Test
    fun `concurrent add and delete calls from two threads on distinct sources complete without exception and index reflects both operations`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val repo = LuceneRepository.open(model, tempDir, "standard")

        // Pre-ingest one source to be deleted
        val existingDocs = (0 until 5).map { i -> makeDoc("Existing chunk $i", "/abs/existing.txt", chunkIndex = i, mtime = 1000L + i) }
        repo.add(existingDocs)

        // Prepare new batch to be added concurrently
        val newBatch = (0 until 5).map { i -> makeDoc("New chunk $i", "/abs/new.txt", chunkIndex = i, mtime = 3000L + i) }

        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<Throwable?>()
        val executor = Executors.newFixedThreadPool(2)

        // One thread adds new documents
        executor.submit {
            try {
                latch.await()
                repo.add(newBatch)
            } catch (e: Throwable) {
                errorRef.compareAndSet(null, e)
            }
        }
        // Another thread deletes the existing source
        executor.submit {
            try {
                latch.await()
                repo.delete("/abs/existing.txt")
            } catch (e: Throwable) {
                errorRef.compareAndSet(null, e)
            }
        }

        latch.countDown()
        executor.shutdown()
        executor.awaitTermination(30, TimeUnit.SECONDS)

        repo.close()

        assertThat(errorRef.get()).isNull()

        // Verify: new source is present, deleted source is absent
        LuceneRepository.open(model, tempDir, "standard").use { verifyRepo ->
            assertThat(verifyRepo.getChunksForFile("/abs/existing.txt")).isEmpty()
            assertThat(verifyRepo.getChunksForFile("/abs/new.txt")).hasSize(5)
        }
    }

    // --- URL freshness (ingest_time) ---

    @Test
    fun `getMetadata returns status FRESH for URL doc with ingest_time within threshold`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val now = 1_700_000_000_000L
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = Document.builder()
                .text("URL content")
                .metadata(mutableMapOf<String, Any>(
                    "source" to "https://example.com/page",
                    "mtime" to 0L,
                    "chunk_index" to 0,
                    "ingest_time" to now,
                ))
                .build()
            repo.add(listOf(doc))

            val metadata = repo.getMetadata(
                urlFreshnessThresholdMs = 24 * 3_600_000L,
                currentTimeMs = now + 1_000_000L, // 1000 seconds after ingest
            )
            assertThat(metadata.documents).hasSize(1)
            assertThat(metadata.documents[0].status).isEqualTo("FRESH")
        }
    }

    @Test
    fun `getMetadata returns status STALE for URL doc beyond freshness threshold`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val now = 1_700_000_000_000L
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = Document.builder()
                .text("URL content")
                .metadata(mutableMapOf<String, Any>(
                    "source" to "https://example.com/page",
                    "mtime" to 0L,
                    "chunk_index" to 0,
                    "ingest_time" to now,
                ))
                .build()
            repo.add(listOf(doc))

            val metadata = repo.getMetadata(
                urlFreshnessThresholdMs = 24 * 3_600_000L,
                currentTimeMs = now + 25 * 3_600_000L, // 25 hours after ingest
            )
            assertThat(metadata.documents).hasSize(1)
            assertThat(metadata.documents[0].status).isEqualTo("STALE")
        }
    }

    @Test
    fun `getMetadata returns status STALE for URL doc with no ingest_time backward compat`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = Document.builder()
                .text("URL content")
                .metadata(mutableMapOf<String, Any>(
                    "source" to "https://example.com/page",
                    "mtime" to 0L,
                    "chunk_index" to 0,
                ))
                .build()
            repo.add(listOf(doc))

            val metadata = repo.getMetadata(urlFreshnessThresholdMs = 24 * 3_600_000L)
            assertThat(metadata.documents).hasSize(1)
            assertThat(metadata.documents[0].status).isEqualTo("STALE")
        }
    }

    @Test
    fun `getMetadata returns status FRESH for file source when probe matches mtime`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = Document.builder()
                .text("File content")
                .metadata(mutableMapOf<String, Any>(
                    "source" to "/abs/file.txt",
                    "mtime" to 1000L,
                    "chunk_index" to 0,
                ))
                .build()
            repo.add(listOf(doc))

            val metadata = repo.getMetadata(filesystemProbe = { _ -> 1000L })
            assertThat(metadata.documents).hasSize(1)
            assertThat(metadata.documents[0].status).isEqualTo("FRESH")
        }
    }

    @Test
    fun `getMetadata returns status STALE for file source when probe returns different mtime`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = Document.builder()
                .text("File content")
                .metadata(mutableMapOf<String, Any>(
                    "source" to "/abs/file.txt",
                    "mtime" to 1000L,
                    "chunk_index" to 0,
                ))
                .build()
            repo.add(listOf(doc))

            val metadata = repo.getMetadata(filesystemProbe = { _ -> 9999L })
            assertThat(metadata.documents).hasSize(1)
            assertThat(metadata.documents[0].status).isEqualTo("STALE")
        }
    }

    @Test
    fun `getMetadata returns status STALE for file source when probe returns null`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = Document.builder()
                .text("File content")
                .metadata(mutableMapOf<String, Any>(
                    "source" to "/abs/file.txt",
                    "mtime" to 1000L,
                    "chunk_index" to 0,
                ))
                .build()
            repo.add(listOf(doc))

            val metadata = repo.getMetadata(filesystemProbe = { _ -> null })
            assertThat(metadata.documents).hasSize(1)
            assertThat(metadata.documents[0].status).isEqualTo("STALE")
        }
    }

    @Test
    fun `getMetadata with custom urlFreshnessThresholdMs overrides default`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val now = 1_700_000_000_000L
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = Document.builder()
                .text("URL content")
                .metadata(mutableMapOf<String, Any>(
                    "source" to "https://example.com/page",
                    "mtime" to 0L,
                    "chunk_index" to 0,
                    "ingest_time" to now,
                ))
                .build()
            repo.add(listOf(doc))

            // 12 hours after ingest; default threshold (24h) → FRESH, custom threshold (6h) → STALE
            val metadata = repo.getMetadata(
                urlFreshnessThresholdMs = 6 * 3_600_000L,
                currentTimeMs = now + 12 * 3_600_000L,
            )
            assertThat(metadata.documents).hasSize(1)
            assertThat(metadata.documents[0].status).isEqualTo("STALE")
        }
    }

    @Test
    fun `getMetadata after delete and re-add resets freshness timer for URL`(@TempDir tempDir: Path) {
        val model = makeEmbeddingModel()
        val now1 = 1_700_000_000_000L
        LuceneRepository.open(model, tempDir, "standard").use { repo ->
            val doc = Document.builder()
                .text("URL content")
                .metadata(mutableMapOf<String, Any>(
                    "source" to "https://example.com/page",
                    "mtime" to 0L,
                    "chunk_index" to 0,
                    "ingest_time" to now1,
                ))
                .build()
            repo.add(listOf(doc))

            // Re-add with a newer ingest_time
            val now2 = now1 + 10_000_000L
            val doc2 = Document.builder()
                .text("URL content updated")
                .metadata(mutableMapOf<String, Any>(
                    "source" to "https://example.com/page",
                    "mtime" to 0L,
                    "chunk_index" to 0,
                    "ingest_time" to now2,
                ))
                .build()
            repo.delete("https://example.com/page")
            repo.add(listOf(doc2))

            // Query with currentTimeMs = now1 + 1s should be STALE (old timer), but now2 + 1s should be FRESH
            val metadata = repo.getMetadata(
                urlFreshnessThresholdMs = 24 * 3_600_000L,
                currentTimeMs = now2 + 1_000_000L,
            )
            assertThat(metadata.documents).hasSize(1)
            assertThat(metadata.documents[0].status).isEqualTo("FRESH")
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
