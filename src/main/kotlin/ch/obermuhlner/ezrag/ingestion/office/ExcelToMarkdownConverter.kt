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
     * Extracts structured sheet data from an Excel file.
     * Returns a list of triples: (sheetName, headerRow, dataRows).
     * Each element in headerRow and dataRows is a list of cell values.
     */
    fun extractSheets(file: File, passwords: List<String> = emptyList()): List<Triple<String, List<String>, List<List<String>>>> {
        return when {
            file.name.lowercase().endsWith(".xlsx") -> extractSheetsXlsx(file, passwords)
            file.name.lowercase().endsWith(".xls")  -> extractSheetsXls(file)
            else -> throw IllegalArgumentException("Unsupported Excel format: ${file.name}")
        }
    }

    private fun extractSheetsXlsx(file: File, passwords: List<String>): List<Triple<String, List<String>, List<List<String>>>> {
        val formatter = DataFormatter()
        return openXlsx(file, passwords).use { wb ->
            (0 until wb.numberOfSheets).map { sheetIndex ->
                val sheet = wb.getSheetAt(sheetIndex)
                val sheetName = wb.getSheetName(sheetIndex)
                extractSheetData(formatter, sheet.rowIterator(), sheetName)
            }
        }
    }

    private fun extractSheetsXls(file: File): List<Triple<String, List<String>, List<List<String>>>> {
        val formatter = DataFormatter()
        return FileInputStream(file).use { fis ->
            HSSFWorkbook(fis).use { wb ->
                (0 until wb.numberOfSheets).map { sheetIndex ->
                    val sheet = wb.getSheetAt(sheetIndex)
                    val sheetName = wb.getSheetName(sheetIndex)
                    extractSheetData(formatter, sheet.rowIterator(), sheetName)
                }
            }
        }
    }

    private fun extractSheetData(
        formatter: DataFormatter,
        rowIterator: Iterator<org.apache.poi.ss.usermodel.Row>,
        sheetName: String,
    ): Triple<String, List<String>, List<List<String>>> {
        val allRows = mutableListOf<List<String>>()
        while (rowIterator.hasNext()) {
            val row = rowIterator.next()
            val lastCellNum = row.lastCellNum.toInt()
            if (lastCellNum <= 0) continue
            val cellValues = (0 until lastCellNum).map { cellIndex ->
                val cell = row.getCell(cellIndex)
                if (cell == null) "" else formatter.formatCellValue(cell).trim()
            }
            if (cellValues.all { it.isEmpty() }) continue
            allRows.add(cellValues)
        }
        val header = if (allRows.isNotEmpty()) allRows[0] else emptyList()
        val dataRows = if (allRows.size > 1) allRows.subList(1, allRows.size) else emptyList()
        return Triple(sheetName, header, dataRows)
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
