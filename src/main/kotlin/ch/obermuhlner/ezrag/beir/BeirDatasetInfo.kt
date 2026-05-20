package ch.obermuhlner.ezrag.beir

data class BeirDatasetInfo(
    val name: String,
    val domain: String,
    val approxDocCount: Int,
    val approxQueryCount: Int
)
