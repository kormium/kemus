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

kotlin {
    jvmToolchain(21)

    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    // Client/server JVM-flavoured target.
    android {
        namespace = "io.github.kemus.core"
        compileSdk = 36
        minSdk = 24
    }

    // Apple clients.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // Desktop / server native.
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    mingwX64()

    // Browser / offline web clients (no filesystem -> in-memory persistence only).
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
                // Single-writer concurrency (Mutex) + Pub/Sub (SharedFlow) are coroutine-based,
                // and are part of the public API.
                api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                // TTL/expiry clock; Clock is exposed in the public constructor.
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }

        // Filesystem-capable platforms (JVM, Android, all native): okio-backed AOF/snapshot
        // persistence. The web targets (js/wasmJs) intentionally do NOT depend on this set and
        // fall back to the in-memory persistence declared in commonMain.
        val fsMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation("com.squareup.okio:okio:3.9.1")
            }
        }
        val jvmMain by getting { dependsOn(fsMain) }
        val androidMain by getting { dependsOn(fsMain) }
        val nativeMain by getting { dependsOn(fsMain) }
    }
}
