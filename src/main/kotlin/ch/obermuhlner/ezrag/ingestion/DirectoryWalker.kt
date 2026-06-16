package ch.obermuhlner.ezrag.ingestion

import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path

class DirectoryWalker(
    @Suppress("UNUSED_PARAMETER")
    private val warningWriter: PrintWriter = PrintWriter(System.err, true),
) {

    fun walk(root: Path): List<Path> {
        val results = mutableListOf<Path>()
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .forEach { path ->
                    results.add(path)
                }
        }
        return results.sortedBy { it.toString() }
    }
}
