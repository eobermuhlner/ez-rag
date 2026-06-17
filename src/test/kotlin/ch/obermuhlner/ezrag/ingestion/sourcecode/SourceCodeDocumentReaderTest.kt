package ch.obermuhlner.ezrag.ingestion.sourcecode

import ch.obermuhlner.ezrag.ingestion.TokenCounter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SourceCodeDocumentReaderTest {

    @Test
    fun `class declaration produces document with correct metadata`() {
        val source = """
            package com.example

            class MyClass {
                fun foo() {}
            }
        """.trimIndent()

        val reader = SourceCodeDocumentReader(source, KotlinSourceCodeParser(), chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        val classDoc = docs.find { it.metadata["declaration_type"] == "class" }
        assertThat(classDoc).isNotNull
        assertThat(classDoc!!.metadata["declaration_name"]).isEqualTo("MyClass")
        assertThat(classDoc.metadata["language"]).isEqualTo("kotlin")
        assertThat(classDoc.metadata["heading_title"]).isEqualTo("MyClass")
        @Suppress("UNCHECKED_CAST")
        assertThat(classDoc.metadata["heading_path"] as List<String>).containsExactly("MyClass")
    }

    @Test
    fun `method declaration produces document with correct metadata`() {
        val source = """
            class MyClass {
                fun foo() {}
            }
        """.trimIndent()

        val reader = SourceCodeDocumentReader(source, KotlinSourceCodeParser(), chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        val methodDoc = docs.find { it.metadata["declaration_type"] == "method" }
        assertThat(methodDoc).isNotNull
        assertThat(methodDoc!!.metadata["declaration_name"]).isEqualTo("foo")
        assertThat(methodDoc.metadata["class_name"]).isEqualTo("MyClass")
        assertThat(methodDoc.metadata["language"]).isEqualTo("kotlin")
        assertThat(methodDoc.metadata["declaration_type"]).isEqualTo("method")
        @Suppress("UNCHECKED_CAST")
        assertThat(methodDoc.metadata["heading_path"] as List<String>).containsExactly("MyClass", "foo")
    }

    @Test
    fun `top-level function produces document without class_name metadata`() {
        val source = """
            fun myFun() {
                println("hello")
            }
        """.trimIndent()

        val reader = SourceCodeDocumentReader(source, KotlinSourceCodeParser(), chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        val funcDoc = docs.find { it.metadata["declaration_type"] == "function" }
        assertThat(funcDoc).isNotNull
        assertThat(funcDoc!!.metadata["declaration_name"]).isEqualTo("myFun")
        assertThat(funcDoc.metadata).doesNotContainKey("class_name")
        @Suppress("UNCHECKED_CAST")
        assertThat(funcDoc.metadata["heading_path"] as List<String>).containsExactly("myFun")
    }

    @Test
    fun `constructor produces document with declaration_type constructor`() {
        val source = """
            class MyClass(val name: String) {
                constructor(name: String, value: Int) : this(name) {}
            }
        """.trimIndent()

        val reader = SourceCodeDocumentReader(source, KotlinSourceCodeParser(), chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        val ctorDoc = docs.find { it.metadata["declaration_type"] == "constructor" }
        assertThat(ctorDoc).isNotNull
        assertThat(ctorDoc!!.metadata["class_name"]).isEqualTo("MyClass")
        assertThat(ctorDoc.metadata["language"]).isEqualTo("kotlin")
    }

    @Test
    fun `each document text starts with heading prefix followed by fenced kotlin code block`() {
        val source = """
            package com.example

            class MyClass {
                fun foo() {}
            }
        """.trimIndent()

        val reader = SourceCodeDocumentReader(source, KotlinSourceCodeParser(), chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).isNotEmpty
        docs.forEach { doc ->
            assertThat(doc.text).contains("```kotlin")
        }
    }

    @Test
    fun `class document heading prefix is double-hash with class name`() {
        val source = """
            class MyClass {
                fun foo() {}
            }
        """.trimIndent()

        val reader = SourceCodeDocumentReader(source, KotlinSourceCodeParser(), chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        val classDoc = docs.find { it.metadata["declaration_type"] == "class" }
        assertThat(classDoc).isNotNull
        assertThat(classDoc!!.text).startsWith("## MyClass\n")
    }

    @Test
    fun `method document heading prefix contains class name and method name`() {
        val source = """
            class MyClass {
                fun foo() {}
            }
        """.trimIndent()

        val reader = SourceCodeDocumentReader(source, KotlinSourceCodeParser(), chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        val methodDoc = docs.find { it.metadata["declaration_type"] == "method" }
        assertThat(methodDoc).isNotNull
        assertThat(methodDoc!!.text).startsWith("## MyClass\n### foo\n")
    }

    @Test
    fun `package declaration appears in each document's code block`() {
        val source = """
            package com.example.myapp

            class MyClass {
                fun foo() {}
            }
        """.trimIndent()

        val reader = SourceCodeDocumentReader(source, KotlinSourceCodeParser(), chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).isNotEmpty
        docs.forEach { doc ->
            assertThat(doc.text).contains("package com.example.myapp")
        }
    }

    @Test
    fun `import declarations appear in each document's code block`() {
        val source = """
            import java.util.List
            import java.util.Map

            class MyClass {
                fun foo() {}
            }
        """.trimIndent()

        val reader = SourceCodeDocumentReader(source, KotlinSourceCodeParser(), chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).isNotEmpty
        docs.forEach { doc ->
            assertThat(doc.text).contains("import java.util.List")
            assertThat(doc.text).contains("import java.util.Map")
        }
    }

    @Test
    fun `oversized method body triggers safety valve and produces multiple sub-chunks`() {
        // Build a method whose body has many paragraphs (separated by blank lines), each with multiple tokens.
        // Total body will far exceed the chunkSize budget.
        val methodParagraphs = (1..20).joinToString("\n\n") { i ->
            "    // Block $i\n    val variable${i}a = \"string value alpha number $i alpha\"\n    val variable${i}b = \"string value beta number $i beta\""
        }
        val source = """
            class MyClass {
                fun bigMethod() {
$methodParagraphs
                }
            }
        """.trimIndent()

        // chunkSize large enough for overhead (heading + code fence ~ 20 tokens) but body (20 paragraphs * ~20 tokens each = ~400 tokens) must split
        val chunkSize = 80
        val reader = SourceCodeDocumentReader(source, KotlinSourceCodeParser(), chunkSize = chunkSize, chunkOverlap = 5)
        val docs = reader.read()

        val methodDocs = docs.filter { it.metadata["declaration_type"] == "method" }
        assertThat(methodDocs.size).isGreaterThan(1)

        // Each sub-chunk total token count should be <= chunkSize
        methodDocs.forEach { doc ->
            val tokenCount = TokenCounter.countTokens(doc.text ?: "")
            assertThat(tokenCount).isLessThanOrEqualTo(chunkSize)
        }
    }

    @Test
    fun `nested class document has qualified name in heading`() {
        val source = """
            class Outer {
                class Inner {
                    fun innerMethod() {}
                }
            }
        """.trimIndent()

        val reader = SourceCodeDocumentReader(source, KotlinSourceCodeParser(), chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        val innerClassDoc = docs.find {
            it.metadata["declaration_type"] == "class" && it.metadata["declaration_name"] == "Outer.Inner"
        }
        assertThat(innerClassDoc).isNotNull
        assertThat(innerClassDoc!!.text).startsWith("## Outer.Inner\n")
    }

    @Test
    fun `all chunks from a kotlin file contain kotlin language tag in fenced code block`() {
        val source = """
            package com.example

            import java.util.List

            class MyClass {
                fun foo() {}
                fun bar() {}
            }

            fun topLevel() {}
        """.trimIndent()

        val reader = SourceCodeDocumentReader(source, KotlinSourceCodeParser(), chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs).isNotEmpty
        docs.forEach { doc ->
            assertThat(doc.text).contains("```kotlin")
        }
    }

    @Test
    fun `class chunk and method chunks are all produced for a two-method class`() {
        val source = """
            class MyClass {
                fun foo() {
                    println("foo")
                }
                fun bar() {
                    println("bar")
                }
            }
        """.trimIndent()

        val reader = SourceCodeDocumentReader(source, KotlinSourceCodeParser(), chunkSize = 1000, chunkOverlap = 200)
        val docs = reader.read()

        assertThat(docs.count { it.metadata["declaration_type"] == "class" }).isEqualTo(1)
        assertThat(docs.count { it.metadata["declaration_type"] == "method" }).isEqualTo(2)
    }
}
