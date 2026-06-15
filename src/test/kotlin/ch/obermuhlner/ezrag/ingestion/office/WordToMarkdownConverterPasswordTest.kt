package ch.obermuhlner.ezrag.ingestion.office

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class WordToMarkdownConverterPasswordTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun createFixtures() {
            EncryptedWordFixtureGenerator.createEncryptedDocxFixture(
                EncryptedWordFixtureGenerator.encryptedDocxFile
            )
        }
    }

    @Test
    fun `correct password opens encrypted docx and returns non-empty Markdown`() {
        val converter = WordToMarkdownConverter()
        val result = converter.convert(
            EncryptedWordFixtureGenerator.encryptedDocxFile,
            passwords = listOf(EncryptedWordFixtureGenerator.CORRECT_PASSWORD)
        )

        assertThat(result).isNotBlank()
        assertThat(result).contains("SecretContent")
    }

    @Test
    fun `wrong password throws an exception`() {
        val converter = WordToMarkdownConverter()

        assertThatThrownBy {
            converter.convert(
                EncryptedWordFixtureGenerator.encryptedDocxFile,
                passwords = listOf("wrong-password")
            )
        }.isInstanceOf(Exception::class.java)
    }

    @Test
    fun `passwords list with wrong then correct password succeeds`() {
        val converter = WordToMarkdownConverter()
        val result = converter.convert(
            EncryptedWordFixtureGenerator.encryptedDocxFile,
            passwords = listOf("wrong-password", EncryptedWordFixtureGenerator.CORRECT_PASSWORD)
        )

        assertThat(result).isNotBlank()
        assertThat(result).contains("SecretContent")
    }

    @Test
    fun `empty passwords list throws an exception`() {
        val converter = WordToMarkdownConverter()

        assertThatThrownBy {
            converter.convert(
                EncryptedWordFixtureGenerator.encryptedDocxFile,
                passwords = emptyList()
            )
        }.isInstanceOf(Exception::class.java)
    }
}
