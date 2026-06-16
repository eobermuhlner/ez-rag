package ch.obermuhlner.ezrag.ingestion

object BinaryTextStripper {
    private const val MIN_RUN_LENGTH = 4

    fun strip(bytes: ByteArray): String {
        val runs = mutableListOf<String>()
        val currentRun = StringBuilder()

        for (byte in bytes) {
            val ch = byte.toInt() and 0xFF
            if (isPrintable(ch)) {
                currentRun.append(ch.toChar())
            } else {
                if (currentRun.length >= MIN_RUN_LENGTH) {
                    runs.add(currentRun.toString())
                }
                currentRun.clear()
            }
        }

        // Handle any trailing run
        if (currentRun.length >= MIN_RUN_LENGTH) {
            runs.add(currentRun.toString())
        }

        return runs.joinToString("\n")
    }

    private fun isPrintable(ch: Int): Boolean {
        return ch in 0x20..0x7E || ch == 0x09 || ch == 0x0A || ch == 0x0D
    }
}
