package ch.obermuhlner.ezrag.ingestion

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.ai.document.Document
import org.springframework.ai.transformer.splitter.TokenTextSplitter

/**
 * Recursive engine that converts a Jackson [JsonNode] into a list of Markdown chunk strings.
 *
 * For Task 01 (json-array-ingestion): handles [ArrayNode] dispatch with budget-aware batching
 * and an oversized-element escape hatch.
 * For Task 02 (json-object-batching): handles [ObjectNode] dispatch with accumulator/batching model.
 * Object/text/primitive dispatch are added in subsequent tasks.
 *
 * Heading format:
 * - Multi-element batch: `## Items {start}–{end}` (1-based, en-dash U+2013)
 * - Single-element batch: `## Item {index}` (1-based)
 * - With parent heading path: `## parent → Items {start}–{end}`
 * - Object chunks: `## level1 → level2` (no per-key suffix)
 */
class JsonChunker(
    private val chunkSize: Int = 1000,
    private val chunkOverlap: Int = 200,
    private val tokenCounter: (String) -> Int = TokenCounter::countTokens,
) {

    private val mapper = ObjectMapper()
    private val prettyWriter = mapper.writerWithDefaultPrettyPrinter()

    fun chunk(node: JsonNode, headingPath: List<String> = emptyList()): List<String> {
        return when (node) {
            is ArrayNode -> chunkArray(node, headingPath)
            is ObjectNode -> chunkObject(node, headingPath)
            else -> emptyList()
        }
    }

    // ---- ArrayNode dispatch ----

    private fun chunkArray(array: ArrayNode, headingPath: List<String>): List<String> {
        if (array.isEmpty) return emptyList()

        val result = mutableListOf<String>()

        // Batch elements by token budget
        val currentBatch = mutableListOf<Pair<Int, String>>() // (1-based index, serialized element)
        var nextIndex = 1

        fun buildChunkText(batch: List<Pair<Int, String>>): String {
            val heading = buildArrayHeading(headingPath, batch)
            val body = batch.joinToString("\n\n") { (_, text) -> text }
            return "$heading\n\n$body"
        }

        fun flush() {
            if (currentBatch.isNotEmpty()) {
                result.add(buildChunkText(currentBatch))
                currentBatch.clear()
            }
        }

        for (element in array) {
            val elementText = prettyWriter.writeValueAsString(element)
            val elemIndex = nextIndex++

            val candidate = if (currentBatch.isEmpty()) {
                buildChunkText(listOf(elemIndex to elementText))
            } else {
                buildChunkText(currentBatch + (elemIndex to elementText))
            }

            when {
                tokenCounter(candidate) <= chunkSize -> {
                    currentBatch.add(elemIndex to elementText)
                }
                currentBatch.isEmpty() -> {
                    // Single element exceeds budget — emit it alone anyway (escape hatch)
                    result.add(buildChunkText(listOf(elemIndex to elementText)))
                }
                else -> {
                    flush()
                    // Try adding this element to a fresh batch
                    val freshCandidate = buildChunkText(listOf(elemIndex to elementText))
                    if (tokenCounter(freshCandidate) <= chunkSize) {
                        currentBatch.add(elemIndex to elementText)
                    } else {
                        // Still oversized alone — emit as its own chunk
                        result.add(buildChunkText(listOf(elemIndex to elementText)))
                    }
                }
            }
        }

        flush()
        return result
    }

    private fun buildArrayHeading(headingPath: List<String>, batch: List<Pair<Int, String>>): String {
        val rangeLabel = if (batch.size == 1) {
            "Item ${batch[0].first}"
        } else {
            "Items ${batch.first().first}-${batch.last().first}" // en-dash
        }
        return if (headingPath.isEmpty()) {
            "## $rangeLabel"
        } else {
            "## ${headingPath.joinToString(" → ")} → $rangeLabel"
        }
    }

    // ---- ObjectNode dispatch (Task 02) ----

    private fun chunkObject(obj: ObjectNode, headingPath: List<String>): List<String> {
        if (obj.isEmpty) return emptyList()

        val result = mutableListOf<String>()
        val heading = buildObjectHeading(headingPath)

        // Accumulator: the heading line plus rendered key-value lines
        val accumulatorLines = mutableListOf<String>()

        fun currentAccumulatorText(): String {
            return if (accumulatorLines.isEmpty()) {
                heading
            } else {
                "$heading\n\n${accumulatorLines.joinToString("\n\n")}"
            }
        }

        fun flush() {
            if (accumulatorLines.isNotEmpty()) {
                result.add(currentAccumulatorText())
                accumulatorLines.clear()
            }
        }

        for ((key, value) in obj.fields().asSequence().map { it.key to it.value }) {
            when {
                value is ObjectNode && !fitsInBudget(prettyWriter.writeValueAsString(value)) -> {
                    // Large nested object: flush accumulator, then recurse with extended heading path
                    flush()
                    result.addAll(chunk(value, headingPath + key))
                }
                value is ArrayNode && !fitsInBudget(prettyWriter.writeValueAsString(value)) -> {
                    // Large nested array: flush accumulator, then recurse with extended heading path
                    flush()
                    result.addAll(chunk(value, headingPath + key))
                }
                value.isTextual && !fitsInBudget(value.textValue()) -> {
                    // Long string value: flush accumulator, then split with TokenTextSplitter
                    flush()
                    result.addAll(splitLongString(value.textValue(), headingPath + key))
                }
                else -> {
                    val candidateLine = renderKeyValue(key, value)

                    // Calculate what the chunk would look like with this line added
                    val textWithLine = if (accumulatorLines.isEmpty()) {
                        "$heading\n\n$candidateLine"
                    } else {
                        "$heading\n\n${accumulatorLines.joinToString("\n\n")}\n\n$candidateLine"
                    }

                    when {
                        tokenCounter(textWithLine) <= chunkSize -> {
                            // Fits in budget — accumulate
                            accumulatorLines.add(candidateLine)
                        }
                        accumulatorLines.isEmpty() -> {
                            // Even a single field doesn't fit — emit it alone
                            result.add("$heading\n\n$candidateLine")
                        }
                        else -> {
                            // Would exceed budget — flush current accumulator and start fresh
                            flush()
                            val freshText = "$heading\n\n$candidateLine"
                            if (tokenCounter(freshText) <= chunkSize) {
                                accumulatorLines.add(candidateLine)
                            } else {
                                // Still too large alone — emit as its own chunk
                                result.add(freshText)
                            }
                        }
                    }
                }
            }
        }

        flush()
        return result
    }

    /**
     * Renders a single key-value pair as a Markdown line.
     * - Primitives and short strings: `**key**: value`
     * - Small nested objects/arrays: `**key**:\n\`\`\`json\n...\n\`\`\``
     * - Large nested objects/arrays: handled by the caller (recursive dispatch)
     */
    private fun renderKeyValue(key: String, value: JsonNode): String {
        return when {
            value.isObject || value.isArray -> {
                val pretty = prettyWriter.writeValueAsString(value)
                "**$key**:\n```json\n$pretty\n```"
            }
            value.isTextual -> "**$key**: ${value.textValue()}"
            value.isNull -> "**$key**: null"
            value.isBoolean -> "**$key**: ${value.booleanValue()}"
            value.isNumber -> "**$key**: ${value.numberValue()}"
            else -> "**$key**: ${value.asText()}"
        }
    }

    /**
     * Splits a long string value across multiple chunks using [TokenTextSplitter].
     * Each resulting chunk is prefixed with a heading derived from [headingPath].
     */
    private fun splitLongString(text: String, headingPath: List<String>): List<String> {
        val heading = buildObjectHeading(headingPath)
        val doc = Document.builder().text(text).build()
        val splitter = TokenTextSplitter.builder()
            .withChunkSize(chunkSize)
            .withMinChunkSizeChars(chunkOverlap)
            .withMinChunkLengthToEmbed(1)
            .withMaxNumChunks(10000)
            .withKeepSeparator(true)
            .build()
        return splitter.apply(listOf(doc)).map { piece ->
            "$heading\n\n${piece.text ?: ""}"
        }
    }

    private fun fitsInBudget(text: String): Boolean = tokenCounter(text) <= chunkSize

    private fun buildObjectHeading(headingPath: List<String>): String {
        return if (headingPath.isEmpty()) {
            "##"
        } else {
            "## ${headingPath.joinToString(" → ")}"
        }
    }
}
