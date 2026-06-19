package ch.obermuhlner.ezrag.example

/**
 * Utility class providing common string manipulation operations.
 */
class StringUtils {

    /**
     * Truncates a string to the specified maximum length.
     * If the string is longer than maxLength, it is cut off and an ellipsis is appended.
     * @param text the input string to truncate
     * @param maxLength the maximum allowed length of the result including the ellipsis
     * @return the truncated string with ellipsis if shortened, or the original string if it fits
     */
    fun truncate(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        return text.substring(0, maxLength - 3) + "..."
    }

    /**
     * Pads a string on the left with a specified character until it reaches the desired width.
     * If the string is already at or longer than the desired width, it is returned unchanged.
     * @param text the input string to pad
     * @param width the total desired width of the padded result
     * @param padChar the character used for left-padding; defaults to a space
     * @return the left-padded string of the specified width
     */
    fun padLeft(text: String, width: Int, padChar: Char = ' '): String {
        if (text.length >= width) return text
        return padChar.toString().repeat(width - text.length) + text
    }

    /**
     * Counts the number of words in a string by splitting on whitespace boundaries.
     * A word is defined as a non-empty sequence of characters separated by whitespace.
     * Empty strings and strings containing only whitespace return a word count of zero.
     * @param text the input string whose words are to be counted
     * @return the number of words found in the input string
     */
    fun countWords(text: String): Int {
        if (text.isBlank()) return 0
        return text.trim().split(Regex("\\s+")).size
    }
}
