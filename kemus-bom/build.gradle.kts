plugins {
    `java-platform`
}

// Bill of Materials: pins the versions of every published kemus library so consumers can depend on
// `platform("io.github.kormium:kemus-bom:<v>")` and omit versions on the individual artifacts.
dependencies {
    constraints {
        listOf(
            "kemus-core",
            "kemus-client",
            "kemus-ktor-plugin",
        ).forEach { api("${project.group}:$it:${project.version}") }
    }
}
