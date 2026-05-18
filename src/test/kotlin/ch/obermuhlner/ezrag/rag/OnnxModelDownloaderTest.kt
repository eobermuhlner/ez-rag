package ch.obermuhlner.ezrag.rag

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OnnxModelDownloaderTest {

    private lateinit var server: HttpServer
    private var serverPort: Int = 0
    private val requestCount = AtomicInteger(0)
    private val lastAuthorizationHeader = AtomicReference<String?>(null)
    private var fileContent = "model-file-content"
    private var returnHttpError = false
    private var returnHttpStatus = 200

    @BeforeEach
    fun startServer() {
        requestCount.set(0)
        lastAuthorizationHeader.set(null)
        returnHttpError = false
        returnHttpStatus = 200
        server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            requestCount.incrementAndGet()
            lastAuthorizationHeader.set(exchange.requestHeaders.getFirst("Authorization"))
            val status = if (returnHttpError) 500 else returnHttpStatus
            if (status != 200) {
                val body = "Error".toByteArray()
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { out -> out.write(body) }
            } else {
                val content = fileContent.toByteArray()
                exchange.sendResponseHeaders(200, content.size.toLong())
                exchange.responseBody.use { out -> out.write(content) }
            }
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
    fun `first call to ensureFile writes file to expected cache path and returns that path`(@TempDir cacheRoot: File) {
        val downloader = OnnxModelDownloader(
            modelName = "test-org/test-model",
            cacheRoot = cacheRoot,
            baseUrl = baseUrl()
        )

        val result = downloader.ensureFile("tokenizer.json", "tokenizer.json")

        val expectedPath = cacheRoot.resolve("test-org/test-model/tokenizer.json")
        assertThat(result).isEqualTo(expectedPath)
        assertThat(result).exists()
        assertThat(result.readText()).isEqualTo(fileContent)
    }

    @Test
    fun `second call to ensureFile skips HTTP request and returns cached file`(@TempDir cacheRoot: File) {
        val downloader = OnnxModelDownloader(
            modelName = "test-org/test-model",
            cacheRoot = cacheRoot,
            baseUrl = baseUrl()
        )

        downloader.ensureFile("tokenizer.json", "tokenizer.json")
        val countAfterFirst = requestCount.get()

        downloader.ensureFile("tokenizer.json", "tokenizer.json")

        assertThat(requestCount.get()).isEqualTo(countAfterFirst)
    }

    @Test
    fun `when download fails no corrupt file remains at destination`(@TempDir cacheRoot: File) {
        returnHttpError = true

        val downloader = OnnxModelDownloader(
            modelName = "test-org/test-model",
            cacheRoot = cacheRoot,
            baseUrl = baseUrl()
        )

        assertThatThrownBy {
            downloader.ensureFile("tokenizer.json", "tokenizer.json")
        }.isInstanceOf(RuntimeException::class.java)

        val destinationFile = cacheRoot.resolve("test-org/test-model/tokenizer.json")
        assertThat(destinationFile).doesNotExist()
    }

    @Test
    fun `when token is provided it is sent as Authorization Bearer header`(@TempDir cacheRoot: File) {
        val downloader = OnnxModelDownloader(
            modelName = "test-org/test-model",
            cacheRoot = cacheRoot,
            baseUrl = baseUrl(),
            token = "my-hf-token"
        )

        downloader.ensureFile("tokenizer.json", "tokenizer.json")

        assertThat(lastAuthorizationHeader.get()).isEqualTo("Bearer my-hf-token")
    }

    @Test
    fun `ensureFileIfExists returns null when server returns 404`(@TempDir cacheRoot: File) {
        returnHttpStatus = 404

        val downloader = OnnxModelDownloader(
            modelName = "test-org/test-model",
            cacheRoot = cacheRoot,
            baseUrl = baseUrl()
        )

        val result = downloader.ensureFileIfExists("model.onnx_data", "model.onnx_data")

        assertThat(result).isNull()
    }

    @Test
    fun `ensureFileIfExists returns cached file when server returns 200`(@TempDir cacheRoot: File) {
        val downloader = OnnxModelDownloader(
            modelName = "test-org/test-model",
            cacheRoot = cacheRoot,
            baseUrl = baseUrl()
        )

        val result = downloader.ensureFileIfExists("model.onnx_data", "model.onnx_data")

        assertThat(result).isNotNull()
        assertThat(result!!).exists()
    }

    @Test
    fun `ensureOnnxDataFile reads external data filename from onnx binary content`(@TempDir cacheRoot: File) {
        val downloader = OnnxModelDownloader(
            modelName = "test-org/test-model",
            cacheRoot = cacheRoot,
            baseUrl = baseUrl()
        )
        // Simulate an onnx file whose binary content references a data file by name
        val onnxFile = cacheRoot.resolve("test-org/test-model/onnx/decoder_model_merged.onnx")
        onnxFile.parentFile.mkdirs()
        onnxFile.writeText("...protobuf...locationmodel.onnx_dataj...")

        val dataFile = downloader.ensureOnnxDataFile(onnxFile)

        assertThat(dataFile).isNotNull()
        assertThat(dataFile!!).exists()
        assertThat(dataFile.name).isEqualTo("model.onnx_data")
    }

    @Test
    fun `ensureOnnxDataFile returns null when onnx file has no external data reference`(@TempDir cacheRoot: File) {
        val downloader = OnnxModelDownloader(
            modelName = "test-org/test-model",
            cacheRoot = cacheRoot,
            baseUrl = baseUrl()
        )
        val onnxFile = cacheRoot.resolve("test-org/test-model/onnx/model.onnx")
        onnxFile.parentFile.mkdirs()
        onnxFile.writeText("no-external-data")

        val result = downloader.ensureOnnxDataFile(onnxFile)

        assertThat(result).isNull()
    }

    @Test
    fun `ensureOnnxDataFile returns null when referenced data file does not exist on server`(@TempDir cacheRoot: File) {
        returnHttpStatus = 404

        val downloader = OnnxModelDownloader(
            modelName = "test-org/test-model",
            cacheRoot = cacheRoot,
            baseUrl = baseUrl()
        )
        val onnxFile = cacheRoot.resolve("test-org/test-model/onnx/decoder_model_merged.onnx")
        onnxFile.parentFile.mkdirs()
        onnxFile.writeText("...locationmodel.onnx_dataj...")

        val result = downloader.ensureOnnxDataFile(onnxFile)

        assertThat(result).isNull()
    }

    @Test
    fun `when server returns 401 error message mentions HF_TOKEN`(@TempDir cacheRoot: File) {
        returnHttpStatus = 401

        val downloader = OnnxModelDownloader(
            modelName = "test-org/test-model",
            cacheRoot = cacheRoot,
            baseUrl = baseUrl()
        )

        assertThatThrownBy {
            downloader.ensureFile("tokenizer.json", "tokenizer.json")
        }.isInstanceOf(RuntimeException::class.java)
            .hasMessageContaining("HF_TOKEN")
    }
}
