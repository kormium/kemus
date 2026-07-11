import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "2.4.0"
    id("com.android.kotlin.multiplatform.library")
}

repositories {
    google()
    mavenCentral()
}

val ktorVersion = "3.5.0"

kotlin {
    jvmToolchain(21)

    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }

    android {
        namespace = "io.github.kemus.client"
        compileSdk = 36
        minSdk = 24
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    mingwX64()

    js {
        browser()
        nodejs()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // KemusCommands, Reply, Resp, the wire DTOs — the client implements the same API.
                api(project(":kemus-core"))
                // The caller supplies the HttpClient (and its platform engine); we only need the
                // multiplatform client core + the plugins the protocol uses.
                api("io.ktor:ktor-client-core:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }
}
