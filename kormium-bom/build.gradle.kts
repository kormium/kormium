plugins {
    `java-platform`
}

// Bill of Materials: pins the versions of every published Kormium artifact so consumers can
// depend on `platform("io.github.kormium:kormium-bom:<v>")` and omit versions elsewhere.
//
// The constraint list is derived from the root build's `publishableModules` — the same set that
// decides what gets published at all — rather than maintained by hand, so a newly published
// module cannot be forgotten here (issue #8: kormium-r2dbc and nine others had drifted out).
dependencies {
    constraints {
        @Suppress("UNCHECKED_CAST")
        val publishableModules = rootProject.extra["publishableModules"] as Set<String>
        (publishableModules - project.name).sorted().forEach {
            api("${project.group}:$it:${project.version}")
        }
    }
}
