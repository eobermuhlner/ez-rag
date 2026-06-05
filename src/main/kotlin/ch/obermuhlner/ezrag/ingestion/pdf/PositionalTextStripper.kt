package ch.obermuhlner.ezrag.ingestion.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDSimpleFont
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.StringWriter
import kotlin.math.abs
import kotlin.math.roundToInt

class PositionalTextStripper(private val rawMode: Boolean = false) : PDFTextStripper() {

    companion object {
        val TEX_MATH_GLYPH_MAP: Map<String, String> = buildMap {
            put("lessmuch",        "≪")
            put("greatermuch",     "≫")
            put("negationslash",   "")
            put("element",         "∈")
            put("owner",           "∋")
            put("propersubset",    "⊂")
            put("propersuperset",  "⊃")
            put("reflexsubset",    "⊆")
            put("reflexsuperset",  "⊇")
            put("union",           "∪")
            put("intersection",    "∩")
            put("unionmulti",      "⊎")
            put("logicaland",      "∧")
            put("logicalor",       "∨")
            put("arrowleft",       "←")
            put("arrowright",      "→")
            put("arrowup",         "↑")
            put("arrowdown",       "↓")
            put("arrowboth",       "↔")
            put("arrownortheast",  "↗")
            put("arrowsoutheast",  "↘")
            put("arrownorthwest",  "↖")
            put("arrowsouthwest",  "↙")
            put("arrowdblleft",    "⇐")
            put("arrowdblright",   "⇒")
            put("arrowdblup",      "⇑")
            put("arrowdbldown",    "⇓")
            put("arrowdblboth",    "⇔")
            put("mapsto",          "↦")
            put("universal",       "∀")
            put("existential",     "∃")
            put("emptyset",        "∅")
            put("infinity",        "∞")
            put("proportional",    "∝")
            put("prime",           "′")
            put("integral",        "∫")
            put("logicalnot",      "¬")
            put("perpendicular",   "⊥")
            put("latticetop",      "⊤")
            put("aleph",           "ℵ")
            put("Rfractur",        "ℜ")
            put("Ifractur",        "ℑ")
            put("circleplus",      "⊕")
            put("circleminus",     "⊖")
            put("circlemultiply",  "⊗")
            put("circledivide",    "⊘")
            put("circledot",       "⊙")
            put("plusminus",       "±")
            put("minusplus",       "∓")
            put("similar",         "∼")
            put("approxequal",     "≈")
            put("equivalence",     "≡")
            put("lessequal",       "≤")
            put("greaterequal",    "≥")
            put("precedesequal",   "⪯")
            put("followsequal",    "⪰")
            put("precedes",        "≺")
            put("follows",         "≻")
            put("equivasymptotic", "≍")
            put("similarequal",    "≃")
            put("turnstileleft",   "⊢")
            put("turnstileright",  "⊣")
            put("floorleft",       "⌊")
            put("floorright",      "⌋")
            put("ceilingleft",     "⌈")
            put("ceilingright",    "⌉")
            put("angbracketleft",  "⟨")
            put("angbracketright", "⟩")
            put("bardbl",          "‖")
            put("triangle",        "△")
            put("triangleinv",     "▽")
            put("bullet",          "•")
            put("openbullet",      "◦")
            put("asteriskmath",    "∗")
            put("diamondmath",     "⋄")
            put("minus",           "−")
            put("lscript",         "ℓ")
            put("vector",          "")
            put("partial",         "∂")
            put("nabla",           "∇")
            put("square",          "□")
            put("blacksquare",     "■")
            put("checkmark",       "✓")
            put("circledR",        "®")
            put("suppress",        "")
            put("visiblespace",    "␣")
        }
    }
    private val elements = mutableListOf<TextElement>()

    fun extractElements(doc: PDDocument): List<TextElement> {
        elements.clear()
        writeText(doc, StringWriter())
        return elements.toList()
    }

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        if (text.isBlank()) return
        val first = textPositions.firstOrNull() ?: return
        val fontSize = first.fontSizeInPt.roundToInt()
        val height = textPositions.maxOf { it.heightDir }.roundToInt()
        val font = normalizeFontStyle(first.font)
        val fontWeight = first.font.fontDescriptor?.fontWeight?.toInt() ?: 400
        val y = first.yDirAdj.roundToInt()

        val splitThreshold = if (fontSize > 0) fontSize * 3.0 else height * 4.0
        val splitPoints: List<Int> = if (text.length == textPositions.size) {
            (0 until textPositions.size - 1).filter { i ->
                textPositions[i + 1].xDirAdj - (textPositions[i].xDirAdj + textPositions[i].width) > splitThreshold
            }
        } else emptyList()

        val rawFont = if (rawMode) rawFontInfo(first.font) else null

        if (splitPoints.isEmpty()) {
            val x = first.xDirAdj.roundToInt()
            val last = textPositions.last()
            val endX = (last.xDirAdj + last.width).roundToInt()
            elements.add(
                TextElement(
                    x, y, endX, height, fontSize, font,
                    normalizeText(remapFallbackGlyphs(text, textPositions)),
                    fontWeight = fontWeight,
                    rawFont = rawFont,
                )
            )
        } else {
            val boundaries = listOf(-1) + splitPoints + listOf(textPositions.size - 1)
            for (b in 0 until boundaries.size - 1) {
                val start = boundaries[b] + 1
                val end = boundaries[b + 1]
                val segText = text.substring(start, end + 1)
                if (segText.isBlank()) continue
                val segPositions = textPositions.subList(start, end + 1)
                val segFirst = segPositions.first()
                val segLast = segPositions.last()
                val segX = segFirst.xDirAdj.roundToInt()
                val segEndX = (segLast.xDirAdj + segLast.width).roundToInt()
                elements.add(
                    TextElement(
                        segX, y, segEndX, height, fontSize, font,
                        normalizeText(remapFallbackGlyphs(segText, segPositions)),
                        fontWeight = fontWeight,
                        rawFont = rawFont,
                    )
                )
            }
        }
    }

    private fun remapFallbackGlyphs(text: String, textPositions: List<TextPosition>): String {
        if (!text.contains('?')) return text
        if (text.length != textPositions.size) return text
        return buildString {
            for (i in text.indices) {
                val ch = text[i]
                val tp = textPositions[i]
                val codes = tp.characterCodes
                val isRealQuestion = ch != '?' || (codes.isNotEmpty() && codes[0] == 0x3F)
                if (isRealQuestion) {
                    append(ch)
                } else {
                    val glyphName = getGlyphName(tp)
                    when {
                        glyphName != null && glyphName in TEX_MATH_GLYPH_MAP ->
                            append(TEX_MATH_GLYPH_MAP[glyphName])
                        else -> append('□')
                    }
                }
            }
        }
    }

    private fun getGlyphName(tp: TextPosition): String? {
        val font = tp.font
        if (font is PDSimpleFont) {
            val codes = tp.characterCodes
            if (codes.isNotEmpty()) {
                return try { font.encoding?.getName(codes[0]) } catch (_: Exception) { null }
            }
        }
        return null
    }
}

fun mergeElements(elements: List<TextElement>): List<TextElement> {
    if (elements.isEmpty()) return elements
    val sorted = elements.sortedWith(compareBy({ it.y }, { it.x }))
    val result = mutableListOf<TextElement>()
    var current = sorted[0]
    for (i in 1 until sorted.size) {
        val next = sorted[i]
        if (canMerge(current, next)) {
            val separator = if (next.x - current.endX > 1) " " else ""
            current = current.copy(
                endX = next.endX,
                height = maxOf(current.height, next.height),
                text = current.text + separator + next.text,
            )
        } else {
            result.add(current.copy(text = normalizeSpreadText(current.text)))
            current = next
        }
    }
    result.add(current.copy(text = normalizeSpreadText(current.text)))
    return result
}

fun canMerge(a: TextElement, b: TextElement): Boolean {
    val yTolerance = maxOf(2, a.fontSize / 4)
    val maxGap = if (a.fontSize > 0) a.fontSize.toDouble() * 1.0 else a.height.toDouble() * 2.0
    return abs(a.y - b.y) <= yTolerance &&
            a.font == b.font &&
            abs(a.fontWeight - b.fontWeight) <= 50 &&
            abs(a.fontSize - b.fontSize) <= 1 &&
            b.x >= a.x &&
            (b.x - a.endX) < maxGap
}

fun normalizeSpreadText(text: String): String {
    if (text.length < 3) return text
    val parts = text.split(Regex(" {2,}")).mapNotNull { part ->
        val trimmed = part.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val tokens = trimmed.split(' ').filter { it.isNotEmpty() }
        when {
            tokens.size >= 3 && tokens.all { it.length <= 2 } -> tokens.joinToString("")
            tokens.size == 2 && tokens.all { it.length == 1 } -> tokens.joinToString("")
            else -> collapseLetterRuns(tokens)
        }
    }
    if (parts.isEmpty()) return text.trim()
    return parts.joinToString(" ")
}

fun rawFontInfo(font: PDFont): String {
    val fullName = font.name ?: ""
    val descriptor = font.fontDescriptor
    val fw = descriptor?.fontWeight?.toString() ?: "?"
    val fb = descriptor?.isForceBold?.toString() ?: "?"
    val fi = descriptor?.isItalic?.toString() ?: "?"
    return "$fullName|fw=$fw|fb=$fb|fi=$fi"
}

fun normalizeFontStyle(font: PDFont): String {
    val descriptor = font.fontDescriptor
    val boldByDescriptor = descriptor != null && (descriptor.isForceBold || descriptor.fontWeight >= 700f)
    val italicByDescriptor = descriptor != null && descriptor.isItalic

    val name = (font.name ?: "").substringAfter('+').uppercase()
    val boldByName = name.contains("BOLD") || name.contains("HEAVY") || name.contains("BLACK") ||
            name.contains("DEMI") || name.endsWith("TB") || name.endsWith("-BD") || name.endsWith("BD")
    val italicByName = name.contains("ITALIC") || name.contains("OBLIQUE") || name.contains("SLANTED") ||
            name.endsWith("TI") || name.endsWith("-IT") || name.endsWith("IT")
    val monoByName = name.contains("MONO") || name.contains("COURIER") || name.contains("CODE") ||
            name.contains("TYPEWRITER") || name.contains("FIXED") || name.contains("CONSOL") ||
            name.contains("INCONSOLATA") || name.contains("TERMINAL") || name.contains("TELETYPE")

    val bold = boldByDescriptor || boldByName
    val italic = italicByDescriptor || italicByName

    val base = when {
        bold && italic -> "bold-italic"
        bold -> "bold"
        italic -> "italic"
        else -> "normal"
    }
    return if (monoByName) "$base-mono" else base
}

private fun collapseLetterRuns(tokens: List<String>): String {
    val sb = StringBuilder()
    var i = 0
    while (i < tokens.size) {
        val t = tokens[i]
        if (t.length == 1 && t[0].isLetter()) {
            var j = i
            while (j < tokens.size && isSpreadFragment(tokens[j])) j++
            val run = tokens.subList(i, j)
            if (sb.isNotEmpty()) sb.append(' ')
            if (run.size >= 2) sb.append(run.joinToString(""))
            else sb.append(t)
            i = j
        } else {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(t)
            i++
        }
    }
    return sb.toString()
}

private fun isSpreadFragment(t: String): Boolean = when (t.length) {
    1    -> t[0].isLetter()
    2    -> t.any { it.isLetter() }
    3, 4 -> t.all { it.isUpperCase() }
    else -> false
}

fun normalizeText(text: String): String = text
    .replace(' ', ' ')
    .replace(' ', ' ')
    .replace('‑', '-')
    .replace('', '•')
    .replace("­", "")
    .replace("⁠", "")
    .replace("﻿", "")
    .replace(Regex("[-]"), "")
