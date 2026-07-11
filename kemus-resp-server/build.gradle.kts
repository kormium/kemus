plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.0"

kotlin {
    jvmToolchain(21)

    jvm { testRuns["test"].executionTask.configure { useJUnitPlatform() } }

    // Server-side targets only — same set as kemus-ktor-plugin/kemus-server. No browser TCP server.
    linuxX64()
    linuxArm64()
    macosX64()
    macosArm64()
    mingwX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // The store + the RESP codec (reply encoding reused verbatim).
                api(project(":kemus-core"))
                // Multiplatform raw TCP sockets (the same network layer the CIO engine is built on).
                implementation("io.ktor:ktor-network:$ktorVersion")
            }
        }
        // Tests prove real redis-client compatibility by driving the server with Lettuce.
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
                implementation("io.lettuce:lettuce-core:6.5.5.RELEASE")
            }
        }
    }
}
