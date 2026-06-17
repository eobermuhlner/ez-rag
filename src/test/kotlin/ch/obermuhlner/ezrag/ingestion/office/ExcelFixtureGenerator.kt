package ch.obermuhlner.ezrag.ingestion.office

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File

/**
 * Creates Excel test fixtures used by ExcelToMarkdownConverterTest and IngestServiceTest.
 * Invoked from @BeforeAll in ExcelToMarkdownConverterTest.
 */
object ExcelFixtureGenerator {

    val fixturesDir = File("src/test/resources/fixtures").also { it.mkdirs() }
    val xlsxFile = File(fixturesDir, "sample.xlsx")
    val xlsxManyRowsFile = File(fixturesDir, "sample_many_rows.xlsx")

    fun createXlsxFixture(file: File) {
        writeIfChanged(file) { baos ->
            XSSFWorkbook().use { wb ->
                // Sheet 1: "Summary" with columns Name, Value
                val summarySheet = wb.createSheet("Summary")
                val summaryHeader = summarySheet.createRow(0)
                summaryHeader.createCell(0).setCellValue("Name")
                summaryHeader.createCell(1).setCellValue("Value")
                val summaryRow1 = summarySheet.createRow(1)
                summaryRow1.createCell(0).setCellValue("Alpha")
                summaryRow1.createCell(1).setCellValue("100")
                val summaryRow2 = summarySheet.createRow(2)
                summaryRow2.createCell(0).setCellValue("Beta")
                summaryRow2.createCell(1).setCellValue("200")

                // Sheet 2: "Details" with columns Item, Count, Notes
                val detailsSheet = wb.createSheet("Details")
                val detailsHeader = detailsSheet.createRow(0)
                detailsHeader.createCell(0).setCellValue("Item")
                detailsHeader.createCell(1).setCellValue("Count")
                detailsHeader.createCell(2).setCellValue("Notes")
                val detailsRow1 = detailsSheet.createRow(1)
                detailsRow1.createCell(0).setCellValue("Widget")
                detailsRow1.createCell(1).setCellValue("42")
                detailsRow1.createCell(2).setCellValue("First item")
                val detailsRow2 = detailsSheet.createRow(2)
                detailsRow2.createCell(0).setCellValue("Gadget")
                detailsRow2.createCell(1).setCellValue("7")
                detailsRow2.createCell(2).setCellValue("Second item")

                wb.write(baos)
            }
        }
    }

    /**
     * Creates an xlsx fixture with a single "Data" sheet containing 50 data rows,
     * used to verify row-chunking behaviour in ExcelDocumentReader.
     */
    fun createXlsxManyRowsFixture(file: File) {
        writeIfChanged(file) { baos ->
            XSSFWorkbook().use { wb ->
                val sheet = wb.createSheet("Data")
                val header = sheet.createRow(0)
                header.createCell(0).setCellValue("ID")
                header.createCell(1).setCellValue("Name")
                header.createCell(2).setCellValue("Description")
                for (i in 1..50) {
                    val row = sheet.createRow(i)
                    row.createCell(0).setCellValue(i.toString())
                    row.createCell(1).setCellValue("Item$i")
                    row.createCell(2).setCellValue("Description for item number $i in the large fixture")
                }
                wb.write(baos)
            }
        }
    }
}
