package ch.obermuhlner.ezrag.beir

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path

class BeirCorpusReader {

    private val mapper = ObjectMapper()

    fun readCorpus(dir: Path, split: String = "test"): BeirCorpusData {
        val documents = parseCorpus(dir.resolve("corpus.jsonl").toFile().readLines())
        val queries = parseQueries(dir.resolve("queries.jsonl").toFile().readLines())
        val qrelsFile = dir.resolve("qrels/$split.tsv").toFile()
        val qrels = if (qrelsFile.exists()) parseQrels(qrelsFile.readLines()) else emptyMap()
        return BeirCorpusData(documents, queries, qrels)
    }

    private fun parseCorpus(lines: List<String>): List<BeirDocument> =
        lines.filter { it.isNotBlank() }.map { line ->
            val node = mapper.readTree(line)
            BeirDocument(
                id = node["_id"].asText(),
                title = node["title"]?.asText() ?: "",
                text = node["text"]?.asText() ?: ""
            )
        }

    private fun parseQueries(lines: List<String>): Map<String, String> =
        lines.filter { it.isNotBlank() }.associate { line ->
            val node = mapper.readTree(line)
            node["_id"].asText() to (node["text"]?.asText() ?: "")
        }

    private fun parseQrels(lines: List<String>): Map<String, Map<String, Int>> {
        val result = mutableMapOf<String, MutableMap<String, Int>>()
        for (line in lines.filter { it.isNotBlank() }) {
            val parts = line.split("\t")
            if (parts.size < 3) continue
            val scoreStr = parts[2].trim()
            val score = scoreStr.toIntOrNull() ?: continue  // skip header row
            if (score < 1) continue
            val queryId = parts[0].trim()
            val docId = parts[1].trim()
            result.getOrPut(queryId) { mutableMapOf() }[docId] = score
        }
        return result
    }
}
