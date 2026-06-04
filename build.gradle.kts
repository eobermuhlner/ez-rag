plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
    application
    jacoco
}

group = "ch.obermuhlner"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass.set("ch.obermuhlner.ezrag.EzRagApplicationKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "1.0.0"

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.springframework.ai:spring-ai-starter-model-ollama")
    implementation("org.springframework.ai:spring-ai-starter-model-transformers")
    implementation("org.springframework.ai:spring-ai-vector-store")
    implementation("org.springframework.ai:spring-ai-pdf-document-reader")
    implementation("org.springframework.ai:spring-ai-starter-mcp-server")
    implementation("com.microsoft.onnxruntime:onnxruntime:1.19.2")
    implementation("ai.djl.huggingface:tokenizers:0.32.0")
    implementation("org.apache.lucene:lucene-core:9.12.1")
    implementation("org.apache.lucene:lucene-analysis-common:9.12.1")
    implementation("org.apache.lucene:lucene-queryparser:9.12.1")
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Werror")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    finalizedBy(tasks.jacocoTestReport)
    System.getenv("TMPDIR")?.let { systemProperty("java.io.tmpdir", it) }
    useJUnitPlatform {
        val tags = System.getProperty("tags")
        val evalOnly = project.hasProperty("eval")
        when {
            evalOnly -> includeTags("eval")
            tags != null -> includeTags(tags)
            else -> excludeTags("integration", "eval")
        }
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

// Embed the Gradle-provisioned JDK 21 as a fallback JAVA_HOME so the installDist
// scripts work even when the system default Java is older than 21.
tasks.named<CreateStartScripts>("startScripts") {
    val launcher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(21)
    }
    doLast {
        val javaHome = launcher.get().executablePath.asFile.parentFile.parentFile.absolutePath
        unixScript.writeText(
            unixScript.readText().replace(
                "\nif [ -n \"\$JAVA_HOME\" ] ;",
                "\n# Default to the JDK 21 toolchain when JAVA_HOME is not set in the environment\n" +
                "if [ -z \"\$JAVA_HOME\" ]; then\n    JAVA_HOME=\"${javaHome}\"\nfi\n\n" +
                "if [ -n \"\$JAVA_HOME\" ] ;"
            )
        )
    }
}
