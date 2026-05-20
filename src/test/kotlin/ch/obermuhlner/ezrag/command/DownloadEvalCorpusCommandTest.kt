package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.beir.BeirDatasetRegistry
import ch.obermuhlner.ezrag.beir.BeirDownloader
import ch.obermuhlner.ezrag.beir.BeirDownloaderTest.Companion.createSampleBeierZip
import ch.obermuhlner.ezrag.eval.EvalCorpusLoader
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

class DownloadEvalCorpusCommandTest {

    private lateinit var server: HttpServer
    private var serverPort: Int = 0
    private val requestCount = AtomicInteger(0)
    private val zipBytes = createSampleBeierZip("nfcorpus")

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

    private fun createCommand(
        out: StringWriter = StringWriter(),
        err: StringWriter = StringWriter()
    ): Triple<DownloadEvalCorpusCommand, StringWriter, StringWriter> {
        val outputWriter = PrintWriter(out, true)
        val cmd = DownloadEvalCorpusCommand(
            registry = BeirDatasetRegistry(),
            outputWriter = outputWriter,
            errorWriter = PrintWriter(err, true),
            downloader = BeirDownloader(baseUrl = baseUrl(), outputWriter = outputWriter)
        )
        return Triple(cmd, out, err)
    }

    @Test
    fun `--list exits 0`() {
        val (cmd, _, _) = createCommand()
        cmd.list = true
        assertThat(cmd.call()).isEqualTo(0)
    }

    @Test
    fun `--list prints Dataset column header`() {
        val (cmd, out, _) = createCommand()
        cmd.list = true
        cmd.call()
        assertThat(out.toString()).containsIgnoringCase("Dataset")
    }

    @Test
    fun `--list output includes nfcorpus`() {
        val (cmd, out, _) = createCommand()
        cmd.list = true
        cmd.call()
        assertThat(out.toString()).contains("nfcorpus")
    }

    @Test
    fun `--list output includes scifact`() {
        val (cmd, out, _) = createCommand()
        cmd.list = true
        cmd.call()
        assertThat(out.toString()).contains("scifact")
    }

    @Test
    fun `--list output includes domain and count information`() {
        val (cmd, out, _) = createCommand()
        cmd.list = true
        cmd.call()
        // At minimum a numeric count should appear
        assertThat(out.toString()).containsPattern(Regex("\\d+").toPattern())
    }

    @Test
    fun `unknown dataset exits 1`() {
        val (cmd, _, _) = createCommand()
        cmd.dataset = "not-a-real-dataset"
        assertThat(cmd.call()).isEqualTo(1)
    }

    @Test
    fun `unknown dataset prints error to error output listing known names`() {
        val (cmd, _, err) = createCommand()
        cmd.dataset = "not-a-real-dataset"
        cmd.call()
        val errorOutput = err.toString()
        assertThat(errorOutput).containsAnyOf("nfcorpus", "scifact")
    }

    @Test
    fun `no args and no --list exits non-zero`() {
        val (cmd, _, _) = createCommand()
        assertThat(cmd.call()).isNotEqualTo(0)
    }

    @Test
    fun `no args and no --list prints usage or help text`() {
        val (cmd, _, err) = createCommand()
        cmd.call()
        assertThat(err.toString()).isNotEmpty()
    }

    @Test
    fun `when corpus jsonl exists in output dir command exits 0 and prints skip message`(@TempDir tempDir: Path) {
        val beiDir = writeSampleBeirFixture(tempDir)
        val (cmd, out, _) = createCommand()
        cmd.dataset = "nfcorpus"
        cmd.outputDir = beiDir
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).containsIgnoringCase("skip")
    }

    @Test
    fun `when corpus jsonl exists command writes questions yaml`(@TempDir tempDir: Path) {
        val beiDir = writeSampleBeirFixture(tempDir)
        val (cmd, _, _) = createCommand()
        cmd.dataset = "nfcorpus"
        cmd.outputDir = beiDir
        cmd.call()
        assertThat(beiDir.resolve("questions.yaml")).exists()
    }

    @Test
    fun `when corpus jsonl exists generated questions yaml is parseable by EvalCorpusLoader`(@TempDir tempDir: Path) {
        val beiDir = writeSampleBeirFixture(tempDir)
        val (cmd, _, _) = createCommand()
        cmd.dataset = "nfcorpus"
        cmd.outputDir = beiDir
        cmd.call()
        val scenario = EvalCorpusLoader().load(beiDir.toPath())
        assertThat(scenario.questions).isNotEmpty
    }

    @Test
    fun `when questions yaml exists and force is false command exits 0 and prints skip message`(@TempDir tempDir: Path) {
        val beiDir = writeSampleBeirFixture(tempDir)
        beiDir.resolve("questions.yaml").writeText("documents: []\nquestions: []\n")
        val (cmd, out, _) = createCommand()
        cmd.dataset = "nfcorpus"
        cmd.outputDir = beiDir
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(out.toString()).containsIgnoringCase("skip")
    }

    @Test
    fun `when questions yaml exists and force is false no network request is made`(@TempDir tempDir: Path) {
        val beiDir = writeSampleBeirFixture(tempDir)
        beiDir.resolve("questions.yaml").writeText("documents: []\nquestions: []\n")
        val (cmd, _, _) = createCommand()
        cmd.dataset = "nfcorpus"
        cmd.outputDir = beiDir
        cmd.call()
        assertThat(requestCount.get()).isEqualTo(0)
    }

    @Test
    fun `when questions yaml exists and force is true command re-downloads and regenerates`(@TempDir tempDir: Path) {
        val beiDir = writeSampleBeirFixture(tempDir)
        beiDir.resolve("questions.yaml").writeText("documents: []\nquestions: []\n")
        val (cmd, _, _) = createCommand()
        cmd.dataset = "nfcorpus"
        cmd.outputDir = beiDir
        cmd.force = true
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(requestCount.get()).isGreaterThan(0)
    }

    @Test
    fun `full pipeline downloads extracts and writes valid questions yaml`(@TempDir tempDir: Path) {
        val beiDir = tempDir.resolve("nfcorpus").toFile()
        val (cmd, _, _) = createCommand()
        cmd.dataset = "nfcorpus"
        cmd.outputDir = beiDir
        val exitCode = cmd.call()
        assertThat(exitCode).isEqualTo(0)
        assertThat(beiDir.resolve("questions.yaml")).exists()
        val scenario = EvalCorpusLoader().load(beiDir.toPath())
        assertThat(scenario.questions).isNotEmpty
    }

    @Test
    fun `full pipeline output contains Downloading Extracting Writing and questions yaml`(@TempDir tempDir: Path) {
        val beiDir = tempDir.resolve("nfcorpus").toFile()
        val (cmd, out, _) = createCommand()
        cmd.dataset = "nfcorpus"
        cmd.outputDir = beiDir
        cmd.call()
        val output = out.toString()
        assertThat(output).containsIgnoringCase("Downloading")
        assertThat(output).containsIgnoringCase("Extracting")
        assertThat(output).containsIgnoringCase("Writing")
        assertThat(output).containsIgnoringCase("questions.yaml")
    }

    @Test
    fun `unknown dataset exits 1 before any network request`(@TempDir tempDir: Path) {
        val (cmd, _, _) = createCommand()
        cmd.dataset = "not-a-real-dataset"
        cmd.outputDir = tempDir.toFile()
        cmd.call()
        assertThat(requestCount.get()).isEqualTo(0)
    }

    private fun writeSampleBeirFixture(tempDir: Path): File {
        val dir = tempDir.resolve("nfcorpus").toFile()
        dir.mkdirs()
        dir.resolve("corpus.jsonl").writeText(
            """{"_id": "d1", "title": "Title 1", "text": "Body 1", "metadata": {}}
{"_id": "d2", "title": "Title 2", "text": "Body 2", "metadata": {}}
"""
        )
        dir.resolve("queries.jsonl").writeText(
            """{"_id": "q1", "text": "Query 1", "metadata": {}}
"""
        )
        val qrelsDir = dir.resolve("qrels")
        qrelsDir.mkdirs()
        qrelsDir.resolve("test.tsv").writeText("query-id\tcorpus-id\tscore\nq1\td1\t1\n")
        return dir
    }
}
