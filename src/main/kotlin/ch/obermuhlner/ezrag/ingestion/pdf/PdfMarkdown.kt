package ch.obermuhlner.ezrag.ingestion.pdf

import org.apache.pdfbox.Loader
import java.io.File

object PdfMarkdown {

    fun toMarkdown(
        file: File,
        maxPages: Int = Int.MAX_VALUE,
        options: ConversionOptions = ConversionOptions.READABLE,
    ): String {
        val (pageElements, modeFontSize) = extractFilteredPageElements(file, maxPages)
        return DeterministicMarkdownConverter.convertDocument(pageElements, modeFontSize, options)
            .joinToString("\n\n")
    }
}

internal fun extractFilteredPageElements(
    file: File,
    maxPages: Int = Int.MAX_VALUE,
): Pair<List<List<TextElement>>, Int> {
    val result = mutableListOf<List<TextElement>>()
    var docModeFontSize = 12

    Loader.loadPDF(file).use { doc ->
        val stripper = PositionalTextStripper()
        val pagesToProcess = minOf(doc.numberOfPages, maxPages)

        val allPageElements = mutableListOf<Pair<Int, List<TextElement>>>()
        for (pageNum in 1..pagesToProcess) {
            stripper.startPage = pageNum
            stripper.endPage = pageNum
            val elements = mergeElements(stripper.extractElements(doc))
            if (elements.isNotEmpty()) allPageElements.add(pageNum to elements)
        }

        val repeatedKeys = detectRepeatedElements(
            allPageElements.map { it.second },
            minPages = maxOf(2, allPageElements.size / 3),
        )

        val allElements = allPageElements.flatMap { it.second }

        docModeFontSize = allElements
            .groupingBy { it.fontSize }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: 12

        val modalFontWeight = allElements
            .filter { it.fontWeight > 0 }
            .groupingBy { it.fontWeight }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key ?: 400

        for ((_, rawElements) in allPageElements) {
            val elements = rawElements
                .filter { el -> Triple(el.x, el.y, el.text.trim()) !in repeatedKeys }
                .map { el -> upgradeFont(el, modalFontWeight) }
            if (elements.isNotEmpty()) result.add(elements)
        }
    }

    return result to docModeFontSize
}

internal fun upgradeFont(el: TextElement, modalFontWeight: Int): TextElement {
    if (modalFontWeight <= 0 || el.fontWeight <= 0) return el
    if (el.fontWeight.toFloat() / modalFontWeight < 1.4f) return el
    val upgraded = when (el.font) {
        "normal"       -> "bold"
        "italic"       -> "bold-italic"
        "normal-mono"  -> "bold-mono"
        "italic-mono"  -> "bold-italic-mono"
        else           -> return el
    }
    return el.copy(font = upgraded)
}

internal fun detectRepeatedElements(
    pageElements: List<List<TextElement>>,
    minPages: Int,
): Set<Triple<Int, Int, String>> {
    val pageCounts = mutableMapOf<Triple<Int, Int, String>, Int>()
    for (elements in pageElements) {
        val seenOnPage = mutableSetOf<Triple<Int, Int, String>>()
        for (el in elements) {
            val key = Triple(el.x, el.y, el.text.trim())
            if (seenOnPage.add(key)) pageCounts[key] = (pageCounts[key] ?: 0) + 1
        }
    }
    return pageCounts.filterValues { it >= minPages }.keys.toSet()
}

internal fun fontSizeToCssKeyword(fontSize: Int, modeFontSize: Int): String {
    val ratio = fontSize.toDouble() / modeFontSize
    return when {
        ratio < 0.70 -> "xx-small"
        ratio < 0.82 -> "x-small"
        ratio < 0.94 -> "small"
        ratio < 1.10 -> "medium"
        ratio < 1.35 -> "large"
        ratio < 1.70 -> "x-large"
        else -> "xx-large"
    }
}
