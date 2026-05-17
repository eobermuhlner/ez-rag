package ch.obermuhlner.ezrag.config

import java.nio.file.Path

/**
 * Walks parent directories from a given start directory looking for a `.ez-rag/` subdirectory.
 * Falls back to `.ez-rag/` in the start directory when none is found.
 *
 * This is a plain class with no Spring dependencies — it can be instantiated directly in tests.
 *
 * Algorithm:
 * 1. Check if `<current>/.ez-rag/` is a directory.
 * 2. If yes, return it.
 * 3. If `<current>` has no parent (filesystem root), return `<startDir>/.ez-rag/` as the fallback.
 * 4. Otherwise, move to the parent and repeat.
 */
class EzRagDirResolver {

    fun resolve(startDir: Path): Path {
        var current = startDir.toAbsolutePath().normalize()
        while (true) {
            val candidate = current.resolve(".ez-rag")
            if (candidate.toFile().isDirectory) {
                return candidate
            }
            val parent = current.parent
            // Stop before traversing into root or its direct children (e.g. /tmp, /home, /usr)
            // to avoid picking up unrelated stores placed in system directories.
            if (parent == null || parent.nameCount <= 1) {
                return startDir.toAbsolutePath().normalize().resolve(".ez-rag")
            }
            current = parent
        }
    }
}
