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
        val hfEnvToken = envVars["HF_TOKEN"]
        val huggingfaceEnvToken = envVars["HUGGINGFACE_TOKEN"]

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

        val huggingfaceToken: String?
        val huggingfaceTokenSource: CredentialSource
        if (hfEnvToken != null) {
            huggingfaceToken = hfEnvToken
            huggingfaceTokenSource = CredentialSource.EnvVar("HF_TOKEN")
        } else if (huggingfaceEnvToken != null) {
            huggingfaceToken = huggingfaceEnvToken
            huggingfaceTokenSource = CredentialSource.EnvVar("HUGGINGFACE_TOKEN")
        } else if (projectLocalCredentials?.huggingfaceToken != null && projectLocalPath != null) {
            huggingfaceToken = projectLocalCredentials.huggingfaceToken
            huggingfaceTokenSource = CredentialSource.File(projectLocalPath)
        } else if (homeCredentials?.huggingfaceToken != null && homePath != null) {
            huggingfaceToken = homeCredentials.huggingfaceToken
            huggingfaceTokenSource = CredentialSource.File(homePath)
        } else {
            huggingfaceToken = null
            huggingfaceTokenSource = CredentialSource.Unset
        }

        return Credentials(
            openaiApiKey = openaiApiKey,
            openaiApiKeySource = openaiApiKeySource,
            anthropicApiKey = anthropicApiKey,
            anthropicApiKeySource = anthropicApiKeySource,
            huggingfaceToken = huggingfaceToken,
            huggingfaceTokenSource = huggingfaceTokenSource,
        )
    }
}
