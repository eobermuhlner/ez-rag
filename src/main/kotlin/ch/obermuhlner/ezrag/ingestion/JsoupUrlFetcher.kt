package ch.obermuhlner.ezrag.ingestion

import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale

class JsoupUrlFetcher : UrlFetcher {
    override fun fetch(url: String): FetchResult {
        val response = Jsoup.connect(url)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .followRedirects(true)
            .timeout(30_000)
            .execute()

        val rawContentType = response.contentType() ?: "application/octet-stream"
        val contentType = rawContentType.substringBefore(";").trim()
        val lastModified = response.header("Last-Modified")?.let { parseHttpDate(it) } ?: 0L

        return FetchResult(
            bytes = response.bodyAsBytes(),
            contentType = contentType,
            lastModifiedEpochMs = lastModified,
            statusCode = response.statusCode(),
        )
    }

    private fun parseHttpDate(value: String): Long {
        return try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH).parse(value)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
