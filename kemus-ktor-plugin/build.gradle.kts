plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "2.4.0"
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.0"

kotlin {
    jvmToolchain(21)

    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }

    // Server-side targets only — there is no HTTP server in a browser, so no js/wasmJs here.
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    mingwX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // Kemus types + Route/Application appear in the public plugin/route signatures.
                api(project(":kemus-core"))
                api("io.ktor:ktor-server-core:$ktorVersion")

                // The plugin installs these itself (JSON, SSE, error mapping).
                implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
                implementation("io.ktor:ktor-server-sse:$ktorVersion")
                implementation("io.ktor:ktor-server-status-pages:$ktorVersion")
            }
        }
        // Tests use the JVM-only ktor test host and the remote client.
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-server-test-host:$ktorVersion")
                implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
                implementation(project(":kemus-client"))
            }
        }
    }
}
