package ch.obermuhlner.ezrag.ingestion.office

import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.poifs.crypt.Decryptor
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream

class ExcelToMarkdownConverter {

    fun convert(file: File, passwords: List<String> = emptyList()): String {
        return when {
            file.name.lowercase().endsWith(".xlsx") -> convertXlsx(file, passwords)
            file.name.lowercase().endsWith(".xls")  -> convertXls(file)
            else -> throw IllegalArgumentException("Unsupported Excel format: ${file.name}")
        }
    }

    /**
     * Try to open the file as an unencrypted XLSX first; if that fails with an
     * OLE2 / encryption exception, iterate [passwords] until one decrypts it.
     */
    private fun openXlsx(file: File, passwords: List<String>): XSSFWorkbook {
        try {
            return XSSFWorkbook(FileInputStream(file))
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
                    return XSSFWorkbook(decryptedStream)
                }
            } catch (_: Exception) {
                // wrong password or unrelated error — try next
            }
        }

        throw IllegalStateException("Cannot open encrypted file: no valid password matched — ${file.name}")
    }

    private fun convertXlsx(file: File, passwords: List<String> = emptyList()): String {
        val sb = StringBuilder()
        val formatter = DataFormatter()
        openXlsx(file, passwords).use { wb ->
            for (sheetIndex in 0 until wb.numberOfSheets) {
                val sheet = wb.getSheetAt(sheetIndex)
                val sheetName = wb.getSheetName(sheetIndex)
                sb.appendLine("## $sheetName")
                appendSheetAsMarkdownTable(sb, formatter, sheet.rowIterator())
            }
        }
        return sb.toString().trimEnd()
    }

    private fun convertXls(file: File): String {
        val sb = StringBuilder()
        val formatter = DataFormatter()
        FileInputStream(file).use { fis ->
            HSSFWorkbook(fis).use { wb ->
                for (sheetIndex in 0 until wb.numberOfSheets) {
                    val sheet = wb.getSheetAt(sheetIndex)
                    val sheetName = wb.getSheetName(sheetIndex)
                    sb.appendLine("## $sheetName")
                    appendSheetAsMarkdownTable(sb, formatter, sheet.rowIterator())
                }
            }
        }
        return sb.toString().trimEnd()
    }

    private fun appendSheetAsMarkdownTable(
        sb: StringBuilder,
        formatter: DataFormatter,
        rowIterator: Iterator<org.apache.poi.ss.usermodel.Row>,
    ) {
        var isFirstRow = true
        while (rowIterator.hasNext()) {
            val row = rowIterator.next()

            // Collect all cell values in this row
            val lastCellNum = row.lastCellNum.toInt()
            if (lastCellNum <= 0) continue // skip empty rows

            val cellValues = (0 until lastCellNum).map { cellIndex ->
                val cell = row.getCell(cellIndex)
                if (cell == null) "" else formatter.formatCellValue(cell).trim()
            }

            // Skip rows where all cells are blank
            if (cellValues.all { it.isEmpty() }) continue

            sb.appendLine("| ${cellValues.joinToString(" | ")} |")

            if (isFirstRow) {
                // Add the separator row after the header
                val separator = cellValues.joinToString(" | ") { "---" }
                sb.appendLine("| $separator |")
                isFirstRow = false
            }
        }
    }
}
