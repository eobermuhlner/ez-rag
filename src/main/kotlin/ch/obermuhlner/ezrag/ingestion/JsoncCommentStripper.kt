package ch.obermuhlner.ezrag.ingestion

/**
 * Strips `//` line comments and `/* */` block comments from a JSONC string.
 *
 * Comment-like text inside string literals is preserved unchanged.
 * Escaped quotes (`\"`) inside strings are handled correctly.
 */
object JsoncCommentStripper {

    fun strip(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        var inString = false

        while (i < input.length) {
            val ch = input[i]

            if (inString) {
                if (ch == '\\' && i + 1 < input.length) {
                    // Escaped character — pass both through unchanged
                    sb.append(ch)
                    sb.append(input[i + 1])
                    i += 2
                    continue
                }
                if (ch == '"') {
                    inString = false
                }
                sb.append(ch)
                i++
            } else {
                when {
                    ch == '"' -> {
                        inString = true
                        sb.append(ch)
                        i++
                    }
                    ch == '/' && i + 1 < input.length && input[i + 1] == '/' -> {
                        // Line comment — skip to end of line
                        i += 2
                        while (i < input.length && input[i] != '\n') {
                            i++
                        }
                        // Leave the newline character in place (if present)
                    }
                    ch == '/' && i + 1 < input.length && input[i + 1] == '*' -> {
                        // Block comment — skip to closing */
                        i += 2
                        while (i < input.length) {
                            if (input[i] == '*' && i + 1 < input.length && input[i + 1] == '/') {
                                i += 2
                                break
                            }
                            i++
                        }
                    }
                    else -> {
                        sb.append(ch)
                        i++
                    }
                }
            }
        }

        return sb.toString()
    }
}
