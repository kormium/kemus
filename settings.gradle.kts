pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "kemus"

// kemus-core        — embedded, multiplatform in-memory data store (Redis-flavoured engine).
// kemus-client      — multiplatform remote client: same KemusCommands API, talks to kemus-server.
// kemus-ktor-plugin — reusable ktor plugin + REST routes over the core (embed in any ktor app).
// kemus-server      — thin runnable standalone REST server that installs the plugin.
// kemus-resp-server — native RESP-over-TCP server (real Redis wire protocol) over the core.
// kemus-benchmarks  — JVM-only JMH harness comparing the engine, kemus-server (HTTP) and Redis.
// kemus-bom         — Bill of Materials pinning the published library versions.
include("kemus-core", "kemus-client", "kemus-ktor-plugin", "kemus-server", "kemus-resp-server", "kemus-benchmarks")
include("kemus-bom")
