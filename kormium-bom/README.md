# kormium-bom

The Bill of Materials for [Kormium](../readme.md). Import it once and omit the version on every
other Kormium artifact — the BOM pins them all to a single, consistent release.

## Install

```kotlin
dependencies {
    implementation(platform("io.github.kormium:kormium-bom:<version>"))

    // versions come from the BOM
    implementation("io.github.kormium:kormium-postgres")
    implementation("io.github.kormium:kormium-ktor")
    implementation("io.github.kormium:kormium-observe")
}
```

## Managed artifacts

`kormium-core`, `kormium-postgres`, `kormium-jdbc`, `kormium-sqlite`, `kormium-migrate`, `kormium-ktor`,
`kormium-ktor-di`, `kormium-ktor-koin`.

> `kormium-r2dbc` is not yet pinned by the BOM — give it an explicit version for now.

## Documentation

- [Installation](../docs/installation.md)
