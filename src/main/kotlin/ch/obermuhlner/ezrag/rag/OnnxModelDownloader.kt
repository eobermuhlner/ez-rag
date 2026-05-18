package ch.obermuhlner.ezrag.rag

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files

class OnnxModelDownloader(
    private val modelName: String,
    private val cacheRoot: File,
    private val baseUrl: String = "https://huggingface.co",
    private val token: String? = System.getenv("HF_TOKEN") ?: System.getenv("HUGGINGFACE_TOKEN")
) {

    private val modelDir: File get() = cacheRoot.resolve(modelName)

    fun ensureFile(remotePath: String, localFileName: String): File {
        val localFile = modelDir.resolve(localFileName)
        if (localFile.exists()) return localFile

        modelDir.mkdirs()
        val url = "$baseUrl/$modelName/resolve/main/$remotePath"
        downloadFile(url, localFile)
        return localFile
    }

    fun ensureFileIfExists(remotePath: String, localFileName: String): File? {
        val localFile = modelDir.resolve(localFileName)
        if (localFile.exists()) return localFile
        return try {
            localFile.parentFile?.mkdirs()
            val url = "$baseUrl/$modelName/resolve/main/$remotePath"
            downloadFile(url, localFile)
            localFile
        } catch (e: RuntimeException) {
            if (e.message?.contains("HTTP 404") == true) null else throw e
        }
    }

    fun ensureOnnxDataFile(onnxFile: File): File? {
        val dataFileName = findExternalDataFilename(onnxFile) ?: return null
        val onnxRelPath = onnxFile.toRelativeString(modelDir).replace(File.separator, "/")
        val dataDir = onnxRelPath.substringBeforeLast("/", "")
        val dataRemotePath = if (dataDir.isEmpty()) dataFileName else "$dataDir/$dataFileName"
        return ensureFileIfExists(dataRemotePath, dataRemotePath)
    }

    private fun findExternalDataFilename(onnxFile: File): String? {
        val content = onnxFile.readBytes().toString(Charsets.ISO_8859_1)
        return Regex("[a-zA-Z0-9_.-]+\\.onnx_data").find(content)?.value
    }

    fun ensureCachedOnnxModel(primaryPath: String, fallbackPath: String): File {
        val primaryLocalPath = primaryPath.replace("/", File.separator)
        val primaryFile = modelDir.resolve(primaryLocalPath)
        if (primaryFile.exists()) return primaryFile

        // Try to download the primary path first
        try {
            primaryFile.parentFile?.mkdirs()
            val url = "$baseUrl/$modelName/resolve/main/$primaryPath"
            downloadFile(url, primaryFile)
            return primaryFile
        } catch (e: Exception) {
            // Fall back to the fallback path
        }

        val fallbackLocalPath = fallbackPath.replace("/", File.separator)
        val fallbackFile = modelDir.resolve(fallbackLocalPath)
        if (fallbackFile.exists()) return fallbackFile

        fallbackFile.parentFile?.mkdirs()
        val url = "$baseUrl/$modelName/resolve/main/$fallbackPath"
        downloadFile(url, fallbackFile)
        return fallbackFile
    }

    private fun downloadFile(url: String, destination: File) {
        val tempFile = Files.createTempFile(destination.parentFile.toPath(), "download-", ".tmp").toFile()
        try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            connection.connect()
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                throw RuntimeException(
                    "HTTP 401 when downloading $url — the model may be gated. " +
                    "Set the HF_TOKEN environment variable to your HuggingFace access token."
                )
            }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw RuntimeException("HTTP $responseCode when downloading $url")
            }
            connection.inputStream.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile.renameTo(destination)
        } catch (e: Exception) {
            tempFile.delete()
            if (e is RuntimeException) throw e
            throw RuntimeException("Failed to download $url to $destination: ${e.message}", e)
        }
    }
}
