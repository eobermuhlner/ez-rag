package ch.obermuhlner.ezrag.ingestion

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BinaryDetectorTest {

    @Test
    fun `empty byte array is not binary`() {
        assertThat(BinaryDetector.isBinary(ByteArray(0))).isFalse()
    }

    @Test
    fun `all printable ASCII bytes are not binary`() {
        assertThat(BinaryDetector.isBinary("hello world".toByteArray())).isFalse()
    }

    @Test
    fun `byte array with null byte anywhere is binary`() {
        assertThat(BinaryDetector.isBinary(byteArrayOf(0x41, 0x00, 0x42))).isTrue()
    }

    @Test
    fun `null byte at index 8192 is outside scan window and not binary`() {
        val bytes = ByteArray(9000) { 0x41.toByte() }
        bytes[8192] = 0x00
        assertThat(BinaryDetector.isBinary(bytes)).isFalse()
    }

    @Test
    fun `explicit length parameter moves null byte outside scan window`() {
        assertThat(BinaryDetector.isBinary(byteArrayOf(0x41, 0x00), length = 1)).isFalse()
    }

    @Test
    fun `UTF-8 multi-byte sequences are not binary`() {
        assertThat(BinaryDetector.isBinary("café".toByteArray(Charsets.UTF_8))).isFalse()
    }
}
