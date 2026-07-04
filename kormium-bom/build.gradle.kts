plugins {
    `java-platform`
}

// Bill of Materials: pins the versions of every published korm artifact so consumers can
// depend on `platform("io.github.kormium:kormium-bom:<v>")` and omit versions elsewhere.
dependencies {
    constraints {
        listOf(
            "kormium-core",
            "kormium-decimal",
            "kormium-postgres",
            "kormium-mysql",
            "kormium-jdbc",
            "kormium-sqlite",
            "kormium-migrate",
            "kormium-ktor",
            "kormium-ktor-di",
            "kormium-ktor-koin",
        ).forEach { api("${project.group}:$it:${project.version}") }
    }
}
