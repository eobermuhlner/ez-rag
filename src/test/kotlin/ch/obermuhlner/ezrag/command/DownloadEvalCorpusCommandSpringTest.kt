package ch.obermuhlner.ezrag.command

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.test.context.junit.jupiter.SpringExtension

@ExtendWith(SpringExtension::class)
@Import(DownloadEvalCorpusCommand::class)
class DownloadEvalCorpusCommandSpringTest {

    @Autowired
    private lateinit var downloadEvalCorpusCommand: DownloadEvalCorpusCommand

    @Test
    fun `DownloadEvalCorpusCommand bean is present in the Spring application context`() {
        assertThat(downloadEvalCorpusCommand).isNotNull()
    }
}
