# Kotlin Web Targets (JS / Wasm-JS / Wasm-WASI)

Status of bringing Kormium to the Kotlin web stack: Kotlin/JS, Kotlin/Wasm (JS interop)
and Kotlin/Wasm (WASI). The goal is that the **typed DSL is the same everywhere**, and
that the browser gets a real embedded database — not a thin remote client.

## Why it is feasible

The core is already portable: `commonMain` has no `runBlocking`, no `Thread`, no `java.*`,
and mapping is DSL-based (`Table`/`Column`/`Expression`), not reflection. It already
compiles to Kotlin/Native, which proves it is free of JVM reflection and blocking IO. All
core dependencies (coroutines, serialization-json, datetime, ionspin-bignum) ship both
`wasmJs` and `wasmWasi` artifacts. The one exception is `kotlin-logging` (no `wasmWasi`
artifact as of 7.0.3), which is why logging now goes through an internal facade.

## Phase 0 — Portable core ✅ (done)

The typed DSL compiles and its test suite passes on `js`, `wasmJs` and `wasmWasi`.

- Internal `KormiumLogger` facade (`commonMain`) decouples core from `kotlin-logging`.
  Every target except wasmWasi delegates to `kotlin-logging` via the `loggingMain`
  intermediate source set; wasmWasi gets a no-op actual.
- `js { nodejs() }`, `wasmJs { nodejs() }`, `wasmWasi { nodejs() }` added to
  `kormium-core`. `nodejs()` is enough to compile the klib and run tests; the produced
  klib is usable in the browser regardless. `browser()` is added per-engine where a
  browser demo is actually run.
- `ioDispatcher` actuals for the three targets use `Dispatchers.Default` (single-threaded;
  the JS drivers are async, so there is nothing to offload).
- No public API change.

## Phase 1 — engines

The proof that the SPI fits the web stack is an **async** engine (`SuspendDatabase` +
`SuspendSqlExecutor` + a `ResultSet` adapter), like the r2dbc backend — you cannot block a
JS event loop on a Promise, so there is no blocking `Database` here; values bind as text with
unspecified OID (the libpq approach) and results are read back through text.

Near-term targets (have real DB access today):

- **Browser — SQLite** ✅ `kormium-sqlite-wasm`. wa-sqlite (SQLite in WASM, ~1.1 MB), async
  engine over its C API, reusing `kormium-sqlite-dialect`. Persists to IndexedDB via
  `IDBBatchAtomicVFS` (`createSqliteWasmDatabase("name")`). Shown by `samples/wasm-todo`, a
  Compose Multiplatform todo app — `./gradlew :samples:wasm-todo:wasmJsBrowserDevelopmentRun`.
  Tested under Node in CI by passing the `.wasm` as `wasmBinary` (wa-sqlite's async build would
  otherwise `fetch()` it, which Node rejects for `file://`).
- **Node — SQLite / Postgres / MySQL** ✅ `kormium-sqlite-node` (better-sqlite3),
  `kormium-postgres-node` (node-postgres, pooled), `kormium-mysql-node` (mysql2, pooled). Kotlin
  compiled to JS/Wasm running under Node against real databases; suspend-only, sharing
  `kormium-wasm-driver`.

### PGlite lives in a separate repo

A first engine — **PGlite** (full Postgres compiled to WASM, real Postgres in the browser) —
was built and verified end-to-end under Node, then moved out to
[github.com/kormium/pglite](https://github.com/kormium/pglite). It is a niche, heavyweight
target (~20 MB), kept out of core; use it when you specifically want Postgres-on-Postgres
parity between client and server. The patterns it proved (named-ESM `@file:JsModule` import,
text binding, Promise→suspend, single-connection `Mutex`) carry over to the engines above.

## Phase 2 — Breadth ✅ (done)

- All three Node engines shipped (sqlite/pg/mysql, above).
- The pure modules `kormium-migrate` and `kormium-observe` now ship web artifacts too.

## Phase 3 — Upstream + wasmWasi

- PR to `oshai/kotlin-logging` adding a `wasmWasi` target plus a stdout logging backend.
  When released, the wasmWasi facade actual switches from no-op to a real delegate — one
  line, no call-site change.
- **wasmWasi is DSL-only for now.** There is no reachable database: no JS interop (so
  PGlite is unavailable), no DOM, and no WASI sockets in Kotlin yet (and wasmWasi cannot
  link a separate C/wasm SQLite module). The DSL + dialect + SPI still compile, so users
  can build queries and plug their own transport. Tracked against WASI sockets / preview2
  maturing in Kotlin.

## Known constraints

- Kotlin/JS exports to idiomatic TypeScript poorly for a generic-heavy DSL. The target
  audience here is **Kotlin/JS authors**, not npm/TypeScript consumers; a clean `.d.ts`
  npm package is out of scope.
