package ch.obermuhlner.ezrag.ingestion.sourcecode

interface SourceCodeParser {
    val language: String  // e.g. "kotlin", "java", "typescript", "javascript"
    fun parse(source: String): List<SourceDeclaration>
}
