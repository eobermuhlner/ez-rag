package ch.obermuhlner.ezrag.ingestion

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.toList

class DirectoryWalker(
    private val warningWriter: PrintWriter = PrintWriter(System.err, true),
) {

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("txt", "pdf", "md")
    }

    fun walk(root: Path): List<Path> {
        val results = mutableListOf<Path>()
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .forEach { path ->
                    val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
                    if (ext in SUPPORTED_EXTENSIONS) {
                        results.add(path)
                    } else {
                        warningWriter.println("Warning: Skipping unsupported file type: $path")
                    }
                }
        }
        return results.sortedBy { it.toString() }
    }
}
