package ch.obermuhlner.ezrag.ingestion.office

import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.crypt.EncryptionMode
import org.apache.poi.poifs.crypt.Encryptor
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.xwpf.usermodel.XWPFDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Creates an AES-encrypted password-protected .docx test fixture.
 */
object EncryptedWordFixtureGenerator {

    const val CORRECT_PASSWORD = "correct-password-123"

    val fixturesDir = File("src/test/resources/fixtures").also { it.mkdirs() }
    val encryptedDocxFile = File(fixturesDir, "protected.docx")

    fun createEncryptedDocxFixture(file: File, password: String = CORRECT_PASSWORD) {
        if (file.exists()) return  // POIFS encryption uses a random salt; content comparison is not possible
        // First build the DOCX in memory
        val doc = XWPFDocument()
        val para = doc.createParagraph()
        para.createRun().setText("SecretContent: This is the protected document content.")

        // Write DOCX to a byte array
        val baos = java.io.ByteArrayOutputStream()
        doc.use { it.write(baos) }
        val docBytes = baos.toByteArray()

        // Encrypt using POI's OOXML encryption
        val poifs = POIFSFileSystem()
        val encInfo = EncryptionInfo(EncryptionMode.agile)
        val enc = encInfo.encryptor
        enc.confirmPassword(password)

        enc.getDataStream(poifs).use { os ->
            os.write(docBytes)
        }

        FileOutputStream(file).use { fos ->
            poifs.writeFilesystem(fos)
        }
        poifs.close()
    }
}
