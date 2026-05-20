package ch.obermuhlner.ezrag.beir

class BeirDatasetRegistry {

    private val datasets: List<BeirDatasetInfo> = listOf(
        BeirDatasetInfo("nfcorpus",          "Biomedical (clinical/nutritional)",      3_633,     323),
        BeirDatasetInfo("scifact",            "Scientific fact-checking",               5_183,     300),
        BeirDatasetInfo("fiqa",               "Financial QA",                          57_638,     648),
        BeirDatasetInfo("arguana",            "Counter-argument retrieval",             8_674,   1_406),
        BeirDatasetInfo("trec-covid",         "Biomedical COVID-19 research",         171_332,      50),
        BeirDatasetInfo("webis-touche2020",   "Argument retrieval (debate)",          382_545,      49),
        BeirDatasetInfo("dbpedia-entity",     "Entity retrieval (Wikipedia)",       4_635_922,     400),
        BeirDatasetInfo("scidocs",            "Scientific literature retrieval",       25_657,   1_000),
        BeirDatasetInfo("fever",              "Fact verification (Wikipedia)",      5_416_568,   6_666),
        BeirDatasetInfo("hotpotqa",           "Multi-hop QA (Wikipedia)",           5_233_329,   7_405),
    )

    private val byName: Map<String, BeirDatasetInfo> = datasets.associateBy { it.name }

    fun lookup(name: String): BeirDatasetInfo? = byName[name]

    fun allDatasets(): List<BeirDatasetInfo> = datasets
}
