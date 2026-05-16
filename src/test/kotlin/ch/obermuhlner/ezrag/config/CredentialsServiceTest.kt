package ch.obermuhlner.ezrag.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CredentialsServiceTest {

    private fun makeRawCredentials(openaiKey: String? = null, anthropicKey: String? = null): RawCredentials =
        RawCredentials(openaiApiKey = openaiKey, anthropicApiKey = anthropicKey)

    @Test
    fun `resolves OpenAI key from env var and records EnvVar source`() {
        val service = CredentialsService(
            envVars = mapOf("OPENAI_API_KEY" to "sk-env-openai"),
            homeFileReader = { null }
        )
        val credentials = service.resolve()

        assertThat(credentials.openaiApiKey).isEqualTo("sk-env-openai")
        assertThat(credentials.openaiApiKeySource).isEqualTo(CredentialSource.EnvVar("OPENAI_API_KEY"))
    }

    @Test
    fun `resolves Anthropic key from env var and records EnvVar source`() {
        val service = CredentialsService(
            envVars = mapOf("ANTHROPIC_API_KEY" to "sk-ant-env"),
            homeFileReader = { null }
        )
        val credentials = service.resolve()

        assertThat(credentials.anthropicApiKey).isEqualTo("sk-ant-env")
        assertThat(credentials.anthropicApiKeySource).isEqualTo(CredentialSource.EnvVar("ANTHROPIC_API_KEY"))
    }

    @Test
    fun `resolves OpenAI key from home file when no env var set`() {
        val homePath = "/home/user/.ez-rag/credentials.yml"
        val service = CredentialsService(
            envVars = emptyMap(),
            homeFileReader = { makeRawCredentials(openaiKey = "sk-home-openai") to homePath }
        )
        val credentials = service.resolve()

        assertThat(credentials.openaiApiKey).isEqualTo("sk-home-openai")
        assertThat(credentials.openaiApiKeySource).isEqualTo(CredentialSource.File(homePath))
    }

    @Test
    fun `resolves Anthropic key from home file when no env var set`() {
        val homePath = "/home/user/.ez-rag/credentials.yml"
        val service = CredentialsService(
            envVars = emptyMap(),
            homeFileReader = { makeRawCredentials(anthropicKey = "sk-ant-home") to homePath }
        )
        val credentials = service.resolve()

        assertThat(credentials.anthropicApiKey).isEqualTo("sk-ant-home")
        assertThat(credentials.anthropicApiKeySource).isEqualTo(CredentialSource.File(homePath))
    }

    @Test
    fun `records Unset when neither env var nor home file provides a key`() {
        val service = CredentialsService(
            envVars = emptyMap(),
            homeFileReader = { null }
        )
        val credentials = service.resolve()

        assertThat(credentials.openaiApiKey).isNull()
        assertThat(credentials.openaiApiKeySource).isEqualTo(CredentialSource.Unset)
        assertThat(credentials.anthropicApiKey).isNull()
        assertThat(credentials.anthropicApiKeySource).isEqualTo(CredentialSource.Unset)
    }

    @Test
    fun `env var beats home file for OpenAI key`() {
        val service = CredentialsService(
            envVars = mapOf("OPENAI_API_KEY" to "sk-env-wins"),
            homeFileReader = { makeRawCredentials(openaiKey = "sk-home-loses") to "/home/.ez-rag/credentials.yml" }
        )
        val credentials = service.resolve()

        assertThat(credentials.openaiApiKey).isEqualTo("sk-env-wins")
        assertThat(credentials.openaiApiKeySource).isEqualTo(CredentialSource.EnvVar("OPENAI_API_KEY"))
    }

    @Test
    fun `env var beats home file for Anthropic key`() {
        val service = CredentialsService(
            envVars = mapOf("ANTHROPIC_API_KEY" to "sk-ant-env-wins"),
            homeFileReader = { makeRawCredentials(anthropicKey = "sk-ant-home-loses") to "/home/.ez-rag/credentials.yml" }
        )
        val credentials = service.resolve()

        assertThat(credentials.anthropicApiKey).isEqualTo("sk-ant-env-wins")
        assertThat(credentials.anthropicApiKeySource).isEqualTo(CredentialSource.EnvVar("ANTHROPIC_API_KEY"))
    }

    @Test
    fun `project-local key beats home file key`() {
        val projectPath = "/project/.ez-rag/credentials.yml"
        val homePath = "/home/.ez-rag/credentials.yml"
        val service = CredentialsService(
            envVars = emptyMap(),
            projectLocalFileReader = { makeRawCredentials(openaiKey = "sk-project-wins") to projectPath },
            homeFileReader = { makeRawCredentials(openaiKey = "sk-home-loses") to homePath }
        )
        val credentials = service.resolve()

        assertThat(credentials.openaiApiKey).isEqualTo("sk-project-wins")
        assertThat(credentials.openaiApiKeySource).isEqualTo(CredentialSource.File(projectPath))
    }

    @Test
    fun `falls back to home file when no project-local file exists`() {
        val homePath = "/home/.ez-rag/credentials.yml"
        val service = CredentialsService(
            envVars = emptyMap(),
            projectLocalFileReader = { null },
            homeFileReader = { makeRawCredentials(openaiKey = "sk-home-fallback") to homePath }
        )
        val credentials = service.resolve()

        assertThat(credentials.openaiApiKey).isEqualTo("sk-home-fallback")
        assertThat(credentials.openaiApiKeySource).isEqualTo(CredentialSource.File(homePath))
    }

    @Test
    fun `env var beats project-local file`() {
        val projectPath = "/project/.ez-rag/credentials.yml"
        val service = CredentialsService(
            envVars = mapOf("OPENAI_API_KEY" to "sk-env-wins"),
            projectLocalFileReader = { makeRawCredentials(openaiKey = "sk-project-loses") to projectPath },
            homeFileReader = { null }
        )
        val credentials = service.resolve()

        assertThat(credentials.openaiApiKey).isEqualTo("sk-env-wins")
        assertThat(credentials.openaiApiKeySource).isEqualTo(CredentialSource.EnvVar("OPENAI_API_KEY"))
    }

    @Test
    fun `CredentialSource File carries project-local path not home path when project-local wins`() {
        val projectPath = "/project/.ez-rag/credentials.yml"
        val homePath = "/home/.ez-rag/credentials.yml"
        val service = CredentialsService(
            envVars = emptyMap(),
            projectLocalFileReader = { makeRawCredentials(anthropicKey = "sk-ant-project") to projectPath },
            homeFileReader = { makeRawCredentials(anthropicKey = "sk-ant-home") to homePath }
        )
        val credentials = service.resolve()

        assertThat(credentials.anthropicApiKeySource).isEqualTo(CredentialSource.File(projectPath))
        assertThat(credentials.anthropicApiKeySource).isNotEqualTo(CredentialSource.File(homePath))
    }

    @Test
    fun `CredentialSource File carries home path when home file is used`() {
        val homePath = "/home/.ez-rag/credentials.yml"
        val service = CredentialsService(
            envVars = emptyMap(),
            projectLocalFileReader = { null },
            homeFileReader = { makeRawCredentials(anthropicKey = "sk-ant-home") to homePath }
        )
        val credentials = service.resolve()

        assertThat(credentials.anthropicApiKeySource).isEqualTo(CredentialSource.File(homePath))
    }
}
