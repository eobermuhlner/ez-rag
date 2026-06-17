package ch.obermuhlner.ezrag.ingestion.sourcecode

data class SourceDeclaration(
    val declarationType: DeclarationType,
    val declarationName: String,
    val className: String?,      // null for top-level functions; "Outer.Inner" for nested classes
    val packageName: String?,
    val imports: List<String>,
    val fullText: String,        // doc comment + signature + body (for CLASS: full body minus nested class bodies)
)
