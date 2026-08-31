# kormium-jdbc

The shared JVM JDBC plumbing for [Kormium](../readme.md): a generic JDBC `SqlExecutor` that
maps core's `ResultSet` and binds core's `SqlParameterSource`, pooled with HikariCP.

This is an **internal building block**, not an end-user backend. `kormium-postgres` (pgjdbc) and
`kormium-sqlite` (sqlite-jdbc) wire it with a driver-specific JDBC URL and result-set wrapper on
the JVM, and both bring it in transitively.

Depend on it directly only when you implement a **custom JDBC backend** for a database Kormium
does not ship — reuse this driver and supply your own `Dialect`, JDBC URL and result-set
wrapper.

## Install

```kotlin
dependencies {
    implementation(platform("io.github.kormium:kormium-bom:<version>"))
    implementation("io.github.kormium:kormium-jdbc")
}
```

`kormium-core` is part of the public API and is pulled in transitively.

## Platforms

JVM only.

## Documentation

- [Backends and platform support](../docs/backends.md)
- [Design notes](../docs/design.md)
