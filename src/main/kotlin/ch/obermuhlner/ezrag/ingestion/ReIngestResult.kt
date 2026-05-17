package ch.obermuhlner.ezrag.ingestion

data class ReIngestResult(
    val staleFound: Int?,     // null when forceAll=true (not applicable)
    val filesReIngested: Int,
    val chunksCreated: Int,
    val filesSkipped: Int,    // source files not found on disk
)
