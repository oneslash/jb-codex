import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.10.4"
}

group = "com.jetbrains.codex"
version = "0.1.3"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        create("IC", "2024.3")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
    }
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        name = "JBCodex"
        version = project.version.toString()
        vendor {
            name = "JetBrains"
            email = "support@jetbrains.com"
            url = "https://jetbrains.com"
        }
        ideaVersion {
            sinceBuild = "243"
        }
    }

    pluginVerification {
        ides {
            create("IC", "2024.3")
        }
    }
}

tasks.withType<RunIdeTask>().configureEach {
    jvmArgs("-Dgradle.compatibility.update.interval=0")

    doFirst {
        val stateDb = sandboxConfigDirectory.get().file("app-internal-state.db").asFile
        if (stateDb.exists()) {
            logger.lifecycle("Removing stale Gradle JVM compatibility cache: ${stateDb}")
            project.delete(stateDb)
        }
    }
}
