package ch.obermuhlner.ezrag.ingestion

import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TokenTextSplitter

class SectionSplitter(
    private val chunkSize: Int,
    private val chunkOverlap: Int,
    private val tokenCounter: (String) -> Int
) {
    fun splitSection(bodyText: String, headingPrefix: String): List<String> {
        val prefixCost = if (headingPrefix.isEmpty()) 0 else tokenCounter("$headingPrefix\n")
        val budget = chunkSize - prefixCost

        val blocks = LayoutBlockParser.parse(bodyText)
        val subChunks = mutableListOf<String>()
        var buffer = ""

        fun candidate(existing: String, next: String) =
            if (existing.isEmpty()) next else "$existing\n$next"

        fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                subChunks.add(buffer)
                buffer = ""
            }
        }

        fun blockText(block: LayoutBlock): String = when (block) {
            is LayoutBlock.Paragraph -> block.text
            is LayoutBlock.FencedCodeBlock -> block.text
            is LayoutBlock.Table -> block.text
            is LayoutBlock.BulletList -> block.text
            is LayoutBlock.BlockQuote -> block.text
            is LayoutBlock.HorizontalRule -> ""
        }

        fun splitWithFallback(text: String): List<String> {
            val doc = Document.builder().text(text).build()
            val splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkSize)
                .withMinChunkSizeChars(chunkOverlap)
                .withMinChunkLengthToEmbed(1)
                .withMaxNumChunks(10000)
                .withKeepSeparator(true)
                .build()
            return splitter.apply(listOf(doc)).map { it.text ?: "" }
        }

        fun handleOversized(block: LayoutBlock) {
            when (block) {
                is LayoutBlock.BulletList -> {
                    var itemBuffer = ""
                    for (item in block.items) {
                        val c = candidate(itemBuffer, item)
                        when {
                            tokenCounter(c) <= budget -> itemBuffer = c
                            itemBuffer.isNotEmpty() -> {
                                subChunks.add(itemBuffer)
                                itemBuffer = if (tokenCounter(item) <= budget) item
                                else {
                                    subChunks.addAll(splitWithFallback(item))
                                    ""
                                }
                            }
                            else -> {
                                subChunks.addAll(splitWithFallback(item))
                            }
                        }
                    }
                    if (itemBuffer.isNotEmpty()) {
                        subChunks.add(itemBuffer)
                    }
                }
                is LayoutBlock.Table,
                is LayoutBlock.FencedCodeBlock,
                is LayoutBlock.BlockQuote -> subChunks.add(blockText(block))
                is LayoutBlock.Paragraph -> subChunks.addAll(splitWithFallback(blockText(block)))
                is LayoutBlock.HorizontalRule -> { /* never reached */ }
            }
        }

        for (block in blocks) {
            if (block is LayoutBlock.HorizontalRule) {
                flushBuffer()
                continue
            }

            val text = blockText(block)
            val c = candidate(buffer, text)

            when {
                tokenCounter(c) <= budget -> buffer = c
                buffer.isNotEmpty() -> {
                    flushBuffer()
                    if (tokenCounter(text) <= budget) {
                        buffer = text
                    } else {
                        handleOversized(block)
                    }
                }
                else -> handleOversized(block)
            }
        }

        flushBuffer()

        return if (headingPrefix.isEmpty()) subChunks
        else subChunks.map { "$headingPrefix\n$it" }
    }
}
