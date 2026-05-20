package ch.obermuhlner.ezrag.beir

data class ConversionConfig(
    val maxQuestions: Int = 50,
    val maxDistractors: Int = 20,
    val randomSeed: Long = 42L,
    val recallThreshold: Double? = null,
    val hitThreshold: Double? = null
)
