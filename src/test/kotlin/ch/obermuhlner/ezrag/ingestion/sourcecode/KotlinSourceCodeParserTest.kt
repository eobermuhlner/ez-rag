package ch.obermuhlner.ezrag.ingestion.sourcecode

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class KotlinSourceCodeParserTest {

    private val parser = KotlinSourceCodeParser()

    @Test
    fun `class with two methods produces one CLASS declaration and two METHOD declarations`() {
        val source = """
            package com.example

            class MyClass {
                fun foo() {
                    println("foo")
                }

                fun bar() {
                    println("bar")
                }
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val classDecls = declarations.filter { it.declarationType == DeclarationType.CLASS }
        val methodDecls = declarations.filter { it.declarationType == DeclarationType.METHOD }

        assertThat(classDecls).hasSize(1)
        assertThat(classDecls[0].declarationName).isEqualTo("MyClass")

        assertThat(methodDecls).hasSize(2)
        val methodNames = methodDecls.map { it.declarationName }
        assertThat(methodNames).containsExactlyInAnyOrder("foo", "bar")
    }

    @Test
    fun `class declaration has correct declarationType and declarationName`() {
        val source = """
            class MyClass {
                fun doSomething() {}
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val classDecl = declarations.find { it.declarationType == DeclarationType.CLASS }
        assertThat(classDecl).isNotNull
        assertThat(classDecl!!.declarationName).isEqualTo("MyClass")
        assertThat(classDecl.declarationType).isEqualTo(DeclarationType.CLASS)
    }

    @Test
    fun `method declarations have correct declarationType, declarationName, and className`() {
        val source = """
            class MyClass {
                fun foo() {}
                fun bar() {}
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val methodDecls = declarations.filter { it.declarationType == DeclarationType.METHOD }
        assertThat(methodDecls).hasSize(2)
        methodDecls.forEach { decl ->
            assertThat(decl.className).isEqualTo("MyClass")
        }
        assertThat(methodDecls.map { it.declarationName }).containsExactlyInAnyOrder("foo", "bar")
    }

    @Test
    fun `top-level function produces FUNCTION declaration with null className`() {
        val source = """
            package com.example

            fun myFun() {
                println("hello")
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val funcDecls = declarations.filter { it.declarationType == DeclarationType.FUNCTION }
        assertThat(funcDecls).hasSize(1)
        assertThat(funcDecls[0].declarationName).isEqualTo("myFun")
        assertThat(funcDecls[0].className).isNull()
    }

    @Test
    fun `nested class produces CLASS declaration with qualified name`() {
        val source = """
            class Outer {
                class Inner {
                    fun innerMethod() {}
                }
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val classDecls = declarations.filter { it.declarationType == DeclarationType.CLASS }
        val outerDecl = classDecls.find { it.declarationName == "Outer" }
        val innerDecl = classDecls.find { it.declarationName == "Outer.Inner" }

        assertThat(outerDecl).isNotNull
        assertThat(innerDecl).isNotNull
        assertThat(innerDecl!!.className).isEqualTo("Outer")
    }

    @Test
    fun `secondary constructor produces CONSTRUCTOR declaration`() {
        val source = """
            class MyClass(val name: String) {
                constructor(name: String, value: Int) : this(name) {
                    println(value)
                }
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val constructorDecls = declarations.filter { it.declarationType == DeclarationType.CONSTRUCTOR }
        assertThat(constructorDecls).hasSize(1)
        assertThat(constructorDecls[0].className).isEqualTo("MyClass")
    }

    @Test
    fun `KDoc comment is included in method fullText`() {
        val source = """
            class MyClass {
                /**
                 * This is a KDoc comment for foo.
                 */
                fun foo() {
                    println("foo")
                }
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val methodDecl = declarations.find { it.declarationType == DeclarationType.METHOD && it.declarationName == "foo" }
        assertThat(methodDecl).isNotNull
        assertThat(methodDecl!!.fullText).contains("KDoc comment for foo")
        assertThat(methodDecl.fullText).contains("fun foo()")
    }

    @Test
    fun `package declaration is extracted correctly`() {
        val source = """
            package com.example.myapp

            class MyClass {}
        """.trimIndent()

        val declarations = parser.parse(source)

        assertThat(declarations).isNotEmpty
        declarations.forEach { decl ->
            assertThat(decl.packageName).isEqualTo("com.example.myapp")
        }
    }

    @Test
    fun `import declarations are extracted correctly`() {
        val source = """
            package com.example

            import java.util.List
            import java.util.Map

            class MyClass {
                fun doSomething() {}
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        assertThat(declarations).isNotEmpty
        declarations.forEach { decl ->
            assertThat(decl.imports).hasSize(2)
            assertThat(decl.imports).anyMatch { it.contains("java.util.List") }
            assertThat(decl.imports).anyMatch { it.contains("java.util.Map") }
        }
    }

    @Test
    fun `all declarations share the same package and imports`() {
        val source = """
            package com.example

            import java.util.List

            class MyClass {
                fun method1() {}
                fun method2() {}
            }

            fun topLevel() {}
        """.trimIndent()

        val declarations = parser.parse(source)

        assertThat(declarations).hasSizeGreaterThan(1)
        declarations.forEach { decl ->
            assertThat(decl.packageName).isEqualTo("com.example")
            assertThat(decl.imports).anyMatch { it.contains("java.util.List") }
        }
    }

    @Test
    fun `class body text does not contain full body of nested class`() {
        val source = """
            class Outer {
                class Inner {
                    fun innerOnlyMethod() {
                        println("this should not appear in Outer class fullText")
                    }
                }

                fun outerMethod() {}
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val outerDecl = declarations.find { it.declarationType == DeclarationType.CLASS && it.declarationName == "Outer" }
        assertThat(outerDecl).isNotNull
        assertThat(outerDecl!!.fullText).doesNotContain("this should not appear in Outer class fullText")
        assertThat(outerDecl.fullText).contains("outerMethod")
    }

    @Test
    fun `class with no methods produces exactly one CLASS declaration`() {
        val source = """
            class EmptyClass
        """.trimIndent()

        val declarations = parser.parse(source)

        assertThat(declarations).hasSize(1)
        assertThat(declarations[0].declarationType).isEqualTo(DeclarationType.CLASS)
        assertThat(declarations[0].declarationName).isEqualTo("EmptyClass")
    }

    @Test
    fun `method fullText contains signature and body`() {
        val source = """
            class MyClass {
                fun calculate(x: Int, y: Int): Int {
                    return x + y
                }
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val methodDecl = declarations.find { it.declarationType == DeclarationType.METHOD }
        assertThat(methodDecl).isNotNull
        assertThat(methodDecl!!.fullText).contains("fun calculate(x: Int, y: Int): Int")
        assertThat(methodDecl.fullText).contains("return x + y")
    }

    @Test
    fun `nested class inner method has qualified className`() {
        val source = """
            class Outer {
                class Inner {
                    fun innerMethod() {}
                }
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val innerMethod = declarations.find { it.declarationType == DeclarationType.METHOD && it.declarationName == "innerMethod" }
        assertThat(innerMethod).isNotNull
        assertThat(innerMethod!!.className).isEqualTo("Outer.Inner")
    }

    @Test
    fun `source with no declarations produces empty list`() {
        val source = """
            package com.example

            import java.util.List
        """.trimIndent()

        val declarations = parser.parse(source)

        assertThat(declarations).isEmpty()
    }
}
