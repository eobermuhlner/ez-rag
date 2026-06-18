package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class JsonDocumentReaderTest {

    @Test
    fun `json file with root array produces non-empty document list`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("data.json").toFile()
        file.writeText("""
            [
                {"name": "Alice", "age": 30},
                {"name": "Bob", "age": 25},
                {"name": "Carol", "age": 35}
            ]
        """.trimIndent())

        val reader = JsonDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).isNotEmpty()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("Alice")
        assertThat(allText).contains("Bob")
        assertThat(allText).contains("Carol")
    }

    @Test
    fun `json file with root array combined text contains all element content`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("array.json").toFile()
        // Use unique markers to verify each element is present
        file.writeText("""
            [
                {"id": "ELEM_AAA", "val": "one"},
                {"id": "ELEM_BBB", "val": "two"},
                {"id": "ELEM_CCC", "val": "three"},
                {"id": "ELEM_DDD", "val": "four"},
                {"id": "ELEM_EEE", "val": "five"}
            ]
        """.trimIndent())

        val reader = JsonDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("ELEM_AAA")
        assertThat(allText).contains("ELEM_BBB")
        assertThat(allText).contains("ELEM_CCC")
        assertThat(allText).contains("ELEM_DDD")
        assertThat(allText).contains("ELEM_EEE")
    }

    @Test
    fun `empty json file returns empty document list without throwing`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("empty.json").toFile()
        file.writeText("")

        val reader = JsonDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).isEmpty()
    }

    @Test
    fun `syntactically invalid json file throws IllegalArgumentException containing filename`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("broken.json").toFile()
        file.writeText("{ this is not valid json [}")

        val reader = JsonDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)

        val ex = assertThrows<IllegalArgumentException> {
            reader.read()
        }
        assertThat(ex.message).contains("broken.json")
    }

    @Test
    fun `json file with object root produces documents containing all scalar field values`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("object.json").toFile()
        file.writeText(
            """
            {
                "firstName": "OBJVAL_ALICE",
                "lastName": "OBJVAL_SMITH",
                "age": 42,
                "active": true,
                "score": null
            }
            """.trimIndent()
        )

        val reader = JsonDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).isNotEmpty()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("OBJVAL_ALICE")
        assertThat(allText).contains("OBJVAL_SMITH")
        assertThat(allText).contains("42")
        assertThat(allText).contains("true")
    }

    @Test
    fun `deeply nested json file produces multiple documents whose combined text contains deeply nested value`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("deep.json").toFile()
        // Root object → level1 → level2 → many scalar fields (to force multiple chunks)
        val deepFields = (1..30).joinToString(",\n") { i -> """"deepField$i": "DEEP_VALUE_$i"""" }
        file.writeText(
            """
            {
                "level1": {
                    "level2": {
                        $deepFields
                    }
                }
            }
            """.trimIndent()
        )

        val reader = JsonDocumentReader(file, chunkSize = 100, chunkOverlap = 0)
        val docs = reader.read()

        assertThat(docs.size).isGreaterThan(1)
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("DEEP_VALUE_1")
        assertThat(allText).contains("DEEP_VALUE_30")
    }

    @Test
    fun `jsonc file with line and block comments produces no chunks containing comment text`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("config.jsonc").toFile()
        file.writeText(
            """
            {
                // secret: this is a line comment SECRET_LINE
                "name": "VISIBLE_NAME",
                /* block comment SECRET_BLOCK */
                "count": 42
            }
            """.trimIndent()
        )

        val reader = JsonDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).isNotEmpty()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).doesNotContain("SECRET_LINE")
        assertThat(allText).doesNotContain("SECRET_BLOCK")
        assertThat(allText).contains("VISIBLE_NAME")
        assertThat(allText).contains("42")
    }

    @Test
    fun `jsonc file valid json content is present in chunks`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("data.jsonc").toFile()
        file.writeText(
            """
            {
                // A comment
                "url": "http://example.com",
                /* another comment */
                "active": true
            }
            """.trimIndent()
        )

        val reader = JsonDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        val allText = docs.joinToString("\n") { it.text ?: "" }
        // URL is inside a string literal - comment stripper must not strip it
        assertThat(allText).contains("http://example.com")
        assertThat(allText).contains("true")
    }

    @Test
    fun `jsonc file whose json is invalid after stripping throws IllegalArgumentException`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("broken.jsonc").toFile()
        // After stripping the comment, the remaining content is invalid JSON
        file.writeText(
            """
            {
                // comment
                this is not valid json
            }
            """.trimIndent()
        )

        val reader = JsonDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)

        val ex = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            reader.read()
        }
        assertThat(ex.message).contains("broken.jsonc")
    }

    // ── JSONL tests ─────────────────────────────────────────────────────────

    @Test
    fun `jsonl with 3 valid lines produces same chunk count and combined text as json array of 3 records`(@TempDir tempDir: Path) {
        val records = listOf(
            """{"id": "JSONL_A", "val": "one"}""",
            """{"id": "JSONL_B", "val": "two"}""",
            """{"id": "JSONL_C", "val": "three"}"""
        )

        val jsonlFile = tempDir.resolve("data.jsonl").toFile()
        jsonlFile.writeText(records.joinToString("\n"))

        val jsonFile = tempDir.resolve("data.json").toFile()
        jsonFile.writeText("[${records.joinToString(",")}]")

        val jsonlDocs = JsonDocumentReader(jsonlFile, chunkSize = 1000, chunkOverlap = 200).read()
        val jsonDocs  = JsonDocumentReader(jsonFile,  chunkSize = 1000, chunkOverlap = 200).read()

        assertThat(jsonlDocs.size).isEqualTo(jsonDocs.size)

        val jsonlText = jsonlDocs.joinToString("\n") { it.text ?: "" }
        assertThat(jsonlText).contains("JSONL_A")
        assertThat(jsonlText).contains("JSONL_B")
        assertThat(jsonlText).contains("JSONL_C")

        val jsonText = jsonDocs.joinToString("\n") { it.text ?: "" }
        assertThat(jsonlText).contains("JSONL_A")
        assertThat(jsonText).contains("JSONL_A")
    }

    @Test
    fun `jsonl where line 2 of 4 is malformed produces chunks from lines 1 3 4 and no exception`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("mixed.jsonl").toFile()
        file.writeText(
            """
            {"id": "LINE1_GOOD"}
            {this is not valid json}
            {"id": "LINE3_GOOD"}
            {"id": "LINE4_GOOD"}
            """.trimIndent()
        )

        val docs = JsonDocumentReader(file, chunkSize = 1000, chunkOverlap = 200).read()

        assertThat(docs).isNotEmpty()
        val allText = docs.joinToString("\n") { it.text ?: "" }
        assertThat(allText).contains("LINE1_GOOD")
        assertThat(allText).doesNotContain("not valid json")
        assertThat(allText).contains("LINE3_GOOD")
        assertThat(allText).contains("LINE4_GOOD")
    }

    @Test
    fun `jsonl where every line is malformed returns empty document list without throwing`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("all-bad.jsonl").toFile()
        file.writeText(
            """
            {this is not valid}
            [also bad
            }{
            """.trimIndent()
        )

        val docs = JsonDocumentReader(file, chunkSize = 1000, chunkOverlap = 200).read()

        assertThat(docs).isEmpty()
    }

    @Test
    fun `jsonl blank lines between records are skipped without affecting chunk output`(@TempDir tempDir: Path) {
        val fileWithBlanks = tempDir.resolve("blanks.jsonl").toFile()
        fileWithBlanks.writeText(
            """
            {"id": "BLANK_A"}

            {"id": "BLANK_B"}

            {"id": "BLANK_C"}
            """.trimIndent()
        )

        val fileWithoutBlanks = tempDir.resolve("noblanks.jsonl").toFile()
        fileWithoutBlanks.writeText(
            """
            {"id": "BLANK_A"}
            {"id": "BLANK_B"}
            {"id": "BLANK_C"}
            """.trimIndent()
        )

        val docsWithBlanks    = JsonDocumentReader(fileWithBlanks,    chunkSize = 1000, chunkOverlap = 200).read()
        val docsWithoutBlanks = JsonDocumentReader(fileWithoutBlanks, chunkSize = 1000, chunkOverlap = 200).read()

        assertThat(docsWithBlanks.size).isEqualTo(docsWithoutBlanks.size)

        val text = docsWithBlanks.joinToString("\n") { it.text ?: "" }
        assertThat(text).contains("BLANK_A")
        assertThat(text).contains("BLANK_B")
        assertThat(text).contains("BLANK_C")
    }
}
