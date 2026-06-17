package ch.obermuhlner.ezrag.ingestion.sourcecode

import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TreeSitterJavascript
import org.treesitter.TreeSitterTypescript

/**
 * Parser for TypeScript (.ts, .tsx) and JavaScript (.js, .jsx) source files.
 *
 * grammarKey selects the grammar and output language:
 *   - "typescript" -> TypeScript grammar, language = "typescript"
 *   - "tsx"        -> TypeScript grammar, language = "typescript"
 *   - "javascript" -> JavaScript grammar, language = "javascript"
 *   - "jsx"        -> JavaScript grammar, language = "javascript"
 */
class TypeScriptSourceCodeParser(private val grammarKey: String) : SourceCodeParser {

    override val language: String = if (grammarKey == "javascript" || grammarKey == "jsx") "javascript" else "typescript"

    private val tsLanguage: TSLanguage = if (grammarKey == "javascript" || grammarKey == "jsx") {
        TreeSitterJavascript()
    } else {
        TreeSitterTypescript()
    }

    override fun parse(source: String): List<SourceDeclaration> {
        val parser = TSParser()
        parser.setLanguage(tsLanguage)
        val tree = parser.parseString(null, source)
        val root = tree.rootNode

        val imports = extractImports(root, source)

        val declarations = mutableListOf<SourceDeclaration>()
        collectDeclarations(root, source, imports, null, declarations)

        return declarations
    }

    private fun extractImports(root: TSNode, source: String): List<String> {
        val imports = mutableListOf<String>()
        for (i in 0 until root.childCount) {
            val child = root.getChild(i)
            if (child.type == "import_statement") {
                imports.add(nodeText(child, source).trim())
            }
        }
        return imports
    }

    /**
     * Recursively collect declarations from the given node.
     * enclosingClass is the qualified name of the containing class (null at top level).
     */
    private fun collectDeclarations(
        node: TSNode,
        source: String,
        imports: List<String>,
        enclosingClass: String?,
        result: MutableList<SourceDeclaration>,
    ) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            when (child.type) {
                "class_declaration", "abstract_class_declaration" -> {
                    val className = extractSimpleName(child, source) ?: continue
                    val qualifiedName = if (enclosingClass != null) "$enclosingClass.$className" else className
                    val classFullText = buildClassFullText(child, source)
                    val jsdoc = findPrecedingJsDoc(node, i, source)
                    val classText = if (jsdoc != null) "$jsdoc\n$classFullText" else classFullText

                    result.add(
                        SourceDeclaration(
                            declarationType = DeclarationType.CLASS,
                            declarationName = qualifiedName,
                            className = enclosingClass,
                            packageName = null,
                            imports = imports,
                            fullText = classText,
                        )
                    )

                    // Recurse into the class body for nested classes and methods
                    val body = findChildByType(child, "class_body")
                    if (body != null) {
                        collectDeclarations(body, source, imports, qualifiedName, result)
                    }
                }

                "interface_declaration" -> {
                    val interfaceName = extractSimpleName(child, source) ?: continue
                    val qualifiedName = if (enclosingClass != null) "$enclosingClass.$interfaceName" else interfaceName
                    val interfaceText = nodeText(child, source).trim()
                    val jsdoc = findPrecedingJsDoc(node, i, source)
                    val fullText = if (jsdoc != null) "$jsdoc\n$interfaceText" else interfaceText

                    result.add(
                        SourceDeclaration(
                            declarationType = DeclarationType.CLASS,
                            declarationName = qualifiedName,
                            className = enclosingClass,
                            packageName = null,
                            imports = imports,
                            fullText = fullText,
                        )
                    )

                    // Emit METHOD declarations for interface method signatures
                    val body = findChildByType(child, "interface_body")
                    if (body != null) {
                        collectInterfaceMethods(body, source, imports, qualifiedName, result)
                    }
                }

                "method_definition" -> {
                    // Methods inside a class body
                    val methodName = extractMethodName(child, source) ?: continue
                    val jsdoc = findPrecedingJsDoc(node, i, source)
                    val methodText = nodeText(child, source).trim()
                    val fullText = if (jsdoc != null) "$jsdoc\n$methodText" else methodText

                    result.add(
                        SourceDeclaration(
                            declarationType = DeclarationType.METHOD,
                            declarationName = methodName,
                            className = enclosingClass,
                            packageName = null,
                            imports = imports,
                            fullText = fullText,
                        )
                    )
                }

                "function_declaration" -> {
                    val funcName = extractSimpleName(child, source) ?: continue
                    val jsdoc = findPrecedingJsDoc(node, i, source)
                    val funcText = nodeText(child, source).trim()
                    val fullText = if (jsdoc != null) "$jsdoc\n$funcText" else funcText

                    if (enclosingClass != null) {
                        result.add(
                            SourceDeclaration(
                                declarationType = DeclarationType.METHOD,
                                declarationName = funcName,
                                className = enclosingClass,
                                packageName = null,
                                imports = imports,
                                fullText = fullText,
                            )
                        )
                    } else {
                        result.add(
                            SourceDeclaration(
                                declarationType = DeclarationType.FUNCTION,
                                declarationName = funcName,
                                className = null,
                                packageName = null,
                                imports = imports,
                                fullText = fullText,
                            )
                        )
                    }
                }

                "export_statement" -> {
                    // Export statements can wrap class/function declarations — recurse into them
                    collectDeclarations(child, source, imports, enclosingClass, result)
                }
            }
        }
    }

    /**
     * Collect method signatures from an interface body.
     */
    private fun collectInterfaceMethods(
        body: TSNode,
        source: String,
        imports: List<String>,
        interfaceName: String,
        result: MutableList<SourceDeclaration>,
    ) {
        for (i in 0 until body.childCount) {
            val child = body.getChild(i)
            if (child.type == "method_signature") {
                val methodName = extractMethodSignatureName(child, source) ?: continue
                val jsdoc = findPrecedingJsDoc(body, i, source)
                val methodText = nodeText(child, source).trim()
                val fullText = if (jsdoc != null) "$jsdoc\n$methodText" else methodText

                result.add(
                    SourceDeclaration(
                        declarationType = DeclarationType.METHOD,
                        declarationName = methodName,
                        className = interfaceName,
                        packageName = null,
                        imports = imports,
                        fullText = fullText,
                    )
                )
            }
        }
    }

    /**
     * Build the full text for a class declaration, replacing nested class bodies with their header only.
     */
    private fun buildClassFullText(classNode: TSNode, source: String): String {
        val sourceBytes = source.toByteArray(Charsets.UTF_8)
        val body = findChildByType(classNode, "class_body") ?: return nodeText(classNode, source).trim()

        val sb = StringBuilder()
        // Add everything up to and including the opening brace of the class body
        sb.append(String(sourceBytes, classNode.startByte, body.startByte - classNode.startByte, Charsets.UTF_8))
        sb.append("{")

        val bodyStart = body.startByte + 1  // skip the opening {
        var cursor = bodyStart

        for (i in 0 until body.childCount) {
            val child = body.getChild(i)
            if (child.type == "class_declaration" || child.type == "abstract_class_declaration") {
                // Append everything before this nested class
                if (child.startByte > cursor) {
                    sb.append(String(sourceBytes, cursor, child.startByte - cursor, Charsets.UTF_8))
                }
                // Append the nested class header only (up to its class_body)
                val nestedBody = findChildByType(child, "class_body")
                if (nestedBody != null) {
                    sb.append(
                        String(sourceBytes, child.startByte, nestedBody.startByte - child.startByte, Charsets.UTF_8)
                    )
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
     * Find the JSDoc block comment immediately preceding the child at childIndex in parent.
     */
    private fun findPrecedingJsDoc(parent: TSNode, childIndex: Int, source: String): String? {
        for (i in childIndex - 1 downTo 0) {
            val sibling = parent.getChild(i)
            when (sibling.type) {
                "comment" -> {
                    val text = nodeText(sibling, source).trim()
                    return if (text.startsWith("/**")) text else null
                }
                // Skip whitespace/unnamed nodes
                else -> if (!sibling.isNamed) continue else return null
            }
        }
        return null
    }

    /**
     * Extract the identifier name from a class declaration or function declaration.
     */
    private fun extractSimpleName(node: TSNode, source: String): String? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child.type == "identifier" || child.type == "type_identifier") {
                return nodeText(child, source).trim()
            }
        }
        return null
    }

    /**
     * Extract the method name from a method_definition node.
     */
    private fun extractMethodName(node: TSNode, source: String): String? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child.type == "property_identifier" || child.type == "identifier") {
                return nodeText(child, source).trim()
            }
        }
        return null
    }

    /**
     * Extract the method name from a method_signature node in an interface.
     */
    private fun extractMethodSignatureName(node: TSNode, source: String): String? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child.type == "property_identifier" || child.type == "identifier") {
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
