package ch.obermuhlner.ezrag.beir

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BeirDatasetRegistryTest {

    private val registry = BeirDatasetRegistry()

    @Test
    fun `known name nfcorpus resolves to non-null metadata`() {
        assertThat(registry.lookup("nfcorpus")).isNotNull()
    }

    @Test
    fun `known name scifact resolves to non-null metadata`() {
        assertThat(registry.lookup("scifact")).isNotNull()
    }

    @Test
    fun `unknown name returns null`() {
        assertThat(registry.lookup("not-a-real-dataset")).isNull()
    }

    @Test
    fun `allDatasets contains at least 8 entries`() {
        assertThat(registry.allDatasets()).hasSizeGreaterThanOrEqualTo(8)
    }

    @Test
    fun `allDatasets contains nfcorpus and scifact`() {
        val names = registry.allDatasets().map { it.name }
        assertThat(names).contains("nfcorpus", "scifact")
    }

    @Test
    fun `allDatasets entries have non-empty domain descriptions`() {
        registry.allDatasets().forEach { info ->
            assertThat(info.domain).isNotBlank()
        }
    }

    @Test
    fun `allDatasets entries have positive approximate doc and query counts`() {
        registry.allDatasets().forEach { info ->
            assertThat(info.approxDocCount).isGreaterThan(0)
            assertThat(info.approxQueryCount).isGreaterThan(0)
        }
    }
}
