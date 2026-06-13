package ch.obermuhlner.ezrag

import ch.obermuhlner.ezrag.command.ChunkCommand
import ch.obermuhlner.ezrag.command.ToMarkdownCommand
import ch.obermuhlner.ezrag.command.DeleteCommand
import ch.obermuhlner.ezrag.command.EvalCommand
import ch.obermuhlner.ezrag.command.DownloadEvalCorpusCommand
import ch.obermuhlner.ezrag.command.HelpCommand
import ch.obermuhlner.ezrag.command.InitCommand
import ch.obermuhlner.ezrag.command.IngestCommand
import ch.obermuhlner.ezrag.command.InstallSkillCommand
import ch.obermuhlner.ezrag.command.ListCommand
import ch.obermuhlner.ezrag.command.McpServerCommand
import ch.obermuhlner.ezrag.command.QueryCommand
import ch.obermuhlner.ezrag.command.ReIngestCommand
import ch.obermuhlner.ezrag.command.SearchCommand
import ch.obermuhlner.ezrag.command.ShellCommand
import ch.obermuhlner.ezrag.command.ShowCommand
import ch.obermuhlner.ezrag.command.StatusCommand
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ScopeType
import java.util.concurrent.Callable

@Command(
    name = "ez-rag",
    mixinStandardHelpOptions = true,
    commandListHeading = "Subcommands:%n",
    description = ["A command-line tool for RAG (retrieval-augmented generation)."],
    subcommands = [
        InitCommand::class,
        InstallSkillCommand::class,
        IngestCommand::class,
        ChunkCommand::class,
        ReIngestCommand::class,
        DeleteCommand::class,
        ListCommand::class,
        ShowCommand::class,
        QueryCommand::class,
        SearchCommand::class,
        StatusCommand::class,
        McpServerCommand::class,
        ShellCommand::class,
        EvalCommand::class,
        DownloadEvalCorpusCommand::class,
        ToMarkdownCommand::class,
        HelpCommand::class,
    ]
)
@Component
class EzRagCommand : Callable<Int> {

    @Option(names = ["--verbose", "-v"], description = ["Enable verbose/debug logging."], scope = ScopeType.INHERIT)
    var verbose: Boolean = false

    @Option(names = ["--stack-trace"], description = ["Print full stack trace on error."], scope = ScopeType.INHERIT)
    var stackTrace: Boolean = false

    @Option(names = ["--provider"], description = ["Chat provider: openai, anthropic, ollama."], scope = ScopeType.INHERIT)
    var provider: String? = null

    @Option(names = ["--model"], description = ["Chat model name override."], scope = ScopeType.INHERIT)
    var model: String? = null

    @Option(names = ["--ollama-url"], description = ["Ollama base URL (default: http://localhost:11434)."])
    var ollamaUrl: String? = null

    @Option(names = ["--rerank-model"], description = ["Cross-encoder reranker model name (empty = reranking disabled)."], scope = ScopeType.INHERIT)
    var rerankModel: String? = null

    @Option(names = ["--rerank-candidates"], description = ["Number of candidates to fetch before reranking (default: topK * 3)."], scope = ScopeType.INHERIT)
    var rerankCandidates: Int? = null

    override fun call(): Int {
        if (verbose) {
            (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).level = Level.DEBUG
        }
        return 0
    }
}
