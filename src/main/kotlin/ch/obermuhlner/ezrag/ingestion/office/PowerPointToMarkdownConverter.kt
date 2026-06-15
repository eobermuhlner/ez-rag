package ch.obermuhlner.ezrag.ingestion.office

import org.apache.poi.hslf.usermodel.HSLFSlideShow
import org.apache.poi.poifs.crypt.Decryptor
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.xslf.usermodel.XMLSlideShow
import java.io.File
import java.io.FileInputStream

class PowerPointToMarkdownConverter {

    fun convert(file: File, passwords: List<String> = emptyList()): String {
        return when {
            file.name.lowercase().endsWith(".pptx") -> convertPptx(file, passwords)
            file.name.lowercase().endsWith(".ppt")  -> convertPpt(file)
            else -> throw IllegalArgumentException("Unsupported PowerPoint format: ${file.name}")
        }
    }

    /**
     * Try to open the file as an unencrypted PPTX first; if that fails with an
     * OLE2 / encryption exception, iterate [passwords] until one decrypts it.
     */
    private fun openPptx(file: File, passwords: List<String>): XMLSlideShow {
        try {
            return XMLSlideShow(FileInputStream(file))
        } catch (e: Exception) {
            // The file may be an OLE2-wrapped encrypted OOXML document
        }

        if (passwords.isEmpty()) {
            throw IllegalStateException("Cannot open encrypted file: no valid password supplied — ${file.name}")
        }

        for (password in passwords) {
            try {
                val poifs = POIFSFileSystem(file)
                val encInfo = EncryptionInfo(poifs)
                val decryptor = Decryptor.getInstance(encInfo)
                if (decryptor.verifyPassword(password)) {
                    val decryptedStream = decryptor.getDataStream(poifs)
                    return XMLSlideShow(decryptedStream)
                }
            } catch (_: Exception) {
                // wrong password or unrelated error — try next
            }
        }

        throw IllegalStateException("Cannot open encrypted file: no valid password matched — ${file.name}")
    }

    private fun convertPptx(file: File, passwords: List<String> = emptyList()): String {
        val sb = StringBuilder()
        openPptx(file, passwords).use { ppt ->
                ppt.slides.forEachIndexed { index, slide ->
                    val slideNumber = index + 1

                    // Collect all text from text shapes on the slide
                    val texts = slide.shapes
                        .filterIsInstance<org.apache.poi.xslf.usermodel.XSLFTextShape>()
                        .map { shape -> shape.text.trim() }
                        .filter { it.isNotEmpty() }

                    // The first non-empty text shape is treated as the title
                    val title = texts.firstOrNull() ?: ""
                    val heading = if (title.isNotEmpty()) {
                        "## Slide $slideNumber: $title"
                    } else {
                        "## Slide $slideNumber"
                    }
                    sb.appendLine(heading)

                    // Remaining text shapes are body content
                    texts.drop(1).forEach { bodyText ->
                        sb.appendLine(bodyText)
                    }

                    // Append speaker notes
                    val notes = slide.notes
                    if (notes != null) {
                        val noteTexts = notes.shapes
                            .filterIsInstance<org.apache.poi.xslf.usermodel.XSLFTextShape>()
                            .map { shape -> shape.text.trim() }
                            .filter { it.isNotEmpty() }
                        noteTexts.forEach { noteText ->
                            sb.appendLine(noteText)
                        }
                    }
                }
        }
        return sb.toString().trimEnd()
    }

    private fun convertPpt(file: File): String {
        val sb = StringBuilder()
        FileInputStream(file).use { fis ->
            HSLFSlideShow(fis).use { ppt ->
                ppt.slides.forEachIndexed { index, slide ->
                    val slideNumber = index + 1

                    // Collect all text from text shapes
                    val texts = slide.shapes
                        .filterIsInstance<org.apache.poi.hslf.usermodel.HSLFTextShape>()
                        .map { shape -> shape.text.trim() }
                        .filter { it.isNotEmpty() }

                    val title = texts.firstOrNull() ?: ""
                    val heading = if (title.isNotEmpty()) {
                        "## Slide $slideNumber: $title"
                    } else {
                        "## Slide $slideNumber"
                    }
                    sb.appendLine(heading)

                    texts.drop(1).forEach { bodyText ->
                        sb.appendLine(bodyText)
                    }

                    // Append speaker notes
                    val notes = slide.notes
                    if (notes != null) {
                        val noteTexts = notes.shapes
                            .filterIsInstance<org.apache.poi.hslf.usermodel.HSLFTextShape>()
                            .map { shape -> shape.text.trim() }
                            .filter { it.isNotEmpty() }
                        noteTexts.forEach { noteText ->
                            sb.appendLine(noteText)
                        }
                    }
                }
            }
        }
        return sb.toString().trimEnd()
    }
}
