package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JsoncChunkerTest {

    // Use a simple word-count token counter for predictable unit tests
    private val wordCount: (String) -> Int = { text ->
        if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
    }

    private fun chunker(chunkSize: Int = 1000): JsoncChunker =
        JsoncChunker(chunkSize = chunkSize, chunkOverlap = 0, tokenCounter = wordCount)

    // ---- Task 01 acceptance criteria ----

    @Test
    fun `empty string returns empty list`() {
        val result = chunker().chunk("")
        assertThat(result).isEmpty()
    }

    @Test
    fun `blank whitespace string returns empty list`() {
        val result = chunker().chunk("  ")
        assertThat(result).isEmpty()
    }

    @Test
    fun `empty object returns empty list`() {
        val result = chunker().chunk("{}")
        assertThat(result).isEmpty()
    }

    @Test
    fun `flat object produces one chunk containing all fields`() {
        val result = chunker().chunk("""{"a":"x","b":"y"}""")
        assertThat(result).hasSize(1)
        assertThat(result[0]).startsWith("##")
        assertThat(result[0]).contains("**a**: x")
        assertThat(result[0]).contains("**b**: y")
    }

    @Test
    fun `jsonc string with trailing comment parses without exception and produces same content as plain json`() {
        val plain = chunker().chunk("""{ "x": 1 }""")
        val withComment = chunker().chunk("""{ "x": 1 } // trailing""")
        // Same chunk count and **x**: 1 content
        assertThat(withComment.size).isEqualTo(plain.size)
        assertThat(withComment.joinToString()).contains("**x**: 1")
    }

    @Test
    fun `nested object produces chunk with heading for parent key`() {
        val result = chunker().chunk("""{"store":{"dir":".ez-rag"}}""")
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).contains("## store")
        assertThat(allText).contains("**dir**: .ez-rag")
    }

    @Test
    fun `object fields exceeding budget are split into multiple chunks each within budget`() {
        // 10 fields with tight budget
        val json = (1..10).joinToString(",", "{", "}") { i -> """"fieldName$i":"fieldValue$i"""" }
        val budget = 10
        val result = chunker(chunkSize = budget).chunk(json)

        assertThat(result).isNotEmpty()
        for (chunk in result) {
            val tokens = wordCount(chunk)
            assertThat(tokens)
                .withFailMessage("Chunk token count $tokens exceeds budget $budget: '$chunk'")
                .isLessThanOrEqualTo(budget)
        }
    }

    // ---- Task 02 acceptance criteria ----

    @Test
    fun `empty array returns empty list`() {
        val result = chunker().chunk("[]")
        assertThat(result).isEmpty()
    }

    @Test
    fun `3-element array all fitting budget produces one chunk with heading Items 1-3`() {
        val result = chunker().chunk("""["a","b","c"]""")
        assertThat(result).hasSize(1)
        assertThat(result[0]).startsWith("## Items 1-3")
    }

    @Test
    fun `array of object elements produces one chunk per element regardless of budget`() {
        val result = chunker().chunk("""[{"a":"x"},{"b":"y"},{"c":"z"}]""")
        assertThat(result).hasSize(3)
        assertThat(result[0]).startsWith("## Item 1")
        assertThat(result[1]).startsWith("## Item 2")
        assertThat(result[2]).startsWith("## Item 3")
    }

    @Test
    fun `array split 2+1 by budget produces correct headings`() {
        // Using word-count:
        //   2-element chunk: "## Items 1-2\n\nalpha\n\nbeta" = 5 words (##, Items, 1-2, alpha, beta)
        //   3-element chunk: "## Items 1-3\n\nalpha\n\nbeta\n\ngamma" = 6 words
        // Budget of 5: fits 2 items but not 3
        val result = chunker(chunkSize = 5).chunk("""["alpha","beta","gamma"]""")
        assertThat(result).hasSize(2)
        assertThat(result[0]).startsWith("## Items 1-2")
        assertThat(result[1]).startsWith("## Item 3")
    }

    @Test
    fun `single oversized array element is emitted alone as Item 1`() {
        // One long element that exceeds the budget by itself
        val longValue = "word ".repeat(20).trim() // 20 words
        val result = chunker(chunkSize = 5).chunk("""["$longValue"]""")
        assertThat(result).hasSize(1)
        assertThat(result[0]).startsWith("## Item 1")
    }

    @Test
    fun `array nested under object key produces headings with parent prefix`() {
        val result = chunker().chunk("""{"servers":["host1","host2","host3"]}""")
        val allText = result.joinToString("\n")
        assertThat(allText).contains("## servers -> Items 1-3")
    }

    // ---- Task 04 acceptance criteria ----

    @Test
    fun `line comment immediately before key appears as prose before key-value line`() {
        val source = """
            {
                // path where stored
                "directory": ".ez-rag"
            }
        """.trimIndent()
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).contains("path where stored")
        assertThat(allText).contains("**directory**: .ez-rag")
        // Comment text must appear before the key-value line in the same chunk
        val chunkContainingDir = result.first { it.contains("**directory**") }
        val commentIdx = chunkContainingDir.indexOf("path where stored")
        val keyIdx = chunkContainingDir.indexOf("**directory**")
        assertThat(commentIdx).isLessThan(keyIdx)
    }

    @Test
    fun `block comment before key is normalised and appears as prose`() {
        val source = """
            {
                /* line1
                   line2 */
                "key": "value"
            }
        """.trimIndent()
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        // Block comment markers must not appear
        assertThat(allText).doesNotContain("/*")
        assertThat(allText).doesNotContain("*/")
        // Text is normalised: "line1 line2" (single space, no leading/trailing whitespace)
        assertThat(allText).contains("line1 line2")
    }

    @Test
    fun `raw comment markers do not appear in any produced chunk`() {
        val source = """
            {
                // line comment
                "a": 1,
                /* block comment */
                "b": 2
            }
        """.trimIndent()
        val result = chunker().chunk(source)
        val allText = result.joinToString("\n")
        assertThat(allText).doesNotContain("//")
        assertThat(allText).doesNotContain("/*")
        assertThat(allText).doesNotContain("*/")
    }

    @Test
    fun `comment before array element appears as prose before element fields`() {
        val source = """
            [
                // first element
                {"id": "ELEM_ONE"},
                // second element
                {"id": "ELEM_TWO"}
            ]
        """.trimIndent()
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).contains("first element")
        assertThat(allText).contains("second element")
    }

    // ---- Task 05 acceptance criteria ----

    @Test
    fun `trailing inline comment on string value is appended with separator`() {
        val source = """{ "mode": "hybrid"  // combines BM25 and embeddings }"""
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).contains("**mode**: hybrid - combines BM25 and embeddings")
    }

    @Test
    fun `trailing inline comment on number value is appended with separator`() {
        val source = """{ "topK": 10  // result count }"""
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).contains("**topK**: 10 - result count")
    }

    @Test
    fun `trailing inline comment on boolean value is appended with separator`() {
        val source = """{ "enabled": true  // default on }"""
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).contains("**enabled**: true - default on")
    }

    @Test
    fun `key with both preceding block comment and trailing line comment produces both in same chunk`() {
        val source = """
            {
                /* describes the mode */
                "mode": "hybrid"  // combines BM25 and embeddings
            }
        """.trimIndent()
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        // Preceding block comment appears as prose
        assertThat(allText).contains("describes the mode")
        // Trailing comment appended to value line
        assertThat(allText).contains("**mode**: hybrid - combines BM25 and embeddings")
    }

    @Test
    fun `trailing comment on array element is appended to last rendered field line`() {
        val source = """
            [
                {"id": "ELEM_ONE"} // first element comment
            ]
        """.trimIndent()
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).contains("- first element comment")
    }

    // ---- Task 06 acceptance criteria ----

    @Test
    fun `single file-level comment is prepended to every chunk`() {
        val source = """
            // ez-rag config
            {
                "a": "1",
                "b": "2"
            }
        """.trimIndent()
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        for (chunk in result) {
            assertThat(chunk).startsWith("ez-rag config")
        }
    }

    @Test
    fun `two file-level comments are joined into single preamble prepended to all chunks`() {
        val source = """
            // line one
            // line two
            {
                "a": "1"
            }
        """.trimIndent()
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        for (chunk in result) {
            assertThat(chunk).startsWith("line one line two")
        }
    }

    @Test
    fun `file-level comment token cost is deducted from budget so content fits`() {
        // Preamble = "preamble text" = 2 words
        // Budget = 8
        // Effective content budget = 8 - 2 = 6
        // Each field entry: "## fieldN valueN" is ~ 3 words (including heading)
        // Without preamble deduction, a budget=8 would fit more fields per chunk
        val source = """
            // preamble text
            {
                "field1": "value1",
                "field2": "value2",
                "field3": "value3",
                "field4": "value4"
            }
        """.trimIndent()
        val preamble = "preamble text"
        val preambleTokens = wordCount(preamble)
        val budget = 8
        val result = chunker(chunkSize = budget).chunk(source)

        assertThat(result).isNotEmpty()
        for (chunk in result) {
            val tokens = wordCount(chunk)
            assertThat(tokens)
                .withFailMessage("Chunk token count $tokens exceeds budget $budget: '$chunk'")
                .isLessThanOrEqualTo(budget)
        }
    }

    @Test
    fun `plain json with no file-level comments produces chunks with no preamble`() {
        val source = """{"x": "1", "y": "2"}"""
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        // First chunk should start with ## (the heading), not a comment preamble
        assertThat(result[0]).startsWith("##")
    }

    // ---- Array prose rendering (inline object context) ----

    @Test
    fun `scalar string array inside inline object renders as comma-separated prose`() {
        val source = """[{"tags": ["AI", "NLP", "search"]}]"""
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).contains("**tags**: AI, NLP, search")
        assertThat(allText).doesNotContain("```")
    }

    @Test
    fun `scalar number array inside inline object renders as comma-separated prose`() {
        val source = """[{"scores": [4.8, 4.5, 4.2]}]"""
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).contains("**scores**: 4.8, 4.5, 4.2")
        assertThat(allText).doesNotContain("```")
    }

    @Test
    fun `scalar array with null element inside inline object renders null as prose`() {
        val source = """[{"codes": [null, "active"]}]"""
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).contains("**codes**: null, active")
        assertThat(allText).doesNotContain("```")
    }

    @Test
    fun `empty array inside inline object omits the field`() {
        val source = """[{"tags": [], "name": "Alice"}]"""
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).doesNotContain("**tags**")
        assertThat(allText).contains("**name**: Alice")
    }

    @Test
    fun `mixed scalar and object array inside inline object renders as code block`() {
        val source = """[{"data": ["note", {"id": 1}]}]"""
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).contains("```")
    }

    @Test
    fun `object array inside inline object renders as semicolon-separated flat items with plain keys`() {
        val source = """[{"contributors": [{"name": "Alice", "role": "author"}, {"name": "Bob", "role": "editor"}]}]"""
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).contains("**contributors**: name: Alice, role: author; name: Bob, role: editor")
        assertThat(allText).doesNotContain("```")
    }

    @Test
    fun `nested object inside flat object-array item renders as raw JSON not further flattened`() {
        val source = """[{"contributors": [{"name": "Alice", "address": {"city": "Zurich"}}]}]"""
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val allText = result.joinToString("\n")
        assertThat(allText).contains("name: Alice")
        // Nested object stops recursion — city stays inside JSON-like text
        assertThat(allText).contains("city")
    }

    // ---- Bug fix: preceding comments on nested object/array keys must not be dropped ----

    @Test
    fun `line comment before nested object key appears in first chunk of that nested section`() {
        val source = """
            {
                // describes the store section
                "store": {
                    "dir": ".ez-rag"
                }
            }
        """.trimIndent()
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val firstChunk = result.first { it.contains("## store") }
        assertThat(firstChunk).contains("describes the store section")
    }

    @Test
    fun `block comment before nested object key appears in first chunk of that nested section`() {
        val source = """
            {
                /* Retrieval settings control how search results are ranked.
                   Uses Reciprocal Rank Fusion. */
                "retrieval": {
                    "mode": "hybrid",
                    "topK": 10
                }
            }
        """.trimIndent()
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val firstRetrievalChunk = result.first { it.contains("## retrieval") }
        assertThat(firstRetrievalChunk).contains("Retrieval settings control how search results are ranked")
        assertThat(firstRetrievalChunk).contains("Reciprocal Rank Fusion")
    }

    @Test
    fun `comment before nested array key appears in first chunk of that nested section`() {
        val source = """
            {
                // list of allowed hosts
                "hosts": ["host1", "host2"]
            }
        """.trimIndent()
        val result = chunker().chunk(source)
        assertThat(result).isNotEmpty()
        val firstChunk = result.first { it.contains("hosts") }
        assertThat(firstChunk).contains("list of allowed hosts")
    }
}
