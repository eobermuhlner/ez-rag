package ch.obermuhlner.ezrag.ingestion.sourcecode

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JavaSourceCodeParserTest {

    private val parser = JavaSourceCodeParser()

    @Test
    fun `class with two methods produces one CLASS declaration and two METHOD declarations`() {
        val source = """
            package com.example;

            class MyClass {
                public void foo() {
                    System.out.println("foo");
                }

                public void bar() {
                    System.out.println("bar");
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
    fun `constructor produces CONSTRUCTOR declaration with correct className and language`() {
        val source = """
            package com.example;

            public class MyClass {
                public MyClass(String name) {
                    this.name = name;
                }
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val constructorDecls = declarations.filter { it.declarationType == DeclarationType.CONSTRUCTOR }
        assertThat(constructorDecls).hasSize(1)
        assertThat(constructorDecls[0].className).isEqualTo("MyClass")
        assertThat(constructorDecls[0].declarationName).isEqualTo("MyClass")
    }

    @Test
    fun `interface with two abstract method signatures produces two METHOD declarations`() {
        val source = """
            package com.example;

            public interface MyInterface {
                void doFirst();
                String doSecond(int x);
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val methodDecls = declarations.filter { it.declarationType == DeclarationType.METHOD }
        assertThat(methodDecls).hasSize(2)
        assertThat(methodDecls.map { it.declarationName }).containsExactlyInAnyOrder("doFirst", "doSecond")
        methodDecls.forEach { decl ->
            assertThat(decl.declarationType).isEqualTo(DeclarationType.METHOD)
        }
    }

    @Test
    fun `Javadoc comment is included in method fullText`() {
        val source = """
            package com.example;

            public class MyClass {
                /**
                 * This is a Javadoc comment for validate.
                 */
                public boolean validate(String input) {
                    return input != null && !input.isEmpty();
                }
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val methodDecl = declarations.find { it.declarationType == DeclarationType.METHOD && it.declarationName == "validate" }
        assertThat(methodDecl).isNotNull
        assertThat(methodDecl!!.fullText).contains("Javadoc comment for validate")
        assertThat(methodDecl.fullText).contains("validate")
    }

    @Test
    fun `package declaration is extracted correctly`() {
        val source = """
            package com.example.myapp;

            public class MyClass {
                public void doWork() {}
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        assertThat(declarations).isNotEmpty
        declarations.forEach { decl ->
            assertThat(decl.packageName).isEqualTo("com.example.myapp")
        }
    }

    @Test
    fun `import declarations are extracted and appear in all chunks`() {
        val source = """
            package com.example;

            import java.util.List;
            import java.util.Map;

            public class MyClass {
                public void doSomething() {}
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
    fun `nested class produces CLASS declaration with qualified name`() {
        val source = """
            public class Outer {
                public class Inner {
                    public void innerMethod() {}
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
    fun `outer class body does not contain full body of nested class`() {
        val source = """
            public class Outer {
                public class Inner {
                    public void innerOnlyMethod() {
                        System.out.println("this should not appear in Outer class fullText");
                    }
                }

                public void outerMethod() {}
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val outerDecl = declarations.find { it.declarationType == DeclarationType.CLASS && it.declarationName == "Outer" }
        assertThat(outerDecl).isNotNull
        assertThat(outerDecl!!.fullText).doesNotContain("this should not appear in Outer class fullText")
        assertThat(outerDecl.fullText).contains("outerMethod")
    }

    @Test
    fun `language is java`() {
        assertThat(parser.language).isEqualTo("java")
    }

    @Test
    fun `all declarations share the same package and imports`() {
        val source = """
            package com.example;

            import java.util.List;

            public class MyClass {
                public void method1() {}
                public void method2() {}
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        assertThat(declarations).hasSizeGreaterThan(1)
        declarations.forEach { decl ->
            assertThat(decl.packageName).isEqualTo("com.example")
            assertThat(decl.imports).anyMatch { it.contains("java.util.List") }
        }
    }

    @Test
    fun `method fullText contains signature and body`() {
        val source = """
            public class MyClass {
                public int calculate(int x, int y) {
                    return x + y;
                }
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val methodDecl = declarations.find { it.declarationType == DeclarationType.METHOD }
        assertThat(methodDecl).isNotNull
        assertThat(methodDecl!!.fullText).contains("calculate")
        assertThat(methodDecl.fullText).contains("return x + y")
    }

    @Test
    fun `source with no declarations produces empty list`() {
        val source = """
            package com.example;

            import java.util.List;
        """.trimIndent()

        val declarations = parser.parse(source)

        assertThat(declarations).isEmpty()
    }

    @Test
    fun `constructor fullText includes constructor body`() {
        val source = """
            public class Widget {
                private final String name;

                public Widget(String name) {
                    this.name = name;
                }
            }
        """.trimIndent()

        val declarations = parser.parse(source)

        val ctorDecl = declarations.find { it.declarationType == DeclarationType.CONSTRUCTOR }
        assertThat(ctorDecl).isNotNull
        assertThat(ctorDecl!!.fullText).contains("Widget")
        assertThat(ctorDecl.fullText).contains("this.name = name")
    }
}
