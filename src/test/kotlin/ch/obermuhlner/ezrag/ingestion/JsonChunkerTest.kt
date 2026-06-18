package ch.obermuhlner.ezrag.ingestion

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JsonChunkerTest {

    // Use a simple word-count token counter for predictable unit tests
    private val wordCount: (String) -> Int = { text ->
        if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
    }

    private val mapper = ObjectMapper()

    private fun chunker(chunkSize: Int = 1000): JsonChunker =
        JsonChunker(chunkSize = chunkSize, chunkOverlap = 0, tokenCounter = wordCount)

    private fun emptyArray(): ArrayNode = mapper.createArrayNode()

    private fun emptyObject(): ObjectNode = mapper.createObjectNode()

    private fun arrayOf(vararg jsons: String): ArrayNode {
        val arr = mapper.createArrayNode()
        jsons.forEach { arr.add(mapper.readTree(it)) }
        return arr
    }

    @Test
    fun `empty array returns empty list`() {
        val result = chunker().chunk(emptyArray())
        assertThat(result).isEmpty()
    }

    @Test
    fun `small array that fits in one chunk produces one chunk containing all elements`() {
        // Three small objects, all fitting in a large budget
        val arr = arrayOf(
            """{"name":"Alice","age":30}""",
            """{"name":"Bob","age":25}""",
            """{"name":"Carol","age":35}"""
        )
        val result = chunker(chunkSize = 1000).chunk(arr)

        assertThat(result).hasSize(1)
        assertThat(result[0]).contains("Alice")
        assertThat(result[0]).contains("Bob")
        assertThat(result[0]).contains("Carol")
    }

    @Test
    fun `budget batching produces multiple chunks each within token budget`() {
        // Use a budget large enough for one element but not two, to force splits without escape hatch
        // Each element: "## Item N\n\n{\n  \"id\" : \"itemN\",\n  \"value\" : \"N\"\n}" ~ 12 words
        // So budget=20 allows one element, not two (two would be ~22 words)
        val arr = arrayOf(
            """{"id":"item1","value":"one"}""",
            """{"id":"item2","value":"two"}""",
            """{"id":"item3","value":"three"}""",
            """{"id":"item4","value":"four"}""",
            """{"id":"item5","value":"five"}"""
        )
        val chunkSize = 20
        val result = chunker(chunkSize).chunk(arr)

        assertThat(result.size).isGreaterThan(1)
        // Every chunk should be within budget (the single-element escape hatch is for elements
        // that alone exceed the budget; each element here fits alone within budget=20)
        result.forEach { chunk ->
            val tokens = wordCount(chunk)
            assertThat(tokens)
                .withFailMessage("Chunk token count $tokens exceeds budget $chunkSize: '$chunk'")
                .isLessThanOrEqualTo(chunkSize)
        }
    }

    @Test
    fun `no element appears in more than one chunk`() {
        val arr = arrayOf(
            """{"id":"alpha001"}""",
            """{"id":"beta002"}""",
            """{"id":"gamma003"}""",
            """{"id":"delta004"}""",
            """{"id":"epsilon005"}""",
            """{"id":"zeta006"}"""
        )
        val result = chunker(chunkSize = 8).chunk(arr)

        assertThat(result.size).isGreaterThan(1)

        val ids = listOf("alpha001", "beta002", "gamma003", "delta004", "epsilon005", "zeta006")
        ids.forEach { id ->
            val count = result.count { it.contains(id) }
            assertThat(count)
                .withFailMessage("Element '$id' appeared in $count chunks (expected exactly 1)")
                .isEqualTo(1)
        }
    }

    @Test
    fun `single oversized element is emitted as its own chunk`() {
        // Budget so small even one element exceeds it
        val longValue = "word ".repeat(20).trim()
        val arr = arrayOf("""{"content":"$longValue"}""")
        val result = chunker(chunkSize = 3).chunk(arr)

        assertThat(result).hasSize(1)
        assertThat(result[0]).contains(longValue)
    }

    @Test
    fun `batch heading uses Items start-end format for multi-element batch (1-based)`() {
        // 5 elements that all fit in one chunk — should produce heading "## Items 1–5"
        val arr = arrayOf(
            """{"n":"a"}""",
            """{"n":"b"}""",
            """{"n":"c"}""",
            """{"n":"d"}""",
            """{"n":"e"}"""
        )
        val result = chunker(chunkSize = 1000).chunk(arr)

        assertThat(result).hasSize(1)
        assertThat(result[0]).contains("## Items 1–5")
    }

    @Test
    fun `single-element batch heading uses Item index format (1-based)`() {
        // Very small budget so each element is alone
        val arr = arrayOf(
            """{"n":"alpha001"}""",
            """{"n":"beta002"}"""
        )
        // Force each element into its own chunk
        val result = chunker(chunkSize = 5).chunk(arr)

        // Each chunk should use "## Item N" (1-based) when it contains exactly one element
        assertThat(result).isNotEmpty()
        // At least one chunk should have "## Item 1"
        val hasItem1 = result.any { chunk -> chunk.contains("## Item 1") }
        val hasItem2 = result.any { chunk -> chunk.contains("## Item 2") }
        assertThat(hasItem1).isTrue()
        assertThat(hasItem2).isTrue()
    }

    @Test
    fun `multi-element batch heading uses correct 1-based range`() {
        // 6 elements with budget forcing batches of 2
        val arr = arrayOf(
            """{"k":"aaa001"}""",
            """{"k":"bbb002"}""",
            """{"k":"ccc003"}""",
            """{"k":"ddd004"}""",
            """{"k":"eee005"}""",
            """{"k":"fff006"}"""
        )
        // Budget set to allow ~2 elements per chunk
        val result = chunker(chunkSize = 15).chunk(arr)

        // Chunks should have range headings like "## Items 1–2", "## Items 3–4", etc.
        assertThat(result.size).isGreaterThan(1)
        // First batch heading should be "## Items 1–..."
        val firstChunkHasRangeHeading = result[0].contains("## Items 1–")
        assertThat(firstChunkHasRangeHeading).isTrue()
    }

    // ---- ObjectNode dispatch tests (Task 02) ----

    @Test
    fun `empty object returns empty list`() {
        val result = chunker().chunk(emptyObject())
        assertThat(result).isEmpty()
    }

    @Test
    fun `object with five short scalar fields all fitting in budget produces exactly one chunk containing all field values`() {
        val obj = mapper.readTree(
            """{"name":"Alice","age":30,"city":"London","active":true,"score":null}"""
        )
        val result = chunker(chunkSize = 1000).chunk(obj)

        assertThat(result).hasSize(1)
        val text = result[0]
        assertThat(text).contains("name")
        assertThat(text).contains("Alice")
        assertThat(text).contains("age")
        assertThat(text).contains("30")
        assertThat(text).contains("city")
        assertThat(text).contains("London")
        assertThat(text).contains("active")
        assertThat(text).contains("score")
    }

    @Test
    fun `object with many short scalar fields produces fewer chunks than there are fields`() {
        // 20 fields — with a generous budget, all should batch into fewer than 20 chunks
        val json = (1..20).joinToString(",", "{", "}") { i -> """"field$i":"value$i"""" }
        val obj = mapper.readTree(json)
        val result = chunker(chunkSize = 1000).chunk(obj)

        assertThat(result.size).isLessThan(20)
        // All field values should appear in combined text
        val allText = result.joinToString("\n")
        for (i in 1..20) {
            assertThat(allText).contains("value$i")
        }
    }

    @Test
    fun `each produced chunk stays within the configured token budget`() {
        // 10 fields, tight budget to force multiple chunks — but each chunk must stay within budget
        val json = (1..10).joinToString(",", "{", "}") { i -> """"fieldName$i":"fieldValue$i"""" }
        val obj = mapper.readTree(json)
        val budget = 10
        val result = chunker(chunkSize = budget).chunk(obj)

        assertThat(result).isNotEmpty()
        result.forEach { chunk ->
            val tokens = wordCount(chunk)
            assertThat(tokens)
                .withFailMessage("Chunk token count $tokens exceeds budget $budget: '$chunk'")
                .isLessThanOrEqualTo(budget)
        }
    }

    @Test
    fun `every produced chunk begins with a double-hash heading`() {
        val obj = mapper.readTree("""{"alpha":"one","beta":"two","gamma":"three"}""")
        val result = chunker(chunkSize = 1000).chunk(obj)

        assertThat(result).isNotEmpty()
        result.forEach { chunk ->
            assertThat(chunk.trimStart()).startsWith("##")
        }
    }

    @Test
    fun `small nested object is rendered as fenced json block within the accumulator chunk not as a separate chunk`() {
        val obj = mapper.readTree(
            """{"name":"Alice","address":{"street":"Main St","city":"Anytown"}}"""
        )
        // With a large budget, everything (including the nested object) should fit in one chunk
        val result = chunker(chunkSize = 1000).chunk(obj)

        assertThat(result).hasSize(1)
        val text = result[0]
        // The nested object should be rendered as a fenced code block
        assertThat(text).contains("```json")
        assertThat(text).contains("street")
        assertThat(text).contains("Main St")
        // It should NOT have been split into a separate chunk
        assertThat(result.size).isEqualTo(1)
    }

    // ---- Recursive re-chunking tests (Task 03) ----

    @Test
    fun `nested object too large produces sub-chunks with heading parent to childKey`() {
        // Create a parent object with a "details" field that contains a large nested object
        // The nested object has enough fields to exceed a tight budget
        val nestedFields = (1..30).joinToString(",") { i -> """"nestedField$i":"nestedValue$i"""" }
        val json = """{"name":"PARENT_NAME","details":{$nestedFields}}"""
        val obj = mapper.readTree(json)

        // Budget: enough for one scalar field, but not the entire nested object
        val result = chunker(chunkSize = 20).chunk(obj)

        // Should produce multiple chunks
        assertThat(result.size).isGreaterThan(1)
        // At least one chunk should have the sub-heading for "details"
        val allText = result.joinToString("\n")
        assertThat(allText).contains("details")
        // The heading should extend to ## details
        val hasDetailsHeading = result.any { it.trimStart().startsWith("## details") }
        assertThat(hasDetailsHeading)
            .withFailMessage("Expected a chunk with heading '## details' but got:\n$allText")
            .isTrue()
    }

    @Test
    fun `content before oversized key appears in a separate chunk from recursed sub-chunks`() {
        // A parent object: scalar fields before an oversized nested object
        // The scalar fields should go into one chunk, and the recursed sub-chunks into different chunk(s)
        val nestedFields = (1..30).joinToString(",") { i -> """"nestedField$i":"nestedValue$i"""" }
        val json = """{"beforeKey":"BEFORE_VALUE","bigNested":{$nestedFields}}"""
        val obj = mapper.readTree(json)

        val result = chunker(chunkSize = 20).chunk(obj)

        // The "BEFORE_VALUE" and "nestedField1" content should be in different chunks
        val beforeChunks = result.filter { it.contains("BEFORE_VALUE") }
        val nestedChunks = result.filter { it.contains("nestedField1") }

        assertThat(beforeChunks).isNotEmpty()
        assertThat(nestedChunks).isNotEmpty()
        // They should not be the same chunk — content from before the flush boundary must be separate
        val sharedChunk = result.find { it.contains("BEFORE_VALUE") && it.contains("nestedField1") }
        assertThat(sharedChunk)
            .withFailMessage("Expected BEFORE_VALUE and nestedField1 to be in separate chunks")
            .isNull()
    }

    @Test
    fun `nested array too large applies array batching at the nested level`() {
        // Parent object with a large array that exceeds budget when serialized
        val elements = (1..20).joinToString(",") { i -> """"ELEM_${i}X"""" }
        val json = """{"items":[$elements]}"""
        val obj = mapper.readTree(json)

        val result = chunker(chunkSize = 15).chunk(obj)

        // Should produce multiple chunks with array sub-headings under "items"
        assertThat(result.size).isGreaterThan(1)
        val allText = result.joinToString("\n")
        // Elements should be distributed across chunks
        assertThat(allText).contains("ELEM_1X")
        assertThat(allText).contains("ELEM_20X")
        // The heading should contain "items" as part of the path
        val hasItemsHeading = result.any { it.contains("items") && it.trimStart().startsWith("## ") }
        assertThat(hasItemsHeading)
            .withFailMessage("Expected a chunk with a heading containing 'items' but got:\n$allText")
            .isTrue()
    }

    @Test
    fun `long string value produces multiple chunks each with parent key heading`() {
        // A very long string value (many words) should be split across multiple chunks
        // Each chunk must carry the parent key's heading path
        val longString = "word ".repeat(200).trim() // 200 words
        val json = """{"story":"$longString"}"""
        val obj = mapper.readTree(json)

        // Budget=50 means one chunk can hold ~50 words from the long string
        val result = chunker(chunkSize = 50).chunk(obj)

        // Should produce multiple chunks
        assertThat(result.size).isGreaterThan(1)
        // Every chunk should have a heading that includes the path related to "story"
        result.forEach { chunk ->
            assertThat(chunk.trimStart()).startsWith("##")
        }
        // All chunks combined should contain the long string content
        val allText = result.joinToString("\n")
        assertThat(allText).contains("word")
    }

    @Test
    fun `heading path at depth 3 renders as level1 to level2 to level3`() {
        // Create a 3-level deep structure: root → mid → leaf
        // The leaf is an object with enough fields to produce chunks
        val leafFields = (1..10).joinToString(",") { i -> """"leafField$i":"leafValue$i"""" }
        val json = """{"mid":{"leaf":{$leafFields}}}"""
        val obj = mapper.readTree(json)

        // Use a tight budget to force sub-chunks at the leaf level
        val result = chunker(chunkSize = 15).chunk(obj)

        assertThat(result).isNotEmpty()
        // All chunks should have headings with the depth-3 path: ## mid → leaf
        result.forEach { chunk ->
            assertThat(chunk.trimStart()).startsWith("## mid → leaf")
        }
    }
}
