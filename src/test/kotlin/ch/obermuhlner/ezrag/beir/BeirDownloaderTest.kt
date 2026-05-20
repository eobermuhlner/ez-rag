package ch.obermuhlner.ezrag.beir

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BeirDownloaderTest {

    private lateinit var server: HttpServer
    private var serverPort: Int = 0
    private val requestCount = AtomicInteger(0)
    private val datasetName = "testdataset"
    private var zipBytes: ByteArray = createSampleBeierZip(datasetName)

    @BeforeEach
    fun startServer() {
        requestCount.set(0)
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            requestCount.incrementAndGet()
            exchange.sendResponseHeaders(200, zipBytes.size.toLong())
            exchange.responseBody.use { out -> out.write(zipBytes) }
        }
        server.start()
        serverPort = server.address.port
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    private fun baseUrl() = "http://localhost:$serverPort"

    @Test
    fun `download extracts corpus jsonl to target directory`(@TempDir tempDir: Path) {
        val targetDir = tempDir.resolve(datasetName).toFile()
        val downloader = BeirDownloader(baseUrl = baseUrl(), outputWriter = PrintWriter(StringWriter()))
        downloader.download(datasetName, targetDir, force = false)
        assertThat(targetDir.resolve("corpus.jsonl")).exists()
    }

    @Test
    fun `download extracts queries jsonl to target directory`(@TempDir tempDir: Path) {
        val targetDir = tempDir.resolve(datasetName).toFile()
        val downloader = BeirDownloader(baseUrl = baseUrl(), outputWriter = PrintWriter(StringWriter()))
        downloader.download(datasetName, targetDir, force = false)
        assertThat(targetDir.resolve("queries.jsonl")).exists()
    }

    @Test
    fun `download extracts qrels tsv to target directory`(@TempDir tempDir: Path) {
        val targetDir = tempDir.resolve(datasetName).toFile()
        val downloader = BeirDownloader(baseUrl = baseUrl(), outputWriter = PrintWriter(StringWriter()))
        downloader.download(datasetName, targetDir, force = false)
        assertThat(targetDir.resolve("qrels/test.tsv")).exists()
    }

    @Test
    fun `download prints Downloading progress message`(@TempDir tempDir: Path) {
        val out = StringWriter()
        val targetDir = tempDir.resolve(datasetName).toFile()
        val downloader = BeirDownloader(baseUrl = baseUrl(), outputWriter = PrintWriter(out, true))
        downloader.download(datasetName, targetDir, force = false)
        assertThat(out.toString()).containsIgnoringCase("Downloading")
    }

    @Test
    fun `download prints Extracting progress message`(@TempDir tempDir: Path) {
        val out = StringWriter()
        val targetDir = tempDir.resolve(datasetName).toFile()
        val downloader = BeirDownloader(baseUrl = baseUrl(), outputWriter = PrintWriter(out, true))
        downloader.download(datasetName, targetDir, force = false)
        assertThat(out.toString()).containsIgnoringCase("Extracting")
    }

    @Test
    fun `when corpus jsonl already exists and force is false no HTTP request is made`(@TempDir tempDir: Path) {
        val targetDir = tempDir.resolve(datasetName).toFile()
        targetDir.mkdirs()
        targetDir.resolve("corpus.jsonl").writeText("existing")
        val downloader = BeirDownloader(baseUrl = baseUrl(), outputWriter = PrintWriter(StringWriter()))
        downloader.download(datasetName, targetDir, force = false)
        assertThat(requestCount.get()).isEqualTo(0)
    }

    @Test
    fun `when corpus jsonl already exists and force is false prints skip message`(@TempDir tempDir: Path) {
        val out = StringWriter()
        val targetDir = tempDir.resolve(datasetName).toFile()
        targetDir.mkdirs()
        targetDir.resolve("corpus.jsonl").writeText("existing")
        val downloader = BeirDownloader(baseUrl = baseUrl(), outputWriter = PrintWriter(out, true))
        downloader.download(datasetName, targetDir, force = false)
        assertThat(out.toString()).containsIgnoringCase("skip")
    }

    @Test
    fun `when force is true re-downloads even if corpus jsonl exists`(@TempDir tempDir: Path) {
        val targetDir = tempDir.resolve(datasetName).toFile()
        targetDir.mkdirs()
        targetDir.resolve("corpus.jsonl").writeText("old")
        val downloader = BeirDownloader(baseUrl = baseUrl(), outputWriter = PrintWriter(StringWriter()))
        downloader.download(datasetName, targetDir, force = true)
        assertThat(requestCount.get()).isGreaterThan(0)
    }

    @Test
    fun `when force is true overwrites existing corpus jsonl`(@TempDir tempDir: Path) {
        val targetDir = tempDir.resolve(datasetName).toFile()
        targetDir.mkdirs()
        targetDir.resolve("corpus.jsonl").writeText("old-content")
        val downloader = BeirDownloader(baseUrl = baseUrl(), outputWriter = PrintWriter(StringWriter()))
        downloader.download(datasetName, targetDir, force = true)
        val content = targetDir.resolve("corpus.jsonl").readText()
        assertThat(content).doesNotContain("old-content")
    }

    @Test
    fun `when force is true on fresh directory proceeds normally without errors`(@TempDir tempDir: Path) {
        val targetDir = tempDir.resolve(datasetName).toFile()
        val downloader = BeirDownloader(baseUrl = baseUrl(), outputWriter = PrintWriter(StringWriter()))
        downloader.download(datasetName, targetDir, force = true)
        assertThat(targetDir.resolve("corpus.jsonl")).exists()
    }

    companion object {
        fun createSampleBeierZip(datasetName: String): ByteArray {
            val bos = ByteArrayOutputStream()
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("$datasetName/corpus.jsonl"))
                zos.write("""{"_id": "d1", "title": "Title 1", "text": "Body 1"}""".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("$datasetName/queries.jsonl"))
                zos.write("""{"_id": "q1", "text": "Query 1"}""".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("$datasetName/qrels/"))
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("$datasetName/qrels/test.tsv"))
                zos.write("query-id\tcorpus-id\tscore\nq1\td1\t1\n".toByteArray())
                zos.closeEntry()
            }
            return bos.toByteArray()
        }
    }
}
