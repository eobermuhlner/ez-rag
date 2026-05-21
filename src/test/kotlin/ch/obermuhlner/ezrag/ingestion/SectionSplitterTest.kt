package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SectionSplitterTest {

    private val wordCount: (String) -> Int = { text ->
        if (text.isBlank()) 0 else text.trim().split(Regex("\\s+")).size
    }

    @Test
    fun `body under budget returns single chunk`() {
        val splitter = SectionSplitter(chunkSize = 10, chunkOverlap = 2, tokenCounter = wordCount)
        val result = splitter.splitSection("hello world", "")
        assertThat(result).hasSize(1)
        assertThat(result[0]).isEqualTo("hello world")
    }

    @Test
    fun `body exactly at budget returns single chunk`() {
        val splitter = SectionSplitter(chunkSize = 5, chunkOverlap = 1, tokenCounter = wordCount)
        val result = splitter.splitSection("one two three four five", "")
        assertThat(result).hasSize(1)
    }

    @Test
    fun `two paragraphs exceeding combined budget split into two chunks`() {
        val splitter = SectionSplitter(chunkSize = 5, chunkOverlap = 1, tokenCounter = wordCount)
        val body = "one two three four\n\nfive six seven eight"
        val result = splitter.splitSection(body, "")
        assertThat(result).hasSize(2)
        assertThat(result[0]).contains("one two three four")
        assertThat(result[1]).contains("five six seven eight")
    }

    @Test
    fun `two small paragraphs fitting within budget remain in one chunk`() {
        val splitter = SectionSplitter(chunkSize = 10, chunkOverlap = 2, tokenCounter = wordCount)
        val body = "one two\n\nthree four"
        val result = splitter.splitSection(body, "")
        assertThat(result).hasSize(1)
    }

    @Test
    fun `layout-block boundary splits produce no duplicated content`() {
        val splitter = SectionSplitter(chunkSize = 5, chunkOverlap = 2, tokenCounter = wordCount)
        val body = "one two three four\n\nfive six seven eight"
        val result = splitter.splitSection(body, "")
        assertThat(result).hasSize(2)
        val chunk1Words = result[0].split(Regex("\\s+")).toSet()
        val chunk2Words = result[1].split(Regex("\\s+")).toSet()
        assertThat(chunk1Words.intersect(chunk2Words)).isEmpty()
    }

    @Test
    fun `horizontal rule forces flush into two separate chunks`() {
        val splitter = SectionSplitter(chunkSize = 20, chunkOverlap = 2, tokenCounter = wordCount)
        val body = "paragraph one\n\n---\n\nparagraph two"
        val result = splitter.splitSection(body, "")
        assertThat(result).hasSize(2)
        result.forEach { chunk ->
            assertThat(chunk).doesNotContain("---")
        }
    }

    @Test
    fun `horizontal rule with underscore variant also forces flush`() {
        val splitter = SectionSplitter(chunkSize = 20, chunkOverlap = 2, tokenCounter = wordCount)
        val body = "before\n\n___\n\nafter"
        val result = splitter.splitSection(body, "")
        assertThat(result).hasSize(2)
        result.forEach { chunk ->
            assertThat(chunk).doesNotContain("___")
        }
    }

    @Test
    fun `oversized table emitted as single over-budget chunk`() {
        val splitter = SectionSplitter(chunkSize = 3, chunkOverlap = 1, tokenCounter = wordCount)
        val table = "| Name | Age | City |\n|------|-----|------|\n| Alice | 30 | Zurich |"
        val result = splitter.splitSection(table, "")
        assertThat(result).hasSize(1)
        assertThat(result[0]).contains("Alice")
    }

    @Test
    fun `oversized fenced code block emitted as single over-budget chunk`() {
        val splitter = SectionSplitter(chunkSize = 3, chunkOverlap = 1, tokenCounter = wordCount)
        val code = "```kotlin\nfun hello(name: String): String {\n    return \"Hello \$name\"\n}\n```"
        val result = splitter.splitSection(code, "")
        assertThat(result).hasSize(1)
        assertThat(result[0]).contains("fun hello")
    }

    @Test
    fun `oversized bullet list split at item boundaries`() {
        // Each item is 4 words; budget = 5 → items cannot be combined (4+4=8 > 5)
        val splitter = SectionSplitter(chunkSize = 5, chunkOverlap = 1, tokenCounter = wordCount)
        val list = "- item one here\n- item two here\n- item three here"
        val result = splitter.splitSection(list, "")
        assertThat(result).hasSize(3)
        assertThat(result[0]).contains("item one")
        assertThat(result[1]).contains("item two")
        assertThat(result[2]).contains("item three")
    }

    @Test
    fun `single oversized list item falls back to TokenTextSplitter producing multiple chunks`() {
        val splitter = SectionSplitter(chunkSize = 10, chunkOverlap = 2, tokenCounter = wordCount)
        // Single item with 30 words — word-count exceeds budget (10); jtokkit also exceeds chunkSize
        val longItem = "- " + (1..30).joinToString(" ") { "word$it" }
        val result = splitter.splitSection(longItem, "")
        assertThat(result).hasSizeGreaterThan(1)
    }

    @Test
    fun `heading prefix prepended to all returned chunks`() {
        val splitter = SectionSplitter(chunkSize = 5, chunkOverlap = 1, tokenCounter = wordCount)
        val prefix = "# My Section"
        val body = "one two three four\n\nfive six seven eight"
        val result = splitter.splitSection(body, prefix)
        assertThat(result).hasSize(2)
        assertThat(result).allMatch { it.startsWith("$prefix\n") }
    }

    @Test
    fun `empty heading prefix does not add leading newline`() {
        val splitter = SectionSplitter(chunkSize = 10, chunkOverlap = 2, tokenCounter = wordCount)
        val result = splitter.splitSection("hello world", "")
        assertThat(result).hasSize(1)
        assertThat(result[0].startsWith("\n")).isFalse()
    }

    @Test
    fun `prefix token cost counted against budget causes split`() {
        // prefix = "# Section" → wordCount("# Section\n") = 2; budget = 5 - 2 = 3
        // Each paragraph: "one two three" = 3 words, fits alone but not combined (6 > 3)
        val splitter = SectionSplitter(chunkSize = 5, chunkOverlap = 1, tokenCounter = wordCount)
        val prefix = "# Section"
        val body = "one two three\n\nfour five six"
        val result = splitter.splitSection(body, prefix)
        assertThat(result).hasSize(2)
        assertThat(result).allMatch { it.startsWith("$prefix\n") }
    }
}
