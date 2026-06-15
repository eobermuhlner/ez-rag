package ch.obermuhlner.ezrag

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.InputStream

class VersionProviderTest {

    @Test
    fun `getVersion returns exactly one element`() {
        val versions = VersionProvider().getVersion()
        assertThat(versions).hasSize(1)
    }

    @Test
    fun `getVersion element starts with 'ez-rag '`() {
        val versions = VersionProvider().getVersion()
        assertThat(versions[0]).startsWith("ez-rag ")
    }

    @Test
    fun `getVersion version portion matches semver pattern`() {
        val versions = VersionProvider().getVersion()
        val versionPortion = versions[0].removePrefix("ez-rag ")
        assertThat(versionPortion).matches(Regex("\\d+\\.\\d+\\.\\d+").toPattern())
    }

    @Test
    fun `getVersion returns 'ez-rag unknown' when build-info properties resource is missing`() {
        val provider = object : VersionProvider() {
            override fun openBuildInfoStream(): InputStream? = null
        }
        val versions = provider.getVersion()
        assertThat(versions).hasSize(1)
        assertThat(versions[0]).isEqualTo("ez-rag unknown")
    }
}
