package ch.obermuhlner.ezrag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine

class EzRagCommandTest {

    @Test
    fun `help exits 0`() {
        val commandLine = CommandLine(EzRagCommand())
        val exitCode = commandLine.execute("--help")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `unknown subcommand exits non-zero`() {
        val commandLine = CommandLine(EzRagCommand())
        val exitCode = commandLine.execute("unknown-subcommand")
        assertThat(exitCode).isNotEqualTo(0)
    }

    // -----------------------------------------------------------------------
    // preParseProviderFlags
    // -----------------------------------------------------------------------

    @Test
    fun `preParseProviderFlags captures --rerank-model`() {
        val result = preParseProviderFlags(
            arrayOf("search", "--rerank-model", "cross-encoder/ms-marco-MiniLM-L-6-v2", "Deimos")
        )
        assertThat(result["ez.rag.rerankModel"]).isEqualTo("cross-encoder/ms-marco-MiniLM-L-6-v2")
    }

    @Test
    fun `preParseProviderFlags captures --rerank-candidates`() {
        val result = preParseProviderFlags(
            arrayOf("search", "--rerank-candidates", "20", "Deimos")
        )
        assertThat(result["ez.rag.rerankCandidates"]).isEqualTo("20")
    }

    // -----------------------------------------------------------------------
    // verbose propagation via @ParentCommand
    // -----------------------------------------------------------------------

    @Test
    fun `--verbose propagates to SearchCommand via parent`() {
        val ezRag = EzRagCommand()
        val commandLine = CommandLine(ezRag)
        val searchSubCmd = commandLine.getSubcommands()["search"]
        val searchCommand = searchSubCmd?.commandSpec?.userObject() as? ch.obermuhlner.ezrag.command.SearchCommand

        // Execute — will fail (no store) but picocli still parses and sets parent
        commandLine.execute("search", "--verbose", "test")

        // The parent's verbose field must be true
        assertThat(ezRag.verbose).isTrue()
        // SearchCommand must expose parent.verbose = true via @ParentCommand
        val parentField = ch.obermuhlner.ezrag.command.SearchCommand::class.java
            .getDeclaredField("parent")
        parentField.isAccessible = true
        val parent = parentField.get(searchCommand) as? EzRagCommand
        assertThat(parent?.verbose).isTrue()
    }
}
