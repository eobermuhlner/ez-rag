package ch.obermuhlner.ezrag.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

class ConfigFileWriter {

    fun write(path: Path, values: Map<String, Any>) {
        val existing: MutableMap<String, Any> = if (path.toFile().exists()) {
            @Suppress("UNCHECKED_CAST")
            (readConfigRaw(path.toString()) ?: emptyMap<String, Any>()).toMutableMap()
        } else {
            mutableMapOf()
        }

        existing.putAll(values)

        val dumperOptions = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        }
        val yaml = Yaml(dumperOptions)
        val yamlString = yaml.dump(existing)

        // Write atomically: temp file then rename
        val parent = path.parent
        val tempFile = Files.createTempFile(parent, "config-", ".tmp")
        try {
            tempFile.toFile().writeText(yamlString)
            Files.move(tempFile, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            tempFile.toFile().delete()
            throw e
        }
    }
}
