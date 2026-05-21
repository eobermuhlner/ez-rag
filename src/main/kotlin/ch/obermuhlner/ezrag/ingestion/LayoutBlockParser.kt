package ch.obermuhlner.ezrag.ingestion

sealed class LayoutBlock {
    data class Paragraph(val text: String) : LayoutBlock()
    data class FencedCodeBlock(val text: String) : LayoutBlock()
    data class Table(val text: String) : LayoutBlock()
    data class BulletList(val items: List<String>) : LayoutBlock() {
        val text: String get() = items.joinToString("\n")
    }
    data class BlockQuote(val text: String) : LayoutBlock()
    object HorizontalRule : LayoutBlock()
}

object LayoutBlockParser {

    private val FENCE_PATTERN = Regex("""^(`{3,}|~{3,})""")
    private val HORIZONTAL_RULE_PATTERN = Regex("""^(-{3,}|_{3,}|\*{3,})$""")
    private val BULLET_PATTERN = Regex("""^([-*+] |\d+\. )""")

    fun parse(text: String): List<LayoutBlock> {
        val lines = text.lines()
        val blocks = mutableListOf<LayoutBlock>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            if (line.isBlank()) {
                i++
                continue
            }

            // Priority 1: fenced code block
            val fenceMatch = FENCE_PATTERN.find(line)
            if (fenceMatch != null) {
                val fence = fenceMatch.value
                val codeLines = mutableListOf(line)
                i++
                while (i < lines.size) {
                    val codeLine = lines[i]
                    codeLines.add(codeLine)
                    i++
                    if (codeLine.trimEnd().startsWith(fence) && codeLine.trim() != line.trim()) {
                        break
                    }
                }
                blocks.add(LayoutBlock.FencedCodeBlock(codeLines.joinToString("\n")))
                continue
            }

            // Priority 2: table
            if (line.contains('|')) {
                val tableLines = mutableListOf(line)
                i++
                while (i < lines.size && lines[i].contains('|') && !lines[i].isBlank()) {
                    tableLines.add(lines[i])
                    i++
                }
                blocks.add(LayoutBlock.Table(tableLines.joinToString("\n")))
                continue
            }

            // Priority 3: bullet list
            if (BULLET_PATTERN.containsMatchIn(line)) {
                val items = mutableListOf(line)
                i++
                while (i < lines.size && !lines[i].isBlank() && BULLET_PATTERN.containsMatchIn(lines[i])) {
                    items.add(lines[i])
                    i++
                }
                blocks.add(LayoutBlock.BulletList(items))
                continue
            }

            // Priority 4: block quote
            if (line.startsWith("> ") || line == ">") {
                val quoteLines = mutableListOf(line)
                i++
                while (i < lines.size && !lines[i].isBlank() && (lines[i].startsWith("> ") || lines[i] == ">")) {
                    quoteLines.add(lines[i])
                    i++
                }
                blocks.add(LayoutBlock.BlockQuote(quoteLines.joinToString("\n")))
                continue
            }

            // Priority 5: horizontal rule
            if (HORIZONTAL_RULE_PATTERN.matches(line.trim())) {
                blocks.add(LayoutBlock.HorizontalRule)
                i++
                continue
            }

            // Priority 6: paragraph
            val paraLines = mutableListOf(line)
            i++
            while (i < lines.size && !lines[i].isBlank()
                && FENCE_PATTERN.find(lines[i]) == null
                && !lines[i].contains('|')
                && !BULLET_PATTERN.containsMatchIn(lines[i])
                && !(lines[i].startsWith("> ") || lines[i] == ">")
                && !HORIZONTAL_RULE_PATTERN.matches(lines[i].trim())
            ) {
                paraLines.add(lines[i])
                i++
            }
            blocks.add(LayoutBlock.Paragraph(paraLines.joinToString("\n")))
        }

        return blocks
    }
}
