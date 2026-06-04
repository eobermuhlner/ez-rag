package ch.obermuhlner.ezrag.ingestion

interface UrlFetcher {
    fun fetch(url: String): FetchResult
}

data class FetchResult(
    val bytes: ByteArray,
    val contentType: String,
    val lastModifiedEpochMs: Long,
    val statusCode: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FetchResult) return false
        return bytes.contentEquals(other.bytes) &&
            contentType == other.contentType &&
            lastModifiedEpochMs == other.lastModifiedEpochMs &&
            statusCode == other.statusCode
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + lastModifiedEpochMs.hashCode()
        result = 31 * result + statusCode
        return result
    }
}
