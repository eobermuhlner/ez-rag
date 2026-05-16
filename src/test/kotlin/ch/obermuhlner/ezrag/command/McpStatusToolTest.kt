package ch.obermuhlner.ezrag.command

import ch.obermuhlner.ezrag.ingestion.StoreDocumentInfo
import ch.obermuhlner.ezrag.ingestion.StoreMetadata
import ch.obermuhlner.ezrag.ingestion.VectorStoreRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class McpStatusToolTest {

    @Test
    fun `status returns StoreStatus mapped from VectorStoreRepository getMetadata`() {
        val mockRepository = mock(VectorStoreRepository::class.java)
        val metadata = StoreMetadata(
            storeFilePath = "/path/to/store.json",
            chunkCount = 42,
            documents = listOf(
                StoreDocumentInfo(path = "doc1.txt", chunkCount = 20),
                StoreDocumentInfo(path = "doc2.txt", chunkCount = 22)
            )
        )
        `when`(mockRepository.getMetadata()).thenReturn(metadata)

        val tool = McpStatusTool(mockRepository)
        val result = tool.status()

        assertThat(result.storeFilePath).isEqualTo("/path/to/store.json")
        assertThat(result.chunkCount).isEqualTo(42)
        assertThat(result.documents).hasSize(2)
        assertThat(result.documents[0].path).isEqualTo("doc1.txt")
        assertThat(result.documents[0].chunkCount).isEqualTo(20)
        assertThat(result.documents[1].path).isEqualTo("doc2.txt")
        assertThat(result.documents[1].chunkCount).isEqualTo(22)
        assertThat(result.error).isNull()
    }

    @Test
    fun `status returns StoreStatus with error field when VectorStoreRepository throws exception`() {
        val mockRepository = mock(VectorStoreRepository::class.java)
        `when`(mockRepository.getMetadata()).thenThrow(RuntimeException("Store not loaded"))

        val tool = McpStatusTool(mockRepository)
        val result = tool.status()

        assertThat(result.error).isNotNull()
        assertThat(result.error).contains("Store not loaded")
    }
}
