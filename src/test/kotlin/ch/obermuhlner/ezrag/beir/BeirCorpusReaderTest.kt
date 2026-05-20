package ch.obermuhlner.ezrag.beir

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class BeirCorpusReaderTest {

    @Test
    fun `corpus jsonl is parsed into BeirDocument list with correct id title and text`(@TempDir dir: Path) {
        dir.resolve("corpus.jsonl").toFile().writeText(
            """{"_id": "doc1", "title": "Title One", "text": "Body text one", "metadata": {}}
{"_id": "doc2", "title": "Title Two", "text": "Body text two", "metadata": {}}
"""
        )
        dir.resolve("queries.jsonl").toFile().writeText("")
        dir.resolve("qrels").toFile().mkdir()
        dir.resolve("qrels/test.tsv").toFile().writeText("")

        val reader = BeirCorpusReader()
        val result = reader.readCorpus(dir)

        assertThat(result.documents).hasSize(2)
        assertThat(result.documents[0].id).isEqualTo("doc1")
        assertThat(result.documents[0].title).isEqualTo("Title One")
        assertThat(result.documents[0].text).isEqualTo("Body text one")
        assertThat(result.documents[1].id).isEqualTo("doc2")
    }

    @Test
    fun `queries jsonl is parsed into map of queryId to queryText`(@TempDir dir: Path) {
        dir.resolve("corpus.jsonl").toFile().writeText("")
        dir.resolve("queries.jsonl").toFile().writeText(
            """{"_id": "q1", "text": "Query one", "metadata": {}}
{"_id": "q2", "text": "Query two", "metadata": {}}
"""
        )
        dir.resolve("qrels").toFile().mkdir()
        dir.resolve("qrels/test.tsv").toFile().writeText("")

        val reader = BeirCorpusReader()
        val result = reader.readCorpus(dir)

        assertThat(result.queries).hasSize(2)
        assertThat(result.queries["q1"]).isEqualTo("Query one")
        assertThat(result.queries["q2"]).isEqualTo("Query two")
    }

    @Test
    fun `qrels tsv with header is parsed into map of queryId to docId to score`(@TempDir dir: Path) {
        dir.resolve("corpus.jsonl").toFile().writeText("")
        dir.resolve("queries.jsonl").toFile().writeText("")
        dir.resolve("qrels").toFile().mkdir()
        dir.resolve("qrels/test.tsv").toFile().writeText(
            "query-id\tcorpus-id\tscore\n" +
            "q1\tdoc1\t1\n" +
            "q1\tdoc2\t2\n" +
            "q2\tdoc3\t1\n"
        )

        val reader = BeirCorpusReader()
        val result = reader.readCorpus(dir, split = "test")

        assertThat(result.qrels["q1"]).containsEntry("doc1", 1)
        assertThat(result.qrels["q1"]).containsEntry("doc2", 2)
        assertThat(result.qrels["q2"]).containsEntry("doc3", 1)
    }

    @Test
    fun `qrels tsv without header is parsed identically to one with header`(@TempDir dir: Path) {
        dir.resolve("corpus.jsonl").toFile().writeText("")
        dir.resolve("queries.jsonl").toFile().writeText("")
        dir.resolve("qrels").toFile().mkdir()
        dir.resolve("qrels/test.tsv").toFile().writeText(
            "q1\tdoc1\t1\n" +
            "q2\tdoc3\t1\n"
        )

        val reader = BeirCorpusReader()
        val result = reader.readCorpus(dir, split = "test")

        assertThat(result.qrels["q1"]).containsEntry("doc1", 1)
        assertThat(result.qrels["q2"]).containsEntry("doc3", 1)
    }

    @Test
    fun `qrels entries with score below 1 are excluded`(@TempDir dir: Path) {
        dir.resolve("corpus.jsonl").toFile().writeText("")
        dir.resolve("queries.jsonl").toFile().writeText("")
        dir.resolve("qrels").toFile().mkdir()
        dir.resolve("qrels/test.tsv").toFile().writeText(
            "query-id\tcorpus-id\tscore\n" +
            "q1\tdoc1\t0\n" +
            "q1\tdoc2\t1\n"
        )

        val reader = BeirCorpusReader()
        val result = reader.readCorpus(dir, split = "test")

        assertThat(result.qrels["q1"]).doesNotContainKey("doc1")
        assertThat(result.qrels["q1"]).containsEntry("doc2", 1)
    }

    @Test
    fun `empty corpus jsonl produces empty document list`(@TempDir dir: Path) {
        dir.resolve("corpus.jsonl").toFile().writeText("")
        dir.resolve("queries.jsonl").toFile().writeText("")
        dir.resolve("qrels").toFile().mkdir()
        dir.resolve("qrels/test.tsv").toFile().writeText("")

        val reader = BeirCorpusReader()
        val result = reader.readCorpus(dir)

        assertThat(result.documents).isEmpty()
        assertThat(result.queries).isEmpty()
        assertThat(result.qrels).isEmpty()
    }
}
