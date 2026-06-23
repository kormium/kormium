# wasm-todo — Kormium + Compose Multiplatform + SQLite in the browser

A todo app that runs entirely in the browser on **Kotlin/Wasm**: a Compose Multiplatform UI on top
of the Kormium typed DSL, backed by an **embedded SQLite** (wa-sqlite, SQLite compiled to WASM)
persisted to **IndexedDB**. No server, no network — the same `Table`/`Entity` DSL you'd use on the
JVM, running against a real SQLite inside the page.

## Run it

```bash
./gradlew :samples:wasm-todo:wasmJsBrowserDevelopmentRun
```

Add todos, tick them off, delete them, then reload the page — the data is still there, because
wa-sqlite persists to IndexedDB (`createSqliteWasmDatabase("kormium-todo")`).

Static bundle instead:

```bash
./gradlew :samples:wasm-todo:wasmJsBrowserDistribution
# output: build/dist/wasmJs/productionExecutable/
```

## How it fits together

| Layer | What it is |
|-------|------------|
| `Schema.kt` | `Todos` table + `Todo` entity — the ordinary Kormium DSL, no annotations/reflection. |
| `TodoRepository.kt` | `suspendTransaction` / `suspendAutocommit` calls — identical to a JVM/Native app. |
| `App.kt` | Compose Multiplatform UI; every mutation calls the repository and refreshes. |
| `kormium-sqlite-wasm` | The engine: a `SuspendDatabase` over wa-sqlite, reusing `kormium-sqlite-dialect`. |

The only unusual thing is the database: `createSqliteWasmDatabase("kormium-todo")` boots an embedded
SQLite in WASM and persists it to IndexedDB. Everything above it is the same Kormium you use on the
server.

## Notes

- **wasmJs only** — wa-sqlite is reached through JS interop.
- Forces the `kotlinx-datetime:*-0.6.x-compat` build to dodge a `kotlinx.datetime.Instant` typealias
  clash with Kotlin 2.4's stable `kotlin.time.Instant` during the wasm executable link; see the
  comment in `build.gradle.kts`.
