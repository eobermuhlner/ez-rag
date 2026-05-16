package ch.obermuhlner.ezrag.config

import java.io.File
import java.io.PrintWriter

class GitIgnoreUpdater(private val noticeWriter: PrintWriter) {

    companion object {
        private const val CREDENTIALS_ENTRY = ".ez-rag/credentials.yml"
    }

    /**
     * Appends [CREDENTIALS_ENTRY] to `.gitignore` in [projectDir] if the file exists
     * and does not already contain the entry. Prints a notice when the entry is added.
     * Does nothing (no error, no modification) when `.gitignore` does not exist.
     */
    fun update(projectDir: File) {
        ensureEntry(projectDir, CREDENTIALS_ENTRY)
    }

    /**
     * Appends [entry] to `.gitignore` in [projectDir] if the file exists and does not
     * already contain the entry. Prints a notice when the entry is added.
     * Does nothing (no error, no modification) when `.gitignore` does not exist.
     */
    fun ensureEntry(projectDir: File, entry: String) {
        val gitignore = File(projectDir, ".gitignore")
        if (!gitignore.exists()) return

        val lines = gitignore.readLines()
        val alreadyPresent = lines.any { it.trim() == entry }
        if (alreadyPresent) return

        gitignore.appendText("\n$entry\n")
        noticeWriter.println("Notice: Added '$entry' to .gitignore.")
        noticeWriter.flush()
    }
}
