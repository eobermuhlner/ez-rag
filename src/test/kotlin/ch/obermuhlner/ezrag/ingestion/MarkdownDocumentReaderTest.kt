package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import org.springframework.ai.vectorstore.SimpleVectorStore
import java.nio.file.Path

class MarkdownDocumentReaderTest {

    @Test
    fun `a md file with body text but no headings produces at least one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("noheadings.md").toFile()
        file.writeText("This is a plain markdown file with no headings. It has some text content.")

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
    }

    @Test
    fun `no chunk has source in its metadata`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("noheadings.md").toFile()
        file.writeText("Some markdown content without headings.")

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("source")
        }
    }

    @Test
    fun `a md file with no headings produces no chunk with heading_title in metadata`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("noheadings.md").toFile()
        file.writeText("This is a plain markdown file with no headings. It has some text content.")

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.metadata).doesNotContainKey("heading_title")
        }
    }

    @Test
    fun `YAML front-matter is stripped and does not appear in any chunk content`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("frontmatter.md").toFile()
        file.writeText("""
            ---
            title: My Document
            author: Test Author
            ---
            # Introduction
            This is the introduction section.
        """.trimIndent())

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.text).doesNotContain("title: My Document")
            assertThat(doc.text).doesNotContain("author: Test Author")
            assertThat(doc.text).doesNotContain("---")
        }
    }

    @Test
    fun `pre-heading body text emits as a standalone chunk without heading metadata`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("preheading.md").toFile()
        file.writeText("""
            This is some introductory text before any heading.
            It spans multiple lines.

            # First Heading
            Content under first heading.
        """.trimIndent())

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val preHeadingChunks = documents.filter { !it.metadata.containsKey("heading_title") }
        assertThat(preHeadingChunks).isNotEmpty()
        assertThat(preHeadingChunks[0].text).contains("introductory text before any heading")
    }

    @Test
    fun `a single heading with body text produces one chunk whose content begins with the heading`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("singleheading.md").toFile()
        file.writeText("""
            # My Section
            This is the body of the section.
        """.trimIndent())

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val headingChunks = documents.filter { it.metadata.containsKey("heading_title") }
        assertThat(headingChunks).hasSize(1)
        assertThat(headingChunks[0].text).startsWith("# My Section\n")
        assertThat(headingChunks[0].text).contains("This is the body of the section.")
    }

    @Test
    fun `a heading with whitespace-only body produces no chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("emptyheading.md").toFile()
        file.writeText("""
            # Empty Section

            # Real Section
            This section has content.
        """.trimIndent())

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val headingChunks = documents.filter { it.metadata.containsKey("heading_title") }
        // Only "Real Section" should produce a chunk; "Empty Section" should not
        assertThat(headingChunks).hasSize(1)
        assertThat(headingChunks[0].metadata["heading_title"]).isEqualTo("Real Section")
    }

    @Test
    fun `heading_title equals immediate heading text and heading_level equals count of hash chars`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("levels.md").toFile()
        file.writeText("""
            ## Level Two Section
            Content here.
        """.trimIndent())

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val headingChunks = documents.filter { it.metadata.containsKey("heading_title") }
        assertThat(headingChunks).hasSize(1)
        assertThat(headingChunks[0].metadata["heading_title"]).isEqualTo("Level Two Section")
        assertThat(headingChunks[0].metadata["heading_level"]).isEqualTo(2)
    }

    @Test
    fun `nested headings produce correct heading_path and content prefix`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("nested.md").toFile()
        file.writeText("""
            # H1 Title
            Content of h1.

            ## H2 Title
            Content of h2.
        """.trimIndent())

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val h2Chunk = documents.find { it.metadata["heading_title"] == "H2 Title" }
        assertThat(h2Chunk).isNotNull()
        @Suppress("UNCHECKED_CAST")
        val path = h2Chunk!!.metadata["heading_path"] as List<String>
        assertThat(path).containsExactly("H1 Title", "H2 Title")
        assertThat(h2Chunk.text).startsWith("# H1 Title\n## H2 Title\n")
    }

    @Test
    fun `two consecutive same-level headings produce independent chunks with correct stacks`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("siblings.md").toFile()
        file.writeText("""
            # Parent
            Parent content.

            ## Sibling One
            Content of sibling one.

            ## Sibling Two
            Content of sibling two.
        """.trimIndent())

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val sibling1 = documents.find { it.metadata["heading_title"] == "Sibling One" }
        val sibling2 = documents.find { it.metadata["heading_title"] == "Sibling Two" }

        assertThat(sibling1).isNotNull()
        assertThat(sibling2).isNotNull()

        @Suppress("UNCHECKED_CAST")
        val path2 = sibling2!!.metadata["heading_path"] as List<String>
        // Second sibling's path should contain "Parent" and "Sibling Two" but not "Sibling One"
        assertThat(path2).containsExactly("Parent", "Sibling Two")
        assertThat(path2).doesNotContain("Sibling One")
    }

    @Test
    fun `heading_path round-trips through SimpleVectorStore JSON serialisation`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("roundtrip.md").toFile()
        file.writeText("""
            # Top Level
            Intro content.

            ## Sub Section
            Sub section content.
        """.trimIndent())

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        // Find the chunk for "Sub Section" which has heading_path = ["Top Level", "Sub Section"]
        val subChunk = documents.find { it.metadata["heading_title"] == "Sub Section" }
        assertThat(subChunk).isNotNull()

        // Simulate IngestService: set source, mtime, chunk_index metadata
        val storeFilePath = tempDir.resolve("vector-store.json")
        val embeddingModel = makeFakeEmbeddingModel()
        val repository = VectorStoreRepository(embeddingModel, storeFilePath)
        repository.load()

        val docsToStore = documents.mapIndexed { idx, doc ->
            Document.builder()
                .id(doc.id)
                .text(doc.text)
                .metadata(doc.metadata.toMutableMap().apply {
                    put("source", "/abs/roundtrip.md")
                    put("mtime", 1000L)
                    put("chunk_index", idx)
                })
                .build()
        }
        repository.add(docsToStore)
        repository.save()

        // Load back via a new repository instance (simulates round-trip through JSON)
        val repository2 = VectorStoreRepository(embeddingModel, storeFilePath)
        repository2.load()
        val chunks = repository2.getChunksForFile("/abs/roundtrip.md")

        // The chunk for "Sub Section" should have heading_path stored correctly
        // We verify via the raw store that heading_path is a list
        val storeField = SimpleVectorStore::class.java.getDeclaredField("store")
        storeField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val storeMap = storeField.get(repository2.getStore()) as Map<String, Any>
        val subEntry = storeMap.values.find { entry ->
            val meta = entry.javaClass.getMethod("getMetadata").invoke(entry) as? Map<*, *>
            meta?.get("heading_title") == "Sub Section"
        }
        assertThat(subEntry).isNotNull()
        val metaMethod = subEntry!!.javaClass.getMethod("getMetadata")
        @Suppress("UNCHECKED_CAST")
        val meta = metaMethod.invoke(subEntry) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val roundTrippedPath = meta["heading_path"] as? List<String>
        assertThat(roundTrippedPath).isNotNull()
        assertThat(roundTrippedPath).containsExactly("Top Level", "Sub Section")
    }

    private fun makeFakeEmbeddingModel(): EmbeddingModel = object : EmbeddingModel {
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
}
