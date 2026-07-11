plugins {
    kotlin("jvm")
    // Compiles `src/jmh`, wires the JMH bytecode generator and adds the `jmh` run task. The Kotlin
    // plugin contributes a `compileJmhKotlin` for the source set, so benchmarks can be written in
    // Kotlin without kapt.
    id("me.champeau.jmh") version "0.7.2"
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.0"
val coroutinesVersion = "1.10.2"

kotlin {
    jvmToolchain(21)
}

// JVM-only module: it embeds a real kemus-server (CIO) in-process, drives it through the remote
// KemusClient over HTTP, and compares against Redis (Lettuce) in a Testcontainers container. None
// of this is multiplatform, so a plain `kotlin("jvm")` module is the right home.
dependencies {
    // Layer 1 — the embedded engine in isolation (no network, no protocol).
    jmhImplementation(project(":kemus-core"))
    // Layer 2 — the full server path: remote client -> HTTP -> CIO ktor server -> engine.
    jmhImplementation(project(":kemus-client"))
    jmhImplementation(project(":kemus-ktor-plugin"))
    // Layer 2b — the native RESP-over-TCP server path (driven by Lettuce, same client as Redis).
    jmhImplementation(project(":kemus-resp-server"))
    jmhImplementation("io.ktor:ktor-server-cio:$ktorVersion")
    jmhImplementation("io.ktor:ktor-client-cio:$ktorVersion")
    // Alternative client engine for the reuse diagnostic (java.net.http pools connections).
    jmhImplementation("io.ktor:ktor-client-java:$ktorVersion")
    jmhImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    // Layer 3 — the Redis baseline: Lettuce sync client against a Testcontainers redis:7 container.
    jmhImplementation("io.lettuce:lettuce-core:6.5.5.RELEASE")
    jmhImplementation("org.testcontainers:testcontainers:1.20.4")

    // Quiet, JMH-friendly logging for ktor/lettuce/testcontainers during runs.
    jmhImplementation("org.slf4j:slf4j-simple:2.0.16")
}

jmh {
    jmhVersion = "1.37"
    // One JVM fork keeps wall-clock time reasonable while still isolating from the Gradle daemon.
    fork = 1
    warmupIterations = 3
    iterations = 5
    // Each iteration is wall-clock bounded so a full run stays in the low minutes.
    warmup = "2s"
    timeOnIteration = "3s"
    // A single client thread measures per-op latency/throughput on one connection (Redis-benchmark's
    // default mental model). Bump with `-Pjmh.threads=N` or the `threads` property for concurrency.
    threads = 1
    failOnError = true
    resultFormat = "JSON"
}
