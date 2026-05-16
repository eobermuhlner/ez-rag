package ch.obermuhlner.ezrag.config

class CredentialsService(
    private val envVars: Map<String, String>,
    private val projectLocalFileReader: (() -> Pair<RawCredentials, String>?)? = null,
    private val homeFileReader: () -> Pair<RawCredentials, String>?,
) {

    fun resolve(): Credentials {
        val projectLocalResult = projectLocalFileReader?.invoke()
        val projectLocalCredentials = projectLocalResult?.first
        val projectLocalPath = projectLocalResult?.second

        val homeResult = homeFileReader()
        val homeCredentials = homeResult?.first
        val homePath = homeResult?.second

        val openaiEnvKey = envVars["OPENAI_API_KEY"]
        val anthropicEnvKey = envVars["ANTHROPIC_API_KEY"]

        val openaiApiKey: String?
        val openaiApiKeySource: CredentialSource
        if (openaiEnvKey != null) {
            openaiApiKey = openaiEnvKey
            openaiApiKeySource = CredentialSource.EnvVar("OPENAI_API_KEY")
        } else if (projectLocalCredentials?.openaiApiKey != null && projectLocalPath != null) {
            openaiApiKey = projectLocalCredentials.openaiApiKey
            openaiApiKeySource = CredentialSource.File(projectLocalPath)
        } else if (homeCredentials?.openaiApiKey != null && homePath != null) {
            openaiApiKey = homeCredentials.openaiApiKey
            openaiApiKeySource = CredentialSource.File(homePath)
        } else {
            openaiApiKey = null
            openaiApiKeySource = CredentialSource.Unset
        }

        val anthropicApiKey: String?
        val anthropicApiKeySource: CredentialSource
        if (anthropicEnvKey != null) {
            anthropicApiKey = anthropicEnvKey
            anthropicApiKeySource = CredentialSource.EnvVar("ANTHROPIC_API_KEY")
        } else if (projectLocalCredentials?.anthropicApiKey != null && projectLocalPath != null) {
            anthropicApiKey = projectLocalCredentials.anthropicApiKey
            anthropicApiKeySource = CredentialSource.File(projectLocalPath)
        } else if (homeCredentials?.anthropicApiKey != null && homePath != null) {
            anthropicApiKey = homeCredentials.anthropicApiKey
            anthropicApiKeySource = CredentialSource.File(homePath)
        } else {
            anthropicApiKey = null
            anthropicApiKeySource = CredentialSource.Unset
        }

        return Credentials(
            openaiApiKey = openaiApiKey,
            openaiApiKeySource = openaiApiKeySource,
            anthropicApiKey = anthropicApiKey,
            anthropicApiKeySource = anthropicApiKeySource,
        )
    }
}
