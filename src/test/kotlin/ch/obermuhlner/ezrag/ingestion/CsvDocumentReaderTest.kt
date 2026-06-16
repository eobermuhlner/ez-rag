package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CsvDocumentReaderTest {

    @Test
    fun `header-only CSV with no data rows returns empty list`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("empty.csv").toFile()
        file.writeText("col1,col2,col3")

        val reader = CsvDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).isEmpty()
    }

    @Test
    fun `small CSV with few rows produces a single chunk containing header`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("small.csv").toFile()
        file.writeText(
            "Name,Age,City\n" +
            "Alice,30,London\n" +
            "Bob,25,Paris\n"
        )

        val reader = CsvDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).hasSize(1)
        val text = docs[0].text ?: ""
        assertThat(text).contains("Name")
        assertThat(text).contains("Age")
        assertThat(text).contains("City")
        assertThat(text).contains("Alice")
        assertThat(text).contains("Bob")
    }

    @Test
    fun `large CSV produces multiple chunks each containing the header`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("large.csv").toFile()
        // Build a CSV large enough to exceed chunkSize=50 tokens
        val sb = StringBuilder()
        sb.appendLine("FirstName,LastName,Department,Email,Phone")
        repeat(30) { i ->
            sb.appendLine("Employee$i,Smith$i,Engineering$i,emp$i@example.com,555-000-$i")
        }
        file.writeText(sb.toString())

        // Use a small chunkSize to force multiple chunks
        val reader = CsvDocumentReader(file, chunkSize = 50, chunkOverlap = 0)
        val docs = reader.read()

        assertThat(docs.size).isGreaterThan(1)

        // Every chunk must contain all header column names
        for (doc in docs) {
            val text = doc.text ?: ""
            assertThat(text).contains("FirstName")
            assertThat(text).contains("LastName")
            assertThat(text).contains("Department")
        }
    }

    @Test
    fun `no data row appears in more than one chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("nodup.csv").toFile()
        val sb = StringBuilder()
        sb.appendLine("ID,Value")
        // Use IDs with letters to ensure unique matching
        val ids = (0 until 20).map { "ROW${it}X" }
        for (id in ids) {
            sb.appendLine("$id,data-$id")
        }
        file.writeText(sb.toString())

        val reader = CsvDocumentReader(file, chunkSize = 30, chunkOverlap = 0)
        val docs = reader.read()

        assertThat(docs.size).isGreaterThan(1)

        // Each unique row ID should appear in exactly one chunk
        for (id in ids) {
            val chunksContainingRow = docs.count { doc -> (doc.text ?: "").contains(id) }
            assertThat(chunksContainingRow)
                .describedAs("Row '$id' should appear in exactly 1 chunk but appeared in $chunksContainingRow")
                .isEqualTo(1)
        }
    }

    @Test
    fun `no partial rows in any chunk`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("partial.csv").toFile()
        val sb = StringBuilder()
        sb.appendLine("PersonID,PersonName,PersonScore")
        repeat(15) { i ->
            sb.appendLine("PID$i,PNAME$i,PSCORE$i")
        }
        file.writeText(sb.toString())

        // chunkSize large enough to hold a few rows but not all 15
        val reader = CsvDocumentReader(file, chunkSize = 100, chunkOverlap = 0)
        val docs = reader.read()

        // Every occurrence of a person in a chunk should also have all three fields
        for (doc in docs) {
            val text = doc.text ?: ""
            for (i in 0 until 15) {
                val hasPid = text.contains("PID$i")
                val hasPname = text.contains("PNAME$i")
                val hasPscore = text.contains("PSCORE$i")
                if (hasPid || hasPname || hasPscore) {
                    // All three must be present together (no partial row)
                    assertThat(hasPid).describedAs("Row $i: PID$i missing when row partially present").isTrue()
                    assertThat(hasPname).describedAs("Row $i: PNAME$i missing when row partially present").isTrue()
                    assertThat(hasPscore).describedAs("Row $i: PSCORE$i missing when row partially present").isTrue()
                }
            }
        }
    }

    @Test
    fun `CSV with single data row produces one chunk with header`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("single.csv").toFile()
        file.writeText("Col1,Col2\nvalue1,value2\n")

        val reader = CsvDocumentReader(file, chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).hasSize(1)
        val text = docs[0].text ?: ""
        assertThat(text).contains("Col1")
        assertThat(text).contains("Col2")
        assertThat(text).contains("value1")
        assertThat(text).contains("value2")
    }
}
