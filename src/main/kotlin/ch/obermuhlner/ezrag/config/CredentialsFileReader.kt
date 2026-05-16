package ch.obermuhlner.ezrag.config

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

class CredentialsFileReader(private val warningWriter: PrintWriter) {

    fun read(path: String): RawCredentials? {
        val file = File(path)
        if (!file.exists()) return null

        checkPermissions(path)

        val data: Map<String, Any> = file.inputStream().use { Yaml().load(it) } ?: return RawCredentials(null, null)

        val openaiApiKey = data.string("openai-api-key") ?: data.string("openaiApiKey")
        val anthropicApiKey = data.string("anthropic-api-key") ?: data.string("anthropicApiKey")

        return RawCredentials(openaiApiKey = openaiApiKey, anthropicApiKey = anthropicApiKey)
    }

    private fun checkPermissions(path: String) {
        try {
            val permissions = Files.getPosixFilePermissions(Paths.get(path))
            val insecure = permissions.contains(PosixFilePermission.GROUP_READ) ||
                    permissions.contains(PosixFilePermission.GROUP_WRITE) ||
                    permissions.contains(PosixFilePermission.GROUP_EXECUTE) ||
                    permissions.contains(PosixFilePermission.OTHERS_READ) ||
                    permissions.contains(PosixFilePermission.OTHERS_WRITE) ||
                    permissions.contains(PosixFilePermission.OTHERS_EXECUTE)
            if (insecure) {
                warningWriter.println(
                    "WARNING: Credentials file '$path' has insecure permissions. " +
                    "Run: chmod 600 $path"
                )
                warningWriter.flush()
            }
        } catch (e: UnsupportedOperationException) {
            // Non-POSIX filesystem (e.g. Windows) — silently skip permission check
        }
    }

    private fun Map<String, Any>.string(key: String): String? = this[key] as? String
}
