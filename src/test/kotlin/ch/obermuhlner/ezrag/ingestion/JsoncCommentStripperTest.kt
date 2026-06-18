package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JsoncCommentStripperTest {

    @Test
    fun `line comment is removed`() {
        val input = """{"key": "value"} // this is a comment"""
        val result = JsoncCommentStripper.strip(input)
        assertThat(result).doesNotContain("this is a comment")
        assertThat(result).contains("value")
    }

    @Test
    fun `block comment is removed`() {
        val input = """{"key": /* block comment */ "value"}"""
        val result = JsoncCommentStripper.strip(input)
        assertThat(result).doesNotContain("block comment")
        assertThat(result).contains("value")
    }

    @Test
    fun `multiline block comment is removed`() {
        val input = """
            {
                /* this is
                   a multiline
                   block comment */
                "key": "value"
            }
        """.trimIndent()
        val result = JsoncCommentStripper.strip(input)
        assertThat(result).doesNotContain("multiline")
        assertThat(result).contains("value")
    }

    @Test
    fun `comment-like text inside string literal is preserved`() {
        val input = """{"url": "http://example.com"}"""
        val result = JsoncCommentStripper.strip(input)
        assertThat(result).contains("http://example.com")
    }

    @Test
    fun `block comment inside string literal is preserved`() {
        val input = """{"desc": "/* not a comment */"}"""
        val result = JsoncCommentStripper.strip(input)
        assertThat(result).contains("/* not a comment */")
    }

    @Test
    fun `empty string returns empty string`() {
        val result = JsoncCommentStripper.strip("")
        assertThat(result).isEmpty()
    }

    @Test
    fun `input with no comments returns output identical to input`() {
        val input = """{"key": "value", "count": 42}"""
        val result = JsoncCommentStripper.strip(input)
        assertThat(result).isEqualTo(input)
    }

    @Test
    fun `comment at end of file without trailing newline is stripped`() {
        val input = """{"key": "value"} // end of file comment"""
        val result = JsoncCommentStripper.strip(input)
        assertThat(result).doesNotContain("end of file comment")
        assertThat(result).contains("value")
    }

    @Test
    fun `line comment on its own line is removed`() {
        val input = "{\n// full line comment\n\"key\": \"value\"\n}"
        val result = JsoncCommentStripper.strip(input)
        assertThat(result).doesNotContain("full line comment")
        assertThat(result).contains("value")
    }

    @Test
    fun `escaped quote inside string does not confuse string tracking`() {
        val input = """{"key": "value with \"escaped quote\" inside"}"""
        val result = JsoncCommentStripper.strip(input)
        assertThat(result).contains("escaped quote")
    }
}
