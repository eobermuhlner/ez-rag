package ch.obermuhlner.ezrag.ingestion.office

import org.apache.poi.hwpf.HWPFDocument
import org.apache.poi.poifs.crypt.Decryptor
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import org.apache.poi.xwpf.usermodel.XWPFTable
import java.io.File
import java.io.FileInputStream

class WordToMarkdownConverter {

    fun convert(file: File, passwords: List<String> = emptyList()): String {
        return when {
            file.name.lowercase().endsWith(".docx") -> convertDocx(file, passwords)
            file.name.lowercase().endsWith(".doc")  -> convertDoc(file)
            else -> throw IllegalArgumentException("Unsupported Word format: ${file.name}")
        }
    }

    /**
     * Try to open the file as an unencrypted DOCX first; if that fails with an
     * OLE2 / encryption exception, iterate [passwords] until one decrypts it.
     */
    private fun openDocx(file: File, passwords: List<String>): XWPFDocument {
        try {
            return XWPFDocument(FileInputStream(file))
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
                    return XWPFDocument(decryptedStream)
                }
            } catch (_: Exception) {
                // wrong password or unrelated error — try next
            }
        }

        throw IllegalStateException("Cannot open encrypted file: no valid password matched — ${file.name}")
    }

    private fun convertDocx(file: File, passwords: List<String> = emptyList()): String {
        val sb = StringBuilder()
        openDocx(file, passwords).use { doc ->
                // Comments are stored separately; we collect their IDs but do not
                // include them in body extraction (comment text is never in body runs)
                // The getComments() call returns null if no comments part exists.
                @Suppress("UNNECESSARY_SAFE_CALL")
                val commentIds = doc.comments?.mapNotNull { it.id }?.toSet() ?: emptySet()

                // Process body elements in document order (paragraphs and tables)
                for (element in doc.bodyElements) {
                    when (element) {
                        is XWPFParagraph -> {
                            val md = paragraphToMarkdown(element, commentIds)
                            if (md.isNotEmpty()) {
                                sb.appendLine(md)
                            }
                        }
                        is XWPFTable -> {
                            sb.appendLine(tableToMarkdown(element))
                        }
                    }
                }

                // Append footnotes
                doc.footnotes?.forEach { footnote ->
                    val footnoteText = footnote.paragraphs.joinToString("\n") { para ->
                        para.runs.joinToString("") { run -> run.text() ?: "" }.trim()
                    }.trim()
                    if (footnoteText.isNotEmpty()) {
                        sb.appendLine(footnoteText)
                    }
                }

                // Append endnotes
                doc.endnotes?.forEach { endnote ->
                    val endnoteText = endnote.paragraphs.joinToString("\n") { para ->
                        para.runs.joinToString("") { run -> run.text() ?: "" }.trim()
                    }.trim()
                    if (endnoteText.isNotEmpty()) {
                        sb.appendLine(endnoteText)
                    }
                }
        }
        return sb.toString().trimEnd()
    }

    private fun paragraphToMarkdown(paragraph: XWPFParagraph, commentIds: Set<String>): String {
        // Get the paragraph style to determine heading level
        val styleName = paragraph.style ?: ""
        val headingLevel = extractHeadingLevel(styleName)

        // Get paragraph text from runs (skip comment runs)
        val text = paragraph.runs
            .filter { run ->
                // Filter out comment annotation runs
                run.text() != null
            }
            .joinToString("") { run -> run.text() ?: "" }
            .trim()

        if (text.isEmpty()) return ""

        return if (headingLevel > 0) {
            "${"#".repeat(headingLevel)} $text"
        } else {
            text
        }
    }

    private fun extractHeadingLevel(styleName: String): Int {
        // POI uses style names like "Heading 1", "Heading 2", etc.
        val normalized = styleName.lowercase().trim()
        return when {
            normalized == "heading 1" || normalized == "heading1" -> 1
            normalized == "heading 2" || normalized == "heading2" -> 2
            normalized == "heading 3" || normalized == "heading3" -> 3
            normalized == "heading 4" || normalized == "heading4" -> 4
            normalized == "heading 5" || normalized == "heading5" -> 5
            normalized == "heading 6" || normalized == "heading6" -> 6
            else -> 0
        }
    }

    private fun tableToMarkdown(table: XWPFTable): String {
        val sb = StringBuilder()
        val rows = table.rows
        if (rows.isEmpty()) return ""

        // Process each row
        rows.forEachIndexed { rowIndex, row ->
            val cells = row.tableCells
            val cellTexts = cells.map { cell ->
                cell.paragraphs.joinToString(" ") { para ->
                    para.runs.joinToString("") { run -> run.text() ?: "" }.trim()
                }.trim()
            }
            sb.appendLine("| ${cellTexts.joinToString(" | ")} |")

            // Add separator row after the header row
            if (rowIndex == 0) {
                val separator = cells.joinToString(" | ") { "---" }
                sb.appendLine("| $separator |")
            }
        }
        return sb.toString().trimEnd()
    }

    private fun convertDoc(file: File): String {
        val sb = StringBuilder()
        FileInputStream(file).use { fis ->
            HWPFDocument(fis).use { doc ->
                val range = doc.range
                for (i in 0 until range.numParagraphs()) {
                    val para = range.getParagraph(i)
                    val text = para.text().trim().trimEnd('\r', '\n')
                    if (text.isEmpty()) continue

                    // Check outline level for headings
                    val outlineLevel = try { para.lvl } catch (e: Exception) { 9 }
                    if (outlineLevel in 0..5) {
                        sb.appendLine("${"#".repeat(outlineLevel + 1)} $text")
                    } else {
                        sb.appendLine(text)
                    }
                }
            }
        }
        return sb.toString().trimEnd()
    }
}
