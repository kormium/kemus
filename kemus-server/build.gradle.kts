plugins {
    kotlin("multiplatform")
}

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.0"

kotlin {
    jvmToolchain(21)

    jvm()

    // Native server executables. entryPoint is the commonMain-driven `main` in each native source set.
    val nativeTargets = listOf(linuxX64(), linuxArm64(), macosX64(), macosArm64(), mingwX64())
    nativeTargets.forEach { target ->
        target.binaries.executable {
            entryPoint = "io.github.kemus.server.main"
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":kemus-ktor-plugin"))
                // Native RESP-over-TCP server, optionally started alongside the REST server.
                implementation(project(":kemus-resp-server"))
                // CIO is the multiplatform engine (JVM + native); Netty would pin us to the JVM.
                implementation("io.ktor:ktor-server-cio:$ktorVersion")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("ch.qos.logback:logback-classic:1.5.13")
            }
        }
    }
}

// Convenience `run` task for the JVM server (native servers run via runDebugExecutable<Target>).
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Runs the kemus REST server on the JVM."
    mainClass.set("io.github.kemus.server.MainKt")
    classpath = files(tasks.named("jvmJar"), configurations.named("jvmRuntimeClasspath"))
}
