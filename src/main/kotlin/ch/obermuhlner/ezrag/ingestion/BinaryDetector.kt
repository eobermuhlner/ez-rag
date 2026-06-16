package ch.obermuhlner.ezrag.ingestion

object BinaryDetector {
    private const val SCAN_LIMIT = 8192

    fun isBinary(bytes: ByteArray, length: Int = bytes.size): Boolean {
        val scanEnd = minOf(length, SCAN_LIMIT)
        for (i in 0 until scanEnd) {
            if (bytes[i] == 0x00.toByte()) return true
        }
        return false
    }
}
