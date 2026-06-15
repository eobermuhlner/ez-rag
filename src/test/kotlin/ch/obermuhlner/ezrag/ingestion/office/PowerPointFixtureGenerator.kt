package ch.obermuhlner.ezrag.ingestion.office

import org.apache.poi.ooxml.POIXMLDocumentPart
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xslf.usermodel.XSLFNotes
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextBody
import org.openxmlformats.schemas.drawingml.x2006.main.CTTextParagraph
import org.openxmlformats.schemas.presentationml.x2006.main.CTNotesSlide
import org.openxmlformats.schemas.presentationml.x2006.main.CTShape
import java.io.File
import java.io.FileOutputStream

/**
 * Creates PowerPoint test fixtures used by PowerPointToMarkdownConverterTest and IngestServiceTest.
 */
object PowerPointFixtureGenerator {

    val fixturesDir = File("src/test/resources/fixtures").also { it.mkdirs() }
    val pptxFile = File(fixturesDir, "sample.pptx")

    fun createPptxFixture(file: File) {
        XMLSlideShow().use { ppt ->
            // Slide 1: Title "Introduction", body text, speaker notes
            val slide1 = ppt.createSlide()

            // Add a title text box
            val title1 = slide1.createTextBox()
            title1.text = "Introduction"
            title1.setAnchor(java.awt.Rectangle(0, 0, 600, 100))

            // Add a body text box
            val body1 = slide1.createTextBox()
            body1.text = "This is the introduction slide body text."
            body1.setAnchor(java.awt.Rectangle(0, 100, 600, 300))

            // Add speaker notes via the notes slide
            // Use reflection to access the private createNotesSlide method
            val createNotesSlideMethod = XMLSlideShow::class.java.getDeclaredMethod(
                "createNotesSlide",
                org.apache.poi.xslf.usermodel.XSLFSlide::class.java
            )
            createNotesSlideMethod.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val notes1 = createNotesSlideMethod.invoke(ppt, slide1) as XSLFNotes
            val notesBox = notes1.createTextBox()
            notesBox.text = "SpeakerNotes: This is the speaker note for slide 1."
            notesBox.setAnchor(java.awt.Rectangle(0, 0, 600, 100))

            // Slide 2: Title "Summary", body text (no speaker notes)
            val slide2 = ppt.createSlide()

            val title2 = slide2.createTextBox()
            title2.text = "Summary"
            title2.setAnchor(java.awt.Rectangle(0, 0, 600, 100))

            val body2 = slide2.createTextBox()
            body2.text = "This is the summary slide body text."
            body2.setAnchor(java.awt.Rectangle(0, 100, 600, 300))

            FileOutputStream(file).use { fos ->
                ppt.write(fos)
            }
        }
    }
}
