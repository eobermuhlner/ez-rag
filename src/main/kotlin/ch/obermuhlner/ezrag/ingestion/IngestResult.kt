package ch.obermuhlner.ezrag.ingestion

data class IngestResult(
    val filesIngested: Int,
    val chunksCreated: Int,
    val skipped: Int,
)
