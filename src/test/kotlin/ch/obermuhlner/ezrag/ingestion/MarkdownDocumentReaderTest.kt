package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
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
    fun `heading_path round-trips through LuceneRepository serialisation`(@TempDir tempDir: Path) {
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
        val embeddingModel = makeFakeEmbeddingModel()
        val repository = LuceneRepository.open(embeddingModel, tempDir, "standard")

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
        repository.close()

        // Load back via a new repository instance (simulates round-trip through the index)
        val repository2 = LuceneRepository.open(embeddingModel, tempDir, "standard")
        val chunks = repository2.getChunksForFile("/abs/roundtrip.md")
        repository2.close()

        // The chunk for "Sub Section" should have heading_path stored correctly
        val subChunkInfo = chunks.find { it.headingTitle == "Sub Section" }
        assertThat(subChunkInfo).isNotNull()
        val roundTrippedPath = subChunkInfo!!.headingPath
        assertThat(roundTrippedPath).isNotNull()
        assertThat(roundTrippedPath).containsExactly("Top Level", "Sub Section")
    }

    @Test
    fun `a large section produces multiple Documents all with the correct heading metadata`(@TempDir tempDir: Path) {
        val loremBody = (1..30).joinToString("\n\n") { "Paragraph $it with enough words to consume token budget." }
        val file = tempDir.resolve("large-section.md").toFile()
        file.writeText("# Big Section\n$loremBody")

        val reader = MarkdownDocumentReader(file, chunkSize = 30, chunkOverlap = 5)
        val documents = reader.read()

        val sectionDocs = documents.filter { it.metadata["heading_title"] == "Big Section" }
        assertThat(sectionDocs.size).isGreaterThan(1)
        sectionDocs.forEach { doc ->
            assertThat(doc.metadata["heading_title"]).isEqualTo("Big Section")
            assertThat(doc.metadata["heading_level"]).isEqualTo(1)
            @Suppress("UNCHECKED_CAST")
            val path = doc.metadata["heading_path"] as List<String>
            assertThat(path).containsExactly("Big Section")
            assertThat(doc.text).startsWith("# Big Section\n")
        }
    }

    @Test
    fun `a section fitting within chunkSize still produces exactly one Document`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("small-section.md").toFile()
        file.writeText("# Small\nShort content.")

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val sectionDocs = documents.filter { it.metadata["heading_title"] == "Small" }
        assertThat(sectionDocs).hasSize(1)
        assertThat(sectionDocs[0].text).startsWith("# Small\n")
        assertThat(sectionDocs[0].text).contains("Short content.")
    }

    @Test
    fun `a horizontal rule within a section body produces two Documents neither containing the rule`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("hrule.md").toFile()
        file.writeText("""
            # Section
            First part of content.

            ---

            Second part of content.
        """.trimIndent())

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        val sectionDocs = documents.filter { it.metadata["heading_title"] == "Section" }
        assertThat(sectionDocs).hasSize(2)
        sectionDocs.forEach { doc ->
            assertThat(doc.text).doesNotContain("---")
        }
        assertThat(sectionDocs[0].text).contains("First part of content.")
        assertThat(sectionDocs[1].text).contains("Second part of content.")
    }

    @Test
    fun `YAML front matter followed by a horizontal rule mid-body produces no front-matter content in chunks`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("yaml-hrule.md").toFile()
        file.writeText("""
            ---
            title: Secret Title
            author: Hidden Author
            ---
            # Section
            Before the rule.

            ---

            After the rule.
        """.trimIndent())

        val reader = MarkdownDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val documents = reader.read()

        assertThat(documents).isNotEmpty()
        documents.forEach { doc ->
            assertThat(doc.text).doesNotContain("Secret Title")
            assertThat(doc.text).doesNotContain("Hidden Author")
        }
        val sectionDocs = documents.filter { it.metadata["heading_title"] == "Section" }
        assertThat(sectionDocs).hasSize(2)
        assertThat(sectionDocs[0].text).contains("Before the rule.")
        assertThat(sectionDocs[1].text).contains("After the rule.")
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
