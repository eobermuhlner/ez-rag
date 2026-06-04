package ch.obermuhlner.ezrag.ingestion

import java.io.File

sealed class IngestSource
data class FileSource(val file: File) : IngestSource()
data class UrlSource(val url: String) : IngestSource()
