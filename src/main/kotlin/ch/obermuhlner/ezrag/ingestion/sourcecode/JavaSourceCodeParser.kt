package ch.obermuhlner.ezrag.ingestion.sourcecode

import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TreeSitterJava

class JavaSourceCodeParser : SourceCodeParser {

    override val language: String = "java"

    private val tsLanguage: TSLanguage = TreeSitterJava()

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
            if (child.type == "package_declaration") {
                // package_declaration: "package" + identifier/scoped_identifier + ";"
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
            if (child.type == "import_declaration") {
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
                "class_declaration", "interface_declaration", "enum_declaration",
                "annotation_type_declaration", "record_declaration" -> {
                    val className = extractSimpleNameForClass(child, source) ?: continue
                    val qualifiedName = if (enclosingClass != null) "$enclosingClass.$className" else className
                    val classFullText = buildClassFullText(child, source)
                    val javadoc = findPrecedingJavadoc(node, i, source)
                    val classText = if (javadoc != null) "$javadoc\n$classFullText" else classFullText

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
                        ?: findChildByType(child, "interface_body")
                        ?: findChildByType(child, "enum_body")
                    if (body != null) {
                        collectDeclarations(body, source, packageName, imports, qualifiedName, result)
                    }
                }

                "method_declaration", "interface_method_declaration" -> {
                    val methodName = extractSimpleNameForMethod(child, source) ?: continue
                    val javadoc = findPrecedingJavadoc(node, i, source)
                    val methodText = nodeText(child, source).trim()
                    val fullText = if (javadoc != null) "$javadoc\n$methodText" else methodText

                    result.add(
                        SourceDeclaration(
                            declarationType = DeclarationType.METHOD,
                            declarationName = methodName,
                            className = enclosingClass,
                            packageName = packageName,
                            imports = imports,
                            fullText = fullText,
                        )
                    )
                }

                "constructor_declaration" -> {
                    val ctorName = extractSimpleNameForConstructor(child, source) ?: (enclosingClass ?: "constructor")
                    val javadoc = findPrecedingJavadoc(node, i, source)
                    val ctorText = nodeText(child, source).trim()
                    val fullText = if (javadoc != null) "$javadoc\n$ctorText" else ctorText

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
     */
    private fun buildClassFullText(classNode: TSNode, source: String): String {
        val sourceBytes = source.toByteArray(Charsets.UTF_8)
        val body = findChildByType(classNode, "class_body")
            ?: findChildByType(classNode, "interface_body")
            ?: findChildByType(classNode, "enum_body")
            ?: return nodeText(classNode, source).trim()

        val sb = StringBuilder()
        // Add everything up to and including the opening brace of the class body
        sb.append(String(sourceBytes, classNode.startByte, body.startByte - classNode.startByte, Charsets.UTF_8))
        sb.append("{")

        val bodyStart = body.startByte + 1  // skip the opening {
        var cursor = bodyStart

        for (i in 0 until body.childCount) {
            val child = body.getChild(i)
            if (child.type == "class_declaration" || child.type == "interface_declaration" ||
                child.type == "enum_declaration" || child.type == "record_declaration" ||
                child.type == "annotation_type_declaration") {
                // Append everything before this nested class
                if (child.startByte > cursor) {
                    sb.append(String(sourceBytes, cursor, child.startByte - cursor, Charsets.UTF_8))
                }
                // Append the nested class header only (up to its class_body)
                val nestedBody = findChildByType(child, "class_body")
                    ?: findChildByType(child, "interface_body")
                    ?: findChildByType(child, "enum_body")
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
     * Find the Javadoc (block comment starting with slash-star-star) immediately preceding the child at [childIndex] in [parent].
     */
    private fun findPrecedingJavadoc(parent: TSNode, childIndex: Int, source: String): String? {
        for (i in childIndex - 1 downTo 0) {
            val sibling = parent.getChild(i)
            when (sibling.type) {
                "block_comment" -> {
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
     * Extract the simple identifier name from a class/interface/enum declaration node.
     */
    private fun extractSimpleNameForClass(node: TSNode, source: String): String? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child.type == "identifier") {
                return nodeText(child, source).trim()
            }
        }
        return null
    }

    /**
     * Extract the method name from a method_declaration or interface_method_declaration node.
     */
    private fun extractSimpleNameForMethod(node: TSNode, source: String): String? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child.type == "identifier") {
                return nodeText(child, source).trim()
            }
        }
        return null
    }

    /**
     * Extract the constructor name from a constructor_declaration node.
     */
    private fun extractSimpleNameForConstructor(node: TSNode, source: String): String? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child.type == "identifier") {
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
