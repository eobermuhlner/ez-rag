package ch.obermuhlner.ezrag.ingestion.sourcecode

import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSQuery
import org.treesitter.TSQueryCapture
import org.treesitter.TSQueryCursor
import org.treesitter.TSQueryMatch
import org.treesitter.TreeSitterKotlin

class KotlinSourceCodeParser : SourceCodeParser {

    override val language: String = "kotlin"

    private val tsLanguage: TSLanguage = TreeSitterKotlin()

    override fun parse(source: String): List<SourceDeclaration> {
        val parser = TSParser()
        parser.setLanguage(tsLanguage)
        val tree = parser.parseString(null, source)
        val root = tree.rootNode

        val packageName = extractPackageName(root, source)
        val imports = extractImports(root, source)

        val declarations = mutableListOf<SourceDeclaration>()
        collectDeclarations(root, source, packageName, imports, null, declarations)

        return declarations
    }

    private fun extractPackageName(root: TSNode, source: String): String? {
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child.type == "package_header") {
                // package_header contains: "package" keyword + identifier
                val text = nodeText(child, source).trim()
                return text.removePrefix("package").trim().trimEnd(';').trim()
            }
        }
        return null
    }

    private fun extractImports(root: TSNode, source: String): List<String> {
        val imports = mutableListOf<String>()
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child.type == "import_list") {
                for (j in 0 until child.childCount) {
                    val importChild = child.getChild(j)
                    if (importChild.type == "import_header") {
                        imports.add(nodeText(importChild, source).trim())
                    }
                }
            } else if (child.type == "import_header") {
                imports.add(nodeText(child, source).trim())
            }
        }
        return imports
    }

    /**
     * Recursively collect declarations from the given node.
     * [enclosingClass] is the qualified name of the containing class (null at top level).
     */
    private fun collectDeclarations(
        node: TSNode,
        source: String,
        packageName: String?,
        imports: List<String>,
        enclosingClass: String?,
        result: MutableList<SourceDeclaration>,
    ) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            when (child.type) {
                "class_declaration", "object_declaration" -> {
                    val className = extractSimpleName(child, source) ?: continue
                    val qualifiedName = if (enclosingClass != null) "$enclosingClass.$className" else className
                    val classFullText = buildClassFullText(child, source)
                    val kdoc = findPrecedingKdoc(node, i, source)
                    val classText = if (kdoc != null) "$kdoc\n$classFullText" else classFullText

                    result.add(
                        SourceDeclaration(
                            declarationType = DeclarationType.CLASS,
                            declarationName = qualifiedName,
                            className = enclosingClass,
                            packageName = packageName,
                            imports = imports,
                            fullText = classText,
                        )
                    )

                    // Recurse into the class body for nested classes, methods, constructors
                    val body = findChildByType(child, "class_body")
                    if (body != null) {
                        collectDeclarations(body, source, packageName, imports, qualifiedName, result)
                    }
                }

                "function_declaration" -> {
                    val funcName = extractSimpleName(child, source) ?: continue
                    val kdoc = findPrecedingKdoc(node, i, source)
                    val funcText = nodeText(child, source).trim()
                    val fullText = if (kdoc != null) "$kdoc\n$funcText" else funcText

                    val (declType, className) = if (enclosingClass != null) {
                        DeclarationType.METHOD to enclosingClass
                    } else {
                        DeclarationType.FUNCTION to null
                    }

                    result.add(
                        SourceDeclaration(
                            declarationType = declType,
                            declarationName = funcName,
                            className = className,
                            packageName = packageName,
                            imports = imports,
                            fullText = fullText,
                        )
                    )
                }

                "secondary_constructor" -> {
                    val kdoc = findPrecedingKdoc(node, i, source)
                    val ctorText = nodeText(child, source).trim()
                    val fullText = if (kdoc != null) "$kdoc\n$ctorText" else ctorText
                    val ctorName = enclosingClass ?: "constructor"

                    result.add(
                        SourceDeclaration(
                            declarationType = DeclarationType.CONSTRUCTOR,
                            declarationName = ctorName,
                            className = enclosingClass,
                            packageName = packageName,
                            imports = imports,
                            fullText = fullText,
                        )
                    )
                }
            }
        }
    }

    /**
     * Build the full text for a class declaration, replacing nested class bodies with their header only.
     * This preserves the class body structure but omits the bodies of nested classes.
     */
    private fun buildClassFullText(classNode: TSNode, source: String): String {
        val sourceBytes = source.toByteArray(Charsets.UTF_8)
        val body = findChildByType(classNode, "class_body") ?: return nodeText(classNode, source).trim()

        val sb = StringBuilder()
        // Add everything up to and including the opening brace of the class body
        sb.append(String(sourceBytes, classNode.startByte, body.startByte - classNode.startByte, Charsets.UTF_8))
        sb.append("{")

        // Walk through the class body, replacing nested class bodies with placeholders
        val bodyStart = body.startByte + 1  // skip the opening {
        var cursor = bodyStart

        for (i in 0 until body.childCount) {
            val child = body.getChild(i)
            if (child.type == "class_declaration" || child.type == "object_declaration") {
                // Append everything before this nested class
                if (child.startByte > cursor) {
                    sb.append(String(sourceBytes, cursor, child.startByte - cursor, Charsets.UTF_8))
                }
                // Append the nested class header only (up to its class_body)
                val nestedBody = findChildByType(child, "class_body")
                if (nestedBody != null) {
                    sb.append(String(sourceBytes, child.startByte, nestedBody.startByte - child.startByte, Charsets.UTF_8))
                    sb.append("{ /* ... */ }")
                    cursor = child.endByte
                } else {
                    sb.append(String(sourceBytes, child.startByte, child.endByte - child.startByte, Charsets.UTF_8))
                    cursor = child.endByte
                }
            }
        }
        // Append remaining body content up to (but not including) the closing }
        val bodyEnd = body.endByte - 1  // skip the closing }
        if (bodyEnd > cursor) {
            sb.append(String(sourceBytes, cursor, bodyEnd - cursor, Charsets.UTF_8))
        }
        sb.append("}")
        return sb.toString().trim()
    }

    /**
     * Find the KDoc comment immediately preceding the child at [childIndex] in [parent].
     */
    private fun findPrecedingKdoc(parent: TSNode, childIndex: Int, source: String): String? {
        // Scan backwards for multiline_comment or line_comment that immediately precedes this declaration
        for (i in childIndex - 1 downTo 0) {
            val sibling = parent.getChild(i)
            return when (sibling.type) {
                "multiline_comment" -> nodeText(sibling, source).trim()
                // Skip whitespace nodes (unnamed)
                else -> if (!sibling.isNamed) continue else null
            }
        }
        return null
    }

    /**
     * Extract the simple identifier name from a class or function declaration.
     */
    private fun extractSimpleName(node: TSNode, source: String): String? {
        // For class_declaration: look for type_identifier
        // For function_declaration: look for simple_identifier
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child.type == "type_identifier" || child.type == "simple_identifier") {
                return nodeText(child, source).trim()
            }
        }
        return null
    }

    private fun findChildByType(node: TSNode, type: String): TSNode? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child.type == type) return child
        }
        return null
    }

    private fun nodeText(node: TSNode, source: String): String {
        val bytes = source.toByteArray(Charsets.UTF_8)
        return String(bytes, node.startByte, node.endByte - node.startByte, Charsets.UTF_8)
    }
}
