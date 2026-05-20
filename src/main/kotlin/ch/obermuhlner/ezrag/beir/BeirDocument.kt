package ch.obermuhlner.ezrag.beir

data class BeirDocument(
    val id: String,
    val title: String,
    val text: String
)

data class BeirCorpusData(
    val documents: List<BeirDocument>,
    val queries: Map<String, String>,
    val qrels: Map<String, Map<String, Int>>
)
