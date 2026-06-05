package ch.obermuhlner.ezrag.ingestion.pdf

/**
 * Controls which conversion rules are applied and how blocks are rendered.
 *
 * Use the named presets [READABLE] and [RAG] for common configurations,
 * or construct a custom instance by overriding individual fields.
 */
data class ConversionOptions(
    /**
     * Strip inline bold/italic asterisks from paragraph text.
     * Structural formatting (headings, fenced code, tables, lists) is always kept.
     * Useful for RAG pipelines where `**word**` and `*word*` are embedding noise.
     */
    val stripInlineFormatting: Boolean = false,

    /**
     * Include Table of Contents entries in the output.
     * ToC entries are low-value in a RAG corpus — they duplicate section titles without content.
     */
    val includeToc: Boolean = true,

    /** How epigraph / block-quote blocks are rendered. */
    val epigraphFormat: EpigraphFormat = EpigraphFormat.BLOCKQUOTE,

    /** How advisory callouts (Note:, Warning:, …) are rendered. */
    val advisoryFormat: AdvisoryFormat = AdvisoryFormat.BLOCKQUOTE,

    /**
     * When true, a cell whose [TextElement.endX] reaches the start of the next column
     * AND whose neighbour column slots are empty is treated as a colspan candidate:
     * the cell text is repeated into those empty slots.
     * Reduces silent data loss in RAG pipelines at the cost of possible text duplication
     * when a normal cell happens to be wide with an empty neighbour.
     */
    val normalizeTableSpans: Boolean = false,

    /**
     * Fine-tuning parameters for conversion rules.
     * Override individual values for specialized document types.
     */
    val ruleTuning: RuleTuning = RuleTuning(),
) {
    enum class EpigraphFormat {
        /** Render as Markdown block quote: `> text`. */
        BLOCKQUOTE,
        /** Render as plain paragraph text, no `>` prefix. */
        PLAIN,
    }

    enum class AdvisoryFormat {
        /** Render as Markdown block quote: `> **Note:** text`. */
        BLOCKQUOTE,
        /** Render as plain text: `Note: text`. */
        PLAIN,
    }

    companion object {
        /** Optimised for human-readable Markdown (default). */
        val READABLE = ConversionOptions()

        /**
         * Optimised for RAG pipelines: structure preserved, inline formatting noise removed,
         * ToC and blockquote wrappers stripped.
         */
        val RAG = ConversionOptions(
            stripInlineFormatting = true,
            includeToc = false,
            epigraphFormat = EpigraphFormat.PLAIN,
            advisoryFormat = AdvisoryFormat.PLAIN,
            normalizeTableSpans = true,
        )
    }
}

/**
 * Fine-tuning parameters for conversion rules.
 *
 * These values control the heuristics used to detect and merge text elements
 * into Markdown blocks. Override individual values to optimize for specific
 * document types (e.g., dense academic papers, loose business reports).
 *
 * All numeric values have sensible defaults that work well for typical documents.
 */
data class RuleTuning(
    // ─── Heading detection ────────────────────────────────────────────────────

    val titleMinRatio: Double = 1.10,
    val headingMediumMinRatio: Double = 1.05,

    // ─── Paragraph joining ────────────────────────────────────────────────────

    val paragraphMaxYGapMultiplier: Double = 2.0,
    val paragraphMaxXDistance: Int = 150,

    // ─── Heading merge ────────────────────────────────────────────────────────

    val headingMergeMaxYGapMultiplier: Double = 3.0,
    val headingMergeMaxFontSizeDiff: Int = 1,

    // ─── List detection ─────────────────────────────────────────────────────

    val listMinIndent: Int = 20,
    val listMaxIndent: Int = 100,
    val listYGapMultiplier: Double = 3.0,
    val listXVariance: Int = 5,
    val bulletListYGapMultiplier: Double = 4.0,
    val listContinuationYGapMultiplier: Double = 2.0,

    // ─── Epigraph detection ──────────────────────────────────────────────────

    val epigraphYGapMultiplier: Double = 3.0,
    val epigraphAttributionYGapMultiplier: Double = 4.0,
    val epigraphAttributionMaxLength: Int = 70,

    // ─── Table detection ─────────────────────────────────────────────────────

    val tableMinColumnGap: Int = 25,
    val tableMinRows: Int = 1,
    val tableRowTolerance: Int = 12,
    val columnDetectionMinElements: Int = 6,
    val columnHistogramBuckets: Int = 50,
    val columnMarginFraction: Double = 0.10,
    val columnMinGapBuckets: Int = 5,
    val columnMinSizeFraction: Double = 0.15,
    val columnMinSizeAbsolute: Int = 4,
    val spanningMinWidthFraction: Double = 0.70,
    val columnCoherenceXDistance: Int = 30,
    val columnCoherenceMinFraction: Double = 0.50,

    // ─── Drop-initial detection ───────────────────────────────────────────────

    val dropInitialMinRows: Int = 2,
    val dropInitialMaxLength: Int = 3,
    val dropInitialYDistanceFraction: Double = 0.5,
    val dropInitialXDistanceMultiplier: Double = 3.0,

    // ─── Code block detection ─────────────────────────────────────────────────

    val codeYGapMultiplier: Double = 2.0,
    val tableDividerMaxYGapMultiplier: Double = 3.0,

    // ─── Pattern-based detection ───────────────────────────────────────────────

    val bulletPrefixChars: String = "•■*□·-→",
    val advisoryLabels: String = "Note|Warning|Tip|Important|Caution|Remark",
)
