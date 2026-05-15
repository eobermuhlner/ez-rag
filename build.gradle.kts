plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
    application
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
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("info.picocli:picocli:4.7.6")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Werror")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
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
