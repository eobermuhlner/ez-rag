package ch.obermuhlner.ezrag.config

data class Credentials(
    val openaiApiKey: String?,
    val openaiApiKeySource: CredentialSource,
    val anthropicApiKey: String?,
    val anthropicApiKeySource: CredentialSource,
)

sealed class CredentialSource {
    data class EnvVar(val name: String) : CredentialSource()
    data class File(val path: String) : CredentialSource()
    object Unset : CredentialSource() {
        override fun toString() = "Unset"
    }
}

/**
 * Holds the raw (un-annotated) API key strings read from a credentials file.
 */
data class RawCredentials(
    val openaiApiKey: String?,
    val anthropicApiKey: String?,
)
