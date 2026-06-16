package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BinaryTextStripperTest {

    @Test
    fun `empty byte array returns empty string`() {
        assertThat(BinaryTextStripper.strip(ByteArray(0))).isEqualTo("")
    }

    @Test
    fun `all non-printable bytes returns empty string`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
        assertThat(BinaryTextStripper.strip(bytes)).isEqualTo("")
    }

    @Test
    fun `single run of exactly 3 printable chars returns empty string`() {
        // "abc" surrounded by null bytes — below minimum run length of 4
        val bytes = byteArrayOf(0x00, 0x61, 0x62, 0x63, 0x00)
        assertThat(BinaryTextStripper.strip(bytes)).isEqualTo("")
    }

    @Test
    fun `single run of exactly 4 printable chars returns that string`() {
        // "test" surrounded by null bytes
        val bytes = byteArrayOf(0x00, 0x74, 0x65, 0x73, 0x74, 0x00)
        assertThat(BinaryTextStripper.strip(bytes)).isEqualTo("test")
    }

    @Test
    fun `two separated runs of 4+ chars are joined with newline`() {
        // "hello" + null bytes + "world"
        val hello = "hello".toByteArray()
        val world = "world".toByteArray()
        val bytes = hello + byteArrayOf(0x00, 0x00) + world
        assertThat(BinaryTextStripper.strip(bytes)).isEqualTo("hello\nworld")
    }

    @Test
    fun `embedded newline within a run is preserved`() {
        // "line1\nline2" — newline is part of the printable set and stays in the output
        val text = "line1\nline2"
        val bytes = byteArrayOf(0x00) + text.toByteArray() + byteArrayOf(0x00)
        assertThat(BinaryTextStripper.strip(bytes)).isEqualTo("line1\nline2")
    }

    @Test
    fun `mixed printable and non-printable bytes returns only qualifying runs`() {
        // 2 printable chars (below min), then garbage, then "hello" (qualifying)
        val bytes = byteArrayOf(0x41, 0x42, 0x00, 0x01) + "hello".toByteArray() + byteArrayOf(0x00)
        assertThat(BinaryTextStripper.strip(bytes)).isEqualTo("hello")
    }

    @Test
    fun `byte outside 0x20-0x7E and not tab-newline-cr breaks current run`() {
        // "hel" + 0x01 (non-printable, not whitespace) + "lo" — two short runs each below 4
        val bytes = byteArrayOf(0x68, 0x65, 0x6C, 0x01, 0x6C, 0x6F)
        assertThat(BinaryTextStripper.strip(bytes)).isEqualTo("")
    }
}
