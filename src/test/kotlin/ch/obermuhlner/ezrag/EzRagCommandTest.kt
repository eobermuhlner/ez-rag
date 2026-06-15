package ch.obermuhlner.ezrag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.nio.file.Path

class EzRagCommandTest {

    @Test
    fun `help exits 0`() {
        val commandLine = CommandLine(EzRagCommand())
        val exitCode = commandLine.execute("--help")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `--version exits 0`() {
        val commandLine = CommandLine(EzRagCommand())
        val exitCode = commandLine.execute("--version")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `-V exits 0`() {
        val commandLine = CommandLine(EzRagCommand())
        val exitCode = commandLine.execute("-V")
        assertThat(exitCode).isEqualTo(0)
    }

    @Test
    fun `--version output contains 'ez-rag ' followed by semver`() {
        val output = java.io.ByteArrayOutputStream()
        val commandLine = CommandLine(EzRagCommand())
        commandLine.out = java.io.PrintWriter(output)
        commandLine.execute("--version")
        val text = output.toString()
        assertThat(text).containsPattern("ez-rag \\d+\\.\\d+\\.\\d+")
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

    @Test
    fun `preParseProviderFlags mcp-server --transport http sets servlet and stdio false`() {
        val result = preParseProviderFlags(arrayOf("mcp-server", "--transport", "http"))
        assertThat(result["spring.ai.mcp.server.stdio"]).isEqualTo("false")
        assertThat(result["spring.main.web-application-type"]).isEqualTo("servlet")
        assertThat(result["server.port"]).isEqualTo("8080")
        assertThat(result["spring.main.lazy-initialization"]).isEqualTo("false")
    }

    @Test
    fun `preParseProviderFlags mcp-server --transport http --port 9090 sets port`() {
        val result = preParseProviderFlags(arrayOf("mcp-server", "--transport", "http", "--port", "9090"))
        assertThat(result["server.port"]).isEqualTo("9090")
    }

    @Test
    fun `preParseProviderFlags captures --store-dir`() {
        val result = preParseProviderFlags(arrayOf("mcp-server", "--transport", "http", "--store-dir", "/tmp/test-store"))
        assertThat(result["ez.rag.storeDir"]).isEqualTo("/tmp/test-store")
    }

    @Test
    fun `preParseProviderFlags captures --store-dir with equals syntax`() {
        val result = preParseProviderFlags(arrayOf("mcp-server", "--transport", "http", "--store-dir=/tmp/test-store"))
        assertThat(result["ez.rag.storeDir"]).isEqualTo("/tmp/test-store")
    }

    @Test
    fun `preParseProviderFlags mcp-server --transport http --port=9090 sets port`() {
        val result = preParseProviderFlags(arrayOf("mcp-server", "--transport", "http", "--port=9090"))
        assertThat(result["server.port"]).isEqualTo("9090")
    }

    @Test
    fun `preParseProviderFlags mcp-server --transport stdio sets stdio true and no servlet`() {
        val result = preParseProviderFlags(arrayOf("mcp-server", "--transport", "stdio"))
        assertThat(result["spring.ai.mcp.server.stdio"]).isEqualTo("true")
        assertThat(result["spring.main.web-application-type"]).isEqualTo("none")
        assertThat(result["spring.main.lazy-initialization"]).isEqualTo("true")
    }

    @Test
    fun `preParseProviderFlags mcp-server no transport defaults to stdio`() {
        val result = preParseProviderFlags(arrayOf("mcp-server"))
        assertThat(result["spring.ai.mcp.server.stdio"]).isEqualTo("true")
        assertThat(result["spring.main.web-application-type"]).isEqualTo("none")
        assertThat(result["spring.main.lazy-initialization"]).isEqualTo("true")
    }

    @Test
    fun `preParseProviderFlags ingest sets web-application-type none and no mcp stdio`() {
        val result = preParseProviderFlags(arrayOf("ingest", "somefile.txt"))
        assertThat(result["spring.main.web-application-type"]).isEqualTo("none")
        assertThat(result.containsKey("spring.ai.mcp.server.stdio")).isFalse()
        assertThat(result["spring.main.lazy-initialization"]).isEqualTo("true")
    }

    // -----------------------------------------------------------------------
    // logging suppression
    // -----------------------------------------------------------------------

    @Test
    fun `preParseProviderFlags mcp-server --transport http suppresses logging by default`() {
        val result = preParseProviderFlags(arrayOf("mcp-server", "--transport", "http"))
        assertThat(result["logging.level.root"]).isEqualTo("off")
    }

    @Test
    fun `preParseProviderFlags mcp-server --transport http --verbose does not suppress logging`() {
        val result = preParseProviderFlags(arrayOf("mcp-server", "--transport", "http", "--verbose"))
        assertThat(result.containsKey("logging.level.root")).isFalse()
    }

    @Test
    fun `preParseProviderFlags mcp-server --transport http -v does not suppress logging`() {
        val result = preParseProviderFlags(arrayOf("mcp-server", "--transport", "http", "-v"))
        assertThat(result.containsKey("logging.level.root")).isFalse()
    }

    @Test
    fun `preParseProviderFlags mcp-server --transport stdio suppresses logging`() {
        val result = preParseProviderFlags(arrayOf("mcp-server", "--transport", "stdio"))
        assertThat(result["logging.level.root"]).isEqualTo("off")
    }

    @Test
    fun `preParseProviderFlags ingest suppresses logging`() {
        val result = preParseProviderFlags(arrayOf("ingest", "somefile.txt"))
        assertThat(result["logging.level.root"]).isEqualTo("off")
    }

    // -----------------------------------------------------------------------
    // verbose propagation via @ParentCommand
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // preParseProviderFlags — local config file
    // -----------------------------------------------------------------------

    @Test
    fun `preParseProviderFlags reads embeddingProvider from local config yml`(@TempDir tempDir: Path) {
        val ezRagDir = tempDir.resolve(".ez-rag").toFile().also { it.mkdirs() }
        ezRagDir.resolve("config.yml").writeText("embeddingProvider: openai\n")

        val result = preParseProviderFlags(arrayOf("search", "test"), localEzRagDir = tempDir.resolve(".ez-rag"))
        assertThat(result["ez.rag.embeddingProvider"]).isEqualTo("openai")
    }

    @Test
    fun `preParseProviderFlags reads embeddingModel from local config yml`(@TempDir tempDir: Path) {
        val ezRagDir = tempDir.resolve(".ez-rag").toFile().also { it.mkdirs() }
        ezRagDir.resolve("config.yml").writeText("embeddingModel: text-embedding-3-small\n")

        val result = preParseProviderFlags(arrayOf("search", "test"), localEzRagDir = tempDir.resolve(".ez-rag"))
        assertThat(result["ez.rag.embeddingModel"]).isEqualTo("text-embedding-3-small")
    }

    @Test
    fun `preParseProviderFlags returns no embeddingProvider when no config file exists`(@TempDir tempDir: Path) {
        val result = preParseProviderFlags(arrayOf("search", "test"), localEzRagDir = tempDir.resolve(".ez-rag"))
        assertThat(result.containsKey("ez.rag.embeddingProvider")).isFalse()
        assertThat(result.containsKey("ez.rag.embeddingModel")).isFalse()
    }

    @Test
    fun `preParseProviderFlags with no local config dir succeeds without error`(@TempDir tempDir: Path) {
        val nonExistent = tempDir.resolve("nonexistent/.ez-rag")
        val result = preParseProviderFlags(arrayOf("status"), localEzRagDir = nonExistent)
        assertThat(result).isNotNull()
    }

    // -----------------------------------------------------------------------
    // --embedding-provider and --embedding-model removed from global options
    // -----------------------------------------------------------------------

    @Test
    fun `passing --embedding-provider as global flag causes UnmatchedArgumentException`() {
        val commandLine = CommandLine(EzRagCommand())
        val exitCode = commandLine.execute("--embedding-provider=openai", "status")
        assertThat(exitCode).isNotEqualTo(0)
    }

    @Test
    fun `passing --embedding-model as global flag causes UnmatchedArgumentException`() {
        val commandLine = CommandLine(EzRagCommand())
        val exitCode = commandLine.execute("--embedding-model=text-embedding-3-small", "status")
        assertThat(exitCode).isNotEqualTo(0)
    }

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

    // -----------------------------------------------------------------------
    // MCP server version sync
    // -----------------------------------------------------------------------

    @Test
    fun `preParseProviderFlags mcp-server spring ai mcp server version matches semver`(@TempDir tempDir: Path) {
        val result = preParseProviderFlags(arrayOf("mcp-server"), localEzRagDir = tempDir.resolve(".ez-rag"))
        val version = result["spring.ai.mcp.server.version"]
        assertThat(version).matches("\\d+\\.\\d+\\.\\d+")
    }

    @Test
    fun `preParseProviderFlags mcp-server spring ai mcp server version matches VersionProvider version`(@TempDir tempDir: Path) {
        val result = preParseProviderFlags(arrayOf("mcp-server"), localEzRagDir = tempDir.resolve(".ez-rag"))
        val mcpVersion = result["spring.ai.mcp.server.version"]
        val providerVersion = VersionProvider().getVersion()[0].removePrefix("ez-rag ")
        assertThat(mcpVersion).isEqualTo(providerVersion)
    }

    // -----------------------------------------------------------------------
    // --lock-timeout flag
    // -----------------------------------------------------------------------

    @Test
    fun `--lock-timeout default is 30`() {
        val ezRag = EzRagCommand()
        val commandLine = CommandLine(ezRag)
        // Execute any subcommand so picocli wires the command hierarchy
        commandLine.execute("ingest", "somefile.txt")
        assertThat(ezRag.lockTimeout).isEqualTo(30)
    }

    @Test
    fun `--lock-timeout 10 is inherited by EzRagCommand`() {
        val ezRag = EzRagCommand()
        val commandLine = CommandLine(ezRag)
        commandLine.execute("--lock-timeout", "10", "ingest", "somefile.txt")
        assertThat(ezRag.lockTimeout).isEqualTo(10)
    }

    @Test
    fun `--lock-timeout 0 is accepted`() {
        val ezRag = EzRagCommand()
        val commandLine = CommandLine(ezRag)
        val exitCode = commandLine.execute("--lock-timeout", "0", "ingest", "somefile.txt")
        assertThat(exitCode).isNotEqualTo(CommandLine.ExitCode.USAGE)
        assertThat(ezRag.lockTimeout).isEqualTo(0)
    }
}
