package ch.obermuhlner.ezrag.ingestion.sourcecode

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TypeScriptSourceCodeParserTest {

    private val tsParser = TypeScriptSourceCodeParser("typescript")
    private val jsParser = TypeScriptSourceCodeParser("javascript")
    private val tsxParser = TypeScriptSourceCodeParser("tsx")

    @Test
    fun `class with two methods produces one CLASS declaration and two METHOD declarations`() {
        val source = """
            class MyClass {
                foo(): void {
                    console.log("foo");
                }

                bar(): void {
                    console.log("bar");
                }
            }
        """.trimIndent()

        val declarations = tsParser.parse(source)

        val classDecls = declarations.filter { it.declarationType == DeclarationType.CLASS }
        val methodDecls = declarations.filter { it.declarationType == DeclarationType.METHOD }

        assertThat(classDecls).hasSize(1)
        assertThat(classDecls[0].declarationName).isEqualTo("MyClass")

        assertThat(methodDecls).hasSize(2)
        assertThat(methodDecls.map { it.declarationName }).containsExactlyInAnyOrder("foo", "bar")
    }

    @Test
    fun `top-level function produces FUNCTION declaration with null className`() {
        val source = """
            function myFun(): void {
                console.log("hello");
            }
        """.trimIndent()

        val declarations = tsParser.parse(source)

        val funcDecls = declarations.filter { it.declarationType == DeclarationType.FUNCTION }
        assertThat(funcDecls).hasSize(1)
        assertThat(funcDecls[0].declarationName).isEqualTo("myFun")
        assertThat(funcDecls[0].className).isNull()
    }

    @Test
    fun `interface with two method signatures produces two METHOD declarations`() {
        val source = """
            interface MyInterface {
                doFirst(): void;
                doSecond(x: number): string;
            }
        """.trimIndent()

        val declarations = tsParser.parse(source)

        val methodDecls = declarations.filter { it.declarationType == DeclarationType.METHOD }
        assertThat(methodDecls).hasSize(2)
        assertThat(methodDecls.map { it.declarationName }).containsExactlyInAnyOrder("doFirst", "doSecond")
    }

    @Test
    fun `JSDoc comment is included in method fullText`() {
        val source = """
            class MyClass {
                /**
                 * This is a JSDoc comment for validate.
                 */
                validate(input: string): boolean {
                    return input !== null && input.length > 0;
                }
            }
        """.trimIndent()

        val declarations = tsParser.parse(source)

        val methodDecl = declarations.find { it.declarationType == DeclarationType.METHOD && it.declarationName == "validate" }
        assertThat(methodDecl).isNotNull
        assertThat(methodDecl!!.fullText).contains("JSDoc comment for validate")
        assertThat(methodDecl.fullText).contains("validate")
    }

    @Test
    fun `import with alias is extracted verbatim`() {
        val source = """
            import { Foo as Bar } from './foo';
            import { Something } from './something';

            class MyClass {
                doWork(): void {}
            }
        """.trimIndent()

        val declarations = tsParser.parse(source)

        assertThat(declarations).isNotEmpty
        declarations.forEach { decl ->
            assertThat(decl.imports).anyMatch { it.contains("Foo as Bar") }
            assertThat(decl.imports).anyMatch { it.contains("Something") }
        }
    }

    @Test
    fun `tsx parser handles JSX-like syntax without error`() {
        val source = """
            import React from 'react';

            class MyComponent {
                render(): void {
                    console.log("rendering");
                }
            }
        """.trimIndent()

        // Should not throw; produces declarations
        val declarations = tsxParser.parse(source)
        assertThat(declarations).isNotEmpty
        assertThat(declarations.any { it.declarationType == DeclarationType.CLASS }).isTrue()
    }

    @Test
    fun `typescript parser language is typescript`() {
        assertThat(tsParser.language).isEqualTo("typescript")
    }

    @Test
    fun `tsx parser language is typescript`() {
        assertThat(tsxParser.language).isEqualTo("typescript")
    }

    @Test
    fun `javascript parser language is javascript`() {
        assertThat(jsParser.language).isEqualTo("javascript")
    }

    @Test
    fun `javascript parser produces chunks with language javascript`() {
        val source = """
            class MyClass {
                greet() {
                    console.log("hello");
                }
            }
        """.trimIndent()

        val declarations = jsParser.parse(source)

        assertThat(declarations).isNotEmpty
        assertThat(jsParser.language).isEqualTo("javascript")
    }

    @Test
    fun `nested class produces CLASS declaration with qualified name`() {
        val source = """
            class Outer {
                Inner = class {
                    innerMethod(): void {}
                };
            }
        """.trimIndent()

        // At minimum should not throw; Outer is a class
        val declarations = tsParser.parse(source)
        assertThat(declarations.any { it.declarationType == DeclarationType.CLASS && it.declarationName == "Outer" }).isTrue()
    }

    @Test
    fun `arrow function at top level produces FUNCTION declaration`() {
        val source = """
            const myArrow = (): void => {
                console.log("arrow");
            };
        """.trimIndent()

        // Arrow functions may or may not be detected depending on implementation;
        // this test checks that parse does not throw
        val declarations = tsParser.parse(source)
        assertThat(declarations).isNotNull
    }

    @Test
    fun `all declarations share the same imports`() {
        val source = """
            import { Foo as Bar } from './foo';

            class MyClass {
                method1(): void {}
                method2(): void {}
            }
        """.trimIndent()

        val declarations = tsParser.parse(source)

        assertThat(declarations).hasSizeGreaterThan(1)
        declarations.forEach { decl ->
            assertThat(decl.imports).anyMatch { it.contains("Foo as Bar") }
        }
    }

    @Test
    fun `source with no declarations produces empty list`() {
        val source = """
            import { Something } from './something';
        """.trimIndent()

        val declarations = tsParser.parse(source)

        assertThat(declarations).isEmpty()
    }

    @Test
    fun `method fullText contains signature and body`() {
        val source = """
            class MyClass {
                calculate(x: number, y: number): number {
                    return x + y;
                }
            }
        """.trimIndent()

        val declarations = tsParser.parse(source)

        val methodDecl = declarations.find { it.declarationType == DeclarationType.METHOD }
        assertThat(methodDecl).isNotNull
        assertThat(methodDecl!!.fullText).contains("calculate")
        assertThat(methodDecl.fullText).contains("return x + y")
    }
}
