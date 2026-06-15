package ch.obermuhlner.ezrag

import picocli.CommandLine
import java.io.InputStream
import java.util.Properties

open class VersionProvider : CommandLine.IVersionProvider {
    override fun getVersion(): Array<String> {
        val props = Properties()
        openBuildInfoStream()?.use { props.load(it) }
        val version = props.getProperty("build.version", "unknown")
        return arrayOf("ez-rag $version")
    }

    protected open fun openBuildInfoStream(): InputStream? =
        VersionProvider::class.java.getResourceAsStream("/META-INF/build-info.properties")
}
