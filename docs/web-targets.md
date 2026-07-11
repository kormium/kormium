# Kotlin Web Targets (JS / Wasm-JS / Wasm-WASI)

Status of bringing Kormium to the Kotlin web stack: Kotlin/JS, Kotlin/Wasm (JS interop)
and Kotlin/Wasm (WASI). The goal is that the **typed DSL is the same everywhere**, and
that the browser gets a real embedded database — not a thin remote client.

## Why it is feasible

The core is already portable: `commonMain` has no `runBlocking`, no `Thread`, no `java.*`,
and mapping is DSL-based (`Table`/`Column`/`Expression`), not reflection. It already
compiles to Kotlin/Native, which proves it is free of JVM reflection and blocking IO. All
core dependencies (coroutines, serialization-json, datetime) ship both
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

## Phase 4 — Concurrent browser SQLite reads (OPFS + Worker pool)

> **Outcome first (the detail below is the engineering record that led here):** the pool shipped
> but is **experimental with a narrow niche**, not the browser-analytics default the plan assumed.
> Validation flipped the premise — see the [addendum](#phase-4-addendum--measured-limits-validation-against-sql-demo-at-1m-rows)
> and [ADR 0010](adr/0010-browser-sqlite-three-engines.md). The recommended default is now a
> *third* engine, `createWorkerSqliteWasmDatabase` (Worker-hosted in-memory), also built here.

**Motivation.** Every other pooled Kormium backend gives you real concurrency out of the box:
JVM SQLite pools JDBC connections (HikariCP), and the Node Postgres/MySQL engines pool their
native driver's connections. `kormium-sqlite-wasm`'s original engine was single-connection
(`SqliteWasmDatabase` guards one wa-sqlite instance with a `Mutex`). This surfaced concretely in
`kormium/playground`'s `sql-demo`: independent read-only aggregate queries against a large dataset
couldn't overlap — wall-clock time was the *sum* of every query, not the slowest one.

**What shipped, and why it doesn't look like the original sketch:**

- **New engine, new dependency.** `kormium-sqlite-wasm`'s existing `wa-sqlite` dependency turned
  out to be npm `1.0.0`, published January 2024 and never updated since; its `AccessHandlePoolVFS`
  explicitly does not support multiple connections. Building the originally-sketched spike on it
  would have failed by construction, not by a real browser limitation. Instead, the pooled engine
  is built on the officially-maintained **`@sqlite.org/sqlite-wasm`** package (published in
  lockstep with SQLite releases) via a new standalone library, **[`kormium/sqlite-wasm-kt`](https://github.com/kormium/sqlite-wasm-kt)**
  (mirrors the `kormium/decimal` precedent — reusable outside Kormium, no dependency on Kormium
  types). It uses that package's `OpfsWlDb` (the `opfs-wl` VFS, SQLite 3.53.0+: real
  multi-connection concurrent access via Web Locks + `Atomics.waitAsync`, no custom VFS/locking
  code needed from us).
- **COOP/COEP is required — confirmed empirically, not assumed.** Tested directly: `OpfsWlDb`
  registration silently no-ops (`sqlite3.oo1.OpfsWlDb is not a constructor`, no error) without
  `Cross-Origin-Opener-Policy: same-origin` + `Cross-Origin-Embedder-Policy: require-corp`; works
  fully with them. GitHub Pages cannot serve custom headers, so hosting the pooled engine's demo
  needs Netlify/Cloudflare Pages or similar (a `_headers` file).
- **A pooled Worker needs a worker script; a Kotlin/Wasm *library* can't ship one transparently.**
  Kotlin/Wasm compiles one bundle per **executable**, not per library, and a Worker needs a real,
  separate script to load — a library alone has no bundle of its own to point a `Worker` at. The
  fix: `kormium/sqlite-wasm-kt` ships a companion **executable** subproject,
  `sqlite-wasm-kt-worker` — a tiny, Kormium-independent Worker entry point that answers a generic
  `postMessage` RPC protocol (open/execute/query/close) over one `SqliteWasmConnection`. It's
  distributed as its own npm package; `kormium-sqlite-wasm` depends on it via
  `implementation(npm("@kormium/sqlite-wasm-worker", ...))`, and `new Worker(new
  URL('@kormium/sqlite-wasm-worker/...', import.meta.url))` — webpack's built-in `new
  Worker(new URL(...))` bundling picks this up automatically as its own chunk, **with zero
  consumer-side webpack config for the Worker delivery itself** (confirmed empirically with a
  throwaway npm package + real headless-Chrome run before building the real thing).
  - One real gotcha this surfaced: the worker package must ship **raw ESM** (the Kotlin/Wasm
    compiler's un-webpacked `.mjs`/`.wasm` output), not `wasmJsBrowserDistribution`'s
    already-webpack-bundled UMD output. A pre-bundled file is one opaque CommonJS module to the
    *consumer's* webpack, which then never re-discovers/re-emits `@sqlite.org/sqlite-wasm`'s own
    `sqlite3.wasm` asset reference bundled *inside* that opaque blob — the asset silently never
    makes it into the final dist directory. Raw ESM source doesn't have this problem: the
    consumer's webpack traces it like any other module and emits its assets itself.
  - A consumer app *does* need two small, one-time webpack config additions (`webpack.config.d/`):
    `output.environment.dynamicImport = true`, and marking `node:module`/`node:fs`/`node:path`/
    `node:url`/the Deno path specifier as `externals` — `@sqlite.org/sqlite-wasm`'s Node/Deno
    environment-detection branches reference those, never reached in a browser but still
    statically parsed by webpack. This is required only for apps that actually call
    `createPooledSqliteWasmDatabase`; Kotlin/Wasm's dead-code elimination drops the whole
    dependency (and the requirement) for apps that don't, confirmed by rebuilding `wasm-todo`
    (the single-connection demo) without either the call or the config and getting its original,
    unchanged bundle back.
- **Routing signal is `readOnly`, not `transactional`.** `SuspendDatabase.useConnection`'s
  `transactional = false` (i.e. `suspendAutocommit { }`) covers both reads *and* single-statement
  writes — the interface doesn't distinguish them. Routing all of it to the reader pool would risk
  sending an autocommit write to a connection that can't be guaranteed writable. So only an
  explicit `suspendTransaction(readOnly = true) { }` — the one unambiguous "this is a read" signal
  — routes to the reader pool; everything else (including plain `suspendAutocommit`) goes to the
  writer. Callers who want pooled reads must use `suspendTransaction(readOnly = true) { }`, not
  `suspendAutocommit { }`.
- **A subtle Worker-side race, found and fixed:** sending the first request immediately after
  `new Worker(...)` raced the worker's own startup in this specific Kotlin/Wasm+webpack-chunk
  setup — the message was sent before the worker's listener was reliably observed to be attached
  in practice. Fixed with an explicit ready handshake: the worker posts a sentinel message the
  moment its listener is registered, and the main-thread side awaits it before sending the real
  `open` request.

**API shipped** (additive; the original `createSqliteWasmDatabase` is untouched — but see the
addendum: the recommended default became `createWorkerSqliteWasmDatabase`, and this pooled factory
is experimental):

```kotlin
public suspend fun createPooledSqliteWasmDatabase(
    opfsPath: String,
    readerPoolSize: Int = 4,
    config: KormiumConfig = KormiumConfig(),
): PooledSqliteWasmDatabase
```

**Verified end-to-end** (`kormium-sqlite-wasm` depending on `kormium/sqlite-wasm-kt` via a
composite build, real headless Chrome, COOP/COEP headers): writer connection commits
CREATE/DELETE/INSERT; two reader connections then run `suspendTransaction(readOnly = true) { }`
reads that genuinely overlap (their `BEGIN` statements land within microseconds of each other in
the SQL trace, not queued one after another).

Both halves are published: `io.github.kormium:sqlite-wasm-kt` on Maven Central and
`@kormium/sqlite-wasm-worker` on npm (0.1.0), so `kormium-sqlite-wasm` consumes them as ordinary
dependencies. (The [Backends](backends.md) per-engine table and
[ADR 0010](adr/0010-browser-sqlite-three-engines.md) are done.)

### Phase 4 addendum — measured limits (validation against `sql-demo` at 1M rows)

Validating against the workload that motivated the phase produced a verdict the original plan did
not anticipate: **the pooled engine is EXPERIMENTAL, and its niche is much narrower than "browser
analytical dashboards".** What the measurements showed, in order:

- **Three fixes took a single 1M-row indexed aggregate from ~1.7 s to ~100–125 ms** (all landed):
  the wa-sqlite-era `'ct'` open flags copied from the upstream demo enabled *per-statement SQL
  tracing to `console.log`* (`t` = trace — fixed to `'c'` in `sqlite-wasm-kt`); SQLite's ~2 MB
  default page cache forced re-reading hot index pages through OPFS every query (each pool
  connection now gets `PRAGMA cache_size=-32768` + `temp_store=MEMORY`); and the BEGIN/COMMIT wrap
  around every read-only block cost two extra `postMessage` round trips per query, each of which
  also waits in the main thread's event queue under active UI rendering. Read-only blocks on the
  pool now skip the wrap — semantically that makes them READ COMMITTED (per-statement snapshots)
  rather than SERIALIZABLE; a future `isolation = SERIALIZABLE` opt-in restoring the real
  transaction is a parked candidate.
- **After those fixes, reader parallelism inverted into a loss.** With queries this fast, opfs-wl's
  per-statement lock handoff between connections (Web Locks + `Atomics.waitAsync`, sync access
  handles are exclusive by spec) dominates: the same 4-query dashboard burst measured ~410 ms wall
  on ONE reader vs ~1030 ms on FOUR. This matches upstream wa-sqlite findings (~60k tps single
  connection → ~1.5k with two). Two-lane composition (separate pool instances for fast/heavy
  queries on one OPFS file) was also measured: it costs ~30% on a single burst from cross-lane
  lock contention, and under rapid repeated bursts (slider dragging) multi-connection lock
  acquisition **fails outright** — `xLock() GetSyncHandleError` after opfs-wl's 5 retries, because
  cancelled coroutines don't cancel requests already queued on the Workers.
- **The right tool for `sql-demo` turned out to be in-memory, single connection — and that became
  a third engine.** The demo regenerates its dataset on every load, so OPFS persistence buys
  nothing, and one connection at memory speed with zero lock traffic beats every pooled
  configuration measured. `createWorkerSqliteWasmDatabase()` (shipped alongside the pool) hosts
  that one in-memory connection in a dedicated Worker: measured ~35% faster per query than the
  main-thread wa-sqlite engine on the same workload (official non-Asyncify build: aggregates
  93–120 ms vs 146–184 ms at 1M rows), SQLite off the main thread so UI keeps rendering during a
  query, and no COOP/COEP requirement (plain dedicated Worker). `sql-demo` now runs on it. Its
  read-only blocks skip BEGIN/COMMIT too, but *without* the isolation caveat: one connection plus
  the block-scoped Mutex make interleaving impossible, so the block is trivially serializable.

**Where the pooled engine still makes sense** (and what its docs must say when it ships):
persistent OPFS data that must survive reloads, *infrequent* heavy queries (hundreds of ms to
seconds each — lock handoff amortizes), and the writes-don't-block-reads property. It is not a fit
for rapid bursts of fast indexed queries — there a single connection wins on both speed and
reliability. Parked candidates from this investigation: `isolation = SERIALIZABLE` opt-in on the
pool, worker-side query timings surfaced to the app, `readerPoolSize` default 4 → 1, and a
lane/priority hint in the core API.

## Known constraints

- Kotlin/JS exports to idiomatic TypeScript poorly for a generic-heavy DSL. The target
  audience here is **Kotlin/JS authors**, not npm/TypeScript consumers; a clean `.d.ts`
  npm package is out of scope.
