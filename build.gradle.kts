import com.vanniktech.maven.publish.MavenPublishBaseExtension

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Kotlin Gradle plugin for all modules (they apply kotlin("multiplatform")/kotlin("jvm")
        // without a version).
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
        // Android Gradle plugin for the modules that declare an android target via the AGP KMP
        // library plugin (kemus-core ships a client/Android artifact).
        classpath("com.android.tools.build:gradle:9.2.1")
    }
}

plugins {
    // Applied to the publishable library subprojects below (not to the root itself).
    id("com.vanniktech.maven.publish") version "0.36.0" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// iOS simulator tests need an installed iOS simulator runtime (Xcode). On a machine without one
// the simulator test task fails and breaks `check`. Gate those tasks on runtime availability so
// `check` stays runnable. Override with -PenableIosSimulatorTests=true|false.
val iosSimulatorTestsEnabled: Boolean by lazy {
    when (providers.gradleProperty("enableIosSimulatorTests").orNull) {
        "true" -> true
        "false" -> false
        else -> runCatching {
            val process = ProcessBuilder("xcrun", "simctl", "list", "runtimes")
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            output.lineSequence().any { it.contains("iOS") }
        }.getOrDefault(false)
    }
}

allprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
        onlyIf("no iOS simulator runtime available") { iosSimulatorTestsEnabled }
    }
}

// Publishing to Maven Central, shared by the library modules. The runnable kemus-server is shipped
// as a binary / Docker image, not a Maven artifact, so it is not published here. Credentials
// (mavenCentralUsername/Password) and the GPG key (signingInMemoryKey/Password) are supplied
// out-of-band via ORG_GRADLE_PROJECT_* env vars in CI — see gradle.properties.
val publishableModules = setOf(
    "kemus-core",
    "kemus-client",
    "kemus-ktor-plugin",
    "kemus-bom",
)

subprojects {
    if (name !in publishableModules) return@subprojects

    apply(plugin = "com.vanniktech.maven.publish")

    configure<MavenPublishBaseExtension> {
        publishToMavenCentral()
        signAllPublications()
        coordinates(group.toString(), name, version.toString())

        pom {
            name.set("kemus")
            description.set("kemus — an embedded Kotlin Multiplatform Redis-flavoured data store (client + offline + server).")
            inceptionYear.set("2026")
            url.set("https://github.com/kormium/kemus")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("knyazevs")
                    name.set("Sergey Knyazev")
                    email.set("sknyazev@vk.com")
                    url.set("https://github.com/knyazevs")
                }
            }
            scm {
                url.set("https://github.com/kormium/kemus")
                connection.set("scm:git:https://github.com/kormium/kemus.git")
                developerConnection.set("scm:git:ssh://git@github.com/kormium/kemus.git")
            }
        }
    }
}
