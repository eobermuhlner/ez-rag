package ch.obermuhlner.ezrag.beir

import java.io.File
import java.io.PrintWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.ZipInputStream

class BeirDownloader(
    private val baseUrl: String = "https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets",
    private val outputWriter: PrintWriter
) {

    fun download(datasetName: String, targetDir: File, force: Boolean) {
        val corpusFile = targetDir.resolve("corpus.jsonl")
        if (corpusFile.exists() && !force) {
            outputWriter.println("Skipping download: corpus.jsonl already exists in ${targetDir.absolutePath}")
            return
        }

        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        outputWriter.println("Downloading $datasetName")
        val url = "$baseUrl/$datasetName.zip"
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI.create(url)).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() != 200) {
            throw RuntimeException("Download failed with HTTP ${response.statusCode()} for $url")
        }

        outputWriter.println("Extracting")
        ZipInputStream(response.body()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val relativePath = entry.name.removePrefix("$datasetName/")
                if (relativePath.isNotEmpty() && !entry.isDirectory) {
                    val file = targetDir.resolve(relativePath)
                    file.parentFile?.mkdirs()
                    file.outputStream().use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
