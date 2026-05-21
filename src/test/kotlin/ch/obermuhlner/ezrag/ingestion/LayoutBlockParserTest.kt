package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LayoutBlockParserTest {

    @Test
    fun `empty string returns empty list`() {
        assertThat(LayoutBlockParser.parse("")).isEmpty()
    }

    @Test
    fun `whitespace-only input returns empty list`() {
        assertThat(LayoutBlockParser.parse("   \n\n  \n")).isEmpty()
    }

    @Test
    fun `single paragraph`() {
        val blocks = LayoutBlockParser.parse("Hello world")
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.Paragraph::class.java)
        assertThat((blocks[0] as LayoutBlock.Paragraph).text).isEqualTo("Hello world")
    }

    @Test
    fun `two paragraphs separated by blank line`() {
        val blocks = LayoutBlockParser.parse("First paragraph\n\nSecond paragraph")
        assertThat(blocks).hasSize(2)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.Paragraph::class.java)
        assertThat(blocks[1]).isInstanceOf(LayoutBlock.Paragraph::class.java)
        assertThat((blocks[0] as LayoutBlock.Paragraph).text).isEqualTo("First paragraph")
        assertThat((blocks[1] as LayoutBlock.Paragraph).text).isEqualTo("Second paragraph")
    }

    @Test
    fun `fenced code block with backtick fence`() {
        val input = "```\nval x = 1\nprintln(x)\n```"
        val blocks = LayoutBlockParser.parse(input)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.FencedCodeBlock::class.java)
    }

    @Test
    fun `fenced code block with tilde fence`() {
        val input = "~~~\nsome code\n~~~"
        val blocks = LayoutBlockParser.parse(input)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.FencedCodeBlock::class.java)
    }

    @Test
    fun `fenced code block containing pipe characters is not parsed as table`() {
        val input = "```\n| col1 | col2 |\n|------|------|\n| a    | b    |\n```"
        val blocks = LayoutBlockParser.parse(input)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.FencedCodeBlock::class.java)
    }

    @Test
    fun `table block`() {
        val input = "| Name | Age |\n|------|-----|\n| Alice | 30 |"
        val blocks = LayoutBlockParser.parse(input)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.Table::class.java)
    }

    @Test
    fun `bullet list with dash items`() {
        val input = "- item one\n- item two\n- item three"
        val blocks = LayoutBlockParser.parse(input)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.BulletList::class.java)
        val list = blocks[0] as LayoutBlock.BulletList
        assertThat(list.items).containsExactly("- item one", "- item two", "- item three")
    }

    @Test
    fun `bullet list with asterisk items`() {
        val input = "* item one\n* item two"
        val blocks = LayoutBlockParser.parse(input)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.BulletList::class.java)
    }

    @Test
    fun `bullet list with plus items`() {
        val input = "+ item one\n+ item two"
        val blocks = LayoutBlockParser.parse(input)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.BulletList::class.java)
    }

    @Test
    fun `numbered list parses as BulletList`() {
        val input = "1. First item\n2. Second item\n3. Third item"
        val blocks = LayoutBlockParser.parse(input)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.BulletList::class.java)
        val list = blocks[0] as LayoutBlock.BulletList
        assertThat(list.items).containsExactly("1. First item", "2. Second item", "3. Third item")
    }

    @Test
    fun `block quote`() {
        val input = "> This is a quote\n> that spans lines"
        val blocks = LayoutBlockParser.parse(input)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.BlockQuote::class.java)
    }

    @Test
    fun `horizontal rule with dashes`() {
        val blocks = LayoutBlockParser.parse("---")
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.HorizontalRule::class.java)
    }

    @Test
    fun `horizontal rule with underscores`() {
        val blocks = LayoutBlockParser.parse("___")
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.HorizontalRule::class.java)
    }

    @Test
    fun `horizontal rule with asterisks`() {
        val blocks = LayoutBlockParser.parse("***")
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.HorizontalRule::class.java)
    }

    @Test
    fun `mixed content returns blocks in order`() {
        val input = """
            First paragraph

            - list item one
            - list item two

            | col1 | col2 |
            |------|------|
            | a    | b    |

            > block quote

            ---

            Second paragraph
        """.trimIndent()

        val blocks = LayoutBlockParser.parse(input)
        assertThat(blocks).hasSize(6)
        assertThat(blocks[0]).isInstanceOf(LayoutBlock.Paragraph::class.java)
        assertThat(blocks[1]).isInstanceOf(LayoutBlock.BulletList::class.java)
        assertThat(blocks[2]).isInstanceOf(LayoutBlock.Table::class.java)
        assertThat(blocks[3]).isInstanceOf(LayoutBlock.BlockQuote::class.java)
        assertThat(blocks[4]).isInstanceOf(LayoutBlock.HorizontalRule::class.java)
        assertThat(blocks[5]).isInstanceOf(LayoutBlock.Paragraph::class.java)
    }

    @Test
    fun `fenced code block text contains all lines including fences`() {
        val input = "```kotlin\nval x = 1\n```"
        val blocks = LayoutBlockParser.parse(input)
        val code = blocks[0] as LayoutBlock.FencedCodeBlock
        assertThat(code.text).contains("val x = 1")
    }

    @Test
    fun `paragraph multiline`() {
        val input = "Line one\nLine two\nLine three"
        val blocks = LayoutBlockParser.parse(input)
        assertThat(blocks).hasSize(1)
        val para = blocks[0] as LayoutBlock.Paragraph
        assertThat(para.text).isEqualTo("Line one\nLine two\nLine three")
    }
}
