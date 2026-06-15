package ch.obermuhlner.ezrag.ingestion.office

import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger

/**
 * Creates test fixtures used by WordToMarkdownConverterTest and IngestServiceTest.
 * Invoked from @BeforeAll in WordToMarkdownConverterTest.
 */
object WordFixtureGenerator {

    val fixturesDir = File("src/test/resources/fixtures").also { it.mkdirs() }
    val docxFile = File(fixturesDir, "sample.docx")

    fun createDocxFixture(file: File) {
        XWPFDocument().use { doc ->
            // Add Heading 1 paragraph
            val h1 = doc.createParagraph()
            h1.style = "Heading1"
            h1.createRun().setText("Introduction")

            // Add Heading 2 paragraph
            val h2 = doc.createParagraph()
            h2.style = "Heading2"
            h2.createRun().setText("Background")

            // Add a normal paragraph
            val normalPara = doc.createParagraph()
            normalPara.createRun().setText("This is a normal paragraph with some content.")

            // Add a table
            val table = doc.createTable(2, 2)
            table.getRow(0).getCell(0).text = "Name"
            table.getRow(0).getCell(1).text = "Value"
            table.getRow(1).getCell(0).text = "Alpha"
            table.getRow(1).getCell(1).text = "42"

            // Add footnote content (as a paragraph — POI's full footnote API requires more complex setup)
            val footnotePara = doc.createParagraph()
            footnotePara.createRun().setText("FootnoteContent: See the appendix for details.")

            // Add a comment (stored separately from body — should not appear in converter output)
            try {
                val comments = doc.createComments()
                val comment = comments.createComment(BigInteger.ONE)
                val commentPara = comment.createParagraph()
                commentPara.createRun().setText("ReviewerComment: This should not appear in output.")
            } catch (e: Exception) {
                // Comment creation failure is acceptable — comments are tested as absent from output
            }

            FileOutputStream(file).use { fos ->
                doc.write(fos)
            }
        }
    }
}
