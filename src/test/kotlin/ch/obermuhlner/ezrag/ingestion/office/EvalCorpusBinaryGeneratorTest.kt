package ch.obermuhlner.ezrag.ingestion.office

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Generates binary eval corpus fixtures (DOCX, PPTX) for the chunking eval scenarios.
 * Running this test writes the fixture files to src/test/resources/eval/.
 * The files are committed as static fixtures — this test is idempotent (uses writeIfChanged).
 */
class EvalCorpusBinaryGeneratorTest {

    @Test
    fun `generate eval corpus DOCX fixture`() {
        val file = File("src/test/resources/eval/chunking-docx/distributed_systems.docx")
        file.parentFile.mkdirs()
        EvalCorpusBinaryGenerator.createDocxFixture(file)
        assertThat(file).exists()
        assertThat(file.length()).isGreaterThan(0).isLessThan(50 * 1024)
    }

    @Test
    fun `generate eval corpus PPTX fixture`() {
        val file = File("src/test/resources/eval/chunking-pptx/distributed_systems.pptx")
        file.parentFile.mkdirs()
        EvalCorpusBinaryGenerator.createPptxFixture(file)
        assertThat(file).exists()
        assertThat(file.length()).isGreaterThan(0).isLessThan(50 * 1024)
    }

    @Test
    fun `generate eval corpus XLSX fixture`() {
        val file = File("src/test/resources/eval/chunking-xlsx/products.xlsx")
        file.parentFile.mkdirs()
        EvalCorpusBinaryGenerator.createXlsxFixture(file)
        assertThat(file).exists()
        assertThat(file.length()).isGreaterThan(0).isLessThan(50 * 1024)
    }
}
