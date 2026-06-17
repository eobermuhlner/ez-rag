package ch.obermuhlner.ezrag.ingestion.office

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

private val IGNORED_ZIP_ENTRIES = setOf("docProps/core.xml", "docProps/app.xml")

fun writeIfChanged(file: File, generate: (ByteArrayOutputStream) -> Unit) {
    val generated = ByteArrayOutputStream().also(generate).toByteArray()
    if (file.exists() && zipContentsMatch(file.readBytes(), generated)) return
    file.writeBytes(generated)
}

private fun zipContentsMatch(existing: ByteArray, generated: ByteArray): Boolean {
    val a = readContentEntries(existing) ?: return false
    val b = readContentEntries(generated) ?: return false
    if (a.keys != b.keys) return false
    return a.all { (name, content) -> b[name]?.contentEquals(content) == true }
}

private fun readContentEntries(data: ByteArray): Map<String, ByteArray>? = try {
    buildMap {
        ZipInputStream(data.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name !in IGNORED_ZIP_ENTRIES) {
                    put(entry.name, zis.readBytes())
                }
                entry = zis.nextEntry
            }
        }
    }
} catch (e: Exception) {
    null
}
