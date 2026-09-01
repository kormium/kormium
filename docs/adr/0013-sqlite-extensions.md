# ADR 0013 — SQLite extensions: a two-phase SPI, and Kormium owns no extensions

- Status: Accepted
- Date: 2026-08-30
- Amends: [ADR 0010](0010-browser-sqlite-three-engines.md) — the browser engines move from upstream
  WASM builds to Kormium's own, so that extensions work there too.

## Context

`kormium-sqlite` embeds SQLite on every platform, and nothing in its public surface can reach an
extension. A caller who wants `sqlite-vec`, `sqlean` or anything else has no entry point, and the
only pragmas that take effect are the three Kormium sets itself (`journal_mode`, `foreign_keys`,
`busy_timeout` — `CreateSqliteDatabase.native.kt:335`); anything else written into a `file:` URI is
silently ignored by SQLite on native.

`KormiumBuilder.beforeStart` is not the missing hook. It runs *after* the pool is up
(`KormiumBuilder.kt:50` — `create(config).also { beforeStart(it) }`, and the native driver opens all
`poolSize` connections in its constructor), it runs once against a single borrowed connection, and
on the JVM HikariCP retires pooled connections behind our back (`maxLifetime`). An extension is
registered per *connection*, so a once-per-database hook cannot carry it.

Measured capability per engine, from the shipped artifacts rather than from documentation:

| Engine | Loading extensions | Evidence |
| --- | --- | --- |
| JVM (sqlite-jdbc 3.53.4.0) | yes | `load_extension` present in `libsqlitejdbc.so`; `SQLiteConfig` exposes the `enable_load_extension` pragma |
| Native / iOS (vendored amalgamation) | yes | `SQLITE_OMIT_LOAD_EXTENSION` not set; `sqlite3_load_extension` in the cinterop klib; `-ldl` already in `sqlite3.def` |
| Node (better-sqlite3 13.0.3) | yes | `Database#loadExtension(file, entryPoint?)` in `lib/methods/wrappers.js` |
| Android (androidx sqlite-bundled 2.7.0) | compiled in, not exposed | `nm -D libsqliteJni.so` exports `sqlite3_load_extension`, `sqlite3_enable_load_extension`, `sqlite3_auto_extension`; `BundledSQLiteDriver` hands out no `sqlite3*` |
| Browser (wa-sqlite v1.1.2) | no, by upstream's choice | upstream `Makefile:110` compiles `-DSQLITE_OMIT_LOAD_EXTENSION` |

Extensions therefore arrive in two shapes, and an API that assumes one of them is wrong on half the
targets: **loaded at runtime** from a shared library, and **compiled into the engine**. A `String`
path describes only the first — sqlite-vec alone ships as five per-platform binaries, an npm
resolver package and a WASM build.

Kormium's position is that extensions work on every platform or on none. That rules out leaving the
browser out, and it rules out Kormium curating which extensions exist.

## Decision

### 1. A two-phase `SqliteExtension` SPI

```kotlin
public interface SqliteExtension {
    public val name: String
    /** The engines this extension can install itself on; checked before anything is installed. */
    public val supportedEngines: Set<SqliteEngine>
    /** Once per driver, before the first connection is opened. */
    public fun beforeOpen(registration: SqliteRegistrationScope) {}
    /** On each opened connection, including ones the pool recreates later. */
    public fun install(connection: SqliteConnectionScope) {}
    /** The suspend half, for engines whose SQLite cannot answer a blocking call. */
    public suspend fun suspendInstall(connection: SuspendSqliteConnectionScope) {}
}

@KormiumDsl
public interface SqliteConnectionScope {
    public val engine: SqliteEngine
    public fun loadLibrary(path: String, entryPoint: String? = null)
    public fun exec(sql: String)
    public fun queryScalar(sql: String): String?
}

@KormiumDsl
public interface SuspendSqliteConnectionScope { /* the same three, suspend */ }

@KormiumDsl
public interface SqliteRegistrationScope {
    public val engine: SqliteEngine
    public fun registerLibrary(path: String, entryPoint: String)
}

public enum class SqliteEngine { Xerial, Native, AndroidX, BetterSqlite3, WaSqlite, SqliteWasm }
```

Both phases have defaults; an extension overrides the one its platform needs. Each scope reports
its `engine`, which is what lets one extension object pick between loading itself per connection and
registering itself process-wide.

**The install phase comes in a blocking and a suspend half**, because the browser engines cannot
offer a blocking one: wa-sqlite's IndexedDB VFS is asynchronous (Asyncify) and the Worker engines
are reached by `postMessage`, so in both the answer arrives after a blocking call would have had to
return. Making the whole SPI suspend instead was rejected: the native driver opens its entire pool
in its constructor, where a suspend call is impossible, and `runBlocking` does not exist on JS — one
platform's asynchrony would have poisoned the four synchronous engines. Kormium already splits
blocking and suspend surfaces everywhere else (`Database`/`SuspendDatabase`, `Scope`/`SuspendScope`,
`SqlExecutor`/`SuspendSqlExecutor`), so the SPI mirrors an established shape rather than inventing
one.

`supportedEngines` exists because that split has a failure mode: an extension written only for
servers, used in a browser app, would leave `suspendInstall` at its no-op default, install nothing,
and surface as `no such module: vec0` on the first query. The driver checks the set before touching
any connection, so the real error — "this package was never built for your platform" — is reported
at `createSqliteDatabase`, by name. It has no default for the same reason.

`loadLibrary` enables the **C API only** — on native `sqlite3_db_config(db,
SQLITE_DBCONFIG_ENABLE_LOAD_EXTENSION, ...)`, not `sqlite3_enable_load_extension`, which would also
arm the `load_extension()` SQL function and let any query in the application load arbitrary code.

### 2. A `sqlite { }` block on the builder

```kotlin
val db = createSqliteDatabase("app.db", poolSize = 4) {
    sqlite {
        extension(SqliteVec)
        pragma("cache_size", "-64000")
        onConnection { exec("…") }   // @DelicateKormiumApi
    }
    beforeStart { migrate(appMigrations) }
}
```

`SqliteBuilder : KormiumBuilder` (which becomes `open`), carrying a `SqliteOptions` that is also a
defaulted parameter of the `expect fun createSqliteDatabase`. `@KormiumDsl` on the block, so an
`onConnection { }` body cannot implicitly reach `extension(...)` or `beforeStart(...)` of the
enclosing builder.

Ordering is Kormium's defaults first, minus any pragma the caller declared (the rule already applied
to URI parameters), then the caller's steps in declaration order.

### 3. The SPI lives in a new all-targets module, `kormium-sqlite-spi`

The engine modules do not share a target set: `kormium-sqlite` is jvm/android/native,
`kormium-sqlite-node` and `-wasm` are wasmJs-only, `kormium-sqlite-js` is js-only. An extension
package that wants one `SqliteVec` object in `commonMain` therefore needs a dependency that exists
on every target. `kormium-sqlite-dialect` happens to be one, but its version would then be a
compatibility contract for the whole extension ecosystem — welded to a module that keeps changing as
dialects evolve. A separate three-file module keeps that contract small and stable, per
[ADR 0001](0001-standalone-dialect-modules.md)'s precedent of splitting out what has to compile
everywhere.

### 4. Kormium ships no extensions, and no curated set of them

Extension packages (`kormium-sqlite-vec` and friends) live in their own repository with their own
release cadence, and third parties can publish their own on the same SPI. Kormium provides the SPI,
the SQLite in the process, and the headers to compile against — nothing else. Being the gatekeeper
of which extensions exist is a position the project should not take, and a curated bundle is exactly
that position wearing a convenience costume.

### 5. Per-engine mechanism

| Engine | Mechanism | Phase |
| --- | --- | --- |
| JVM | `enable_load_extension` + `load_extension()`, per connection through a wrapping `DataSource` (Hikari's `connectionInitSql` takes one statement and no Kotlin) | `install` |
| Native / iOS | the extension package ships its own K/N static library and calls `sqlite3_auto_extension` | `beforeOpen` |
| Node | `db.loadExtension(path)`; the npm package resolves the platform binary and Kotlin propagates the npm dependency transitively | `install` |
| Android | a Kormium-supplied JNI shim calls the exported `sqlite3_auto_extension` in `libsqliteJni.so`; the extension package ships only its `.so` per ABI | `beforeOpen` |
| Browser | Kormium's own WASM build, with the extension as an Emscripten side module loaded by `dlopen` | `suspendInstall` |

The Android shim is **extension-agnostic** — `registerLibrary(path, entryPoint)` — so Kormium
writes it once and extension authors ship no C at all, only their `.so` per ABI. This works because
SQLite passes the API routines table to auto-registered entry points (`sqlite3.c:144094`:
`xInit(db, &zErrmsg, pThunk)` with `pThunk = &sqlite3Apis`), so an Android extension can be an
ordinary loadable `.so` needing no direct `sqlite3_*` symbol resolution.

It ships as a separate module, **`kormium-sqlite-android-ext`**, because AGP's Kotlin-Multiplatform
library plugin — the one `kormium-sqlite` uses — has no NDK support whatsoever: its DSL
(`KotlinMultiplatformAndroidLibraryExtension`, AGP 9.3.1) exposes no `externalNativeBuild`, no
`cmake` and no `ndk`. The classic `com.android.library` plugin does, so the ~40 lines of C are built
there and consumed by `kormium-sqlite`'s android target. No Kotlin lives in that module: the
`external fun` is declared in `kormium-sqlite` and JNI resolves it by name at runtime, so the two
halves need not share an artifact.

Because registration is process-global and must precede the pool, it cannot go through
`install(connection)`. `beforeOpen` therefore takes a `SqliteRegistrationScope` — mirroring
`install`'s `SqliteConnectionScope` — whose `registerLibrary` is implemented on Android and refused
elsewhere, either because the engine loads per connection (JVM, Node, browser) or because a native
package registers its own statically linked entry point (Kotlin/Native).

### 6. Native extensions bring only themselves

An extension package compiles its C sources with `-DSQLITE_CORE`, archives them into its **own**
static library containing no SQLite, and exposes a registration call through its own cinterop:

```c
---
#include "sqlite3.h"
int sqlite3_vec_init(sqlite3*, char**, const void*);
static int kormium_register_vec(void) {
    return sqlite3_auto_extension((void(*)(void)) sqlite3_vec_init);
}
```

The unresolved `sqlite3_*` symbols in that archive are resolved at final link against the
`libsqlite3.a` already embedded in `kormium-sqlite`'s cinterop klib. Kormium's native artifacts stay
self-contained, no consumer-side Gradle plugin is involved, and extensions compose additively.

Kormium additionally publishes `sqlite3.h` **and `sqlite3ext.h`** as an artifact so extension
authors compile against the exact SQLite the driver links.

### 7. Browser: Kormium owns the WASM builds, and publishes two of them

The browser is the one platform where nothing can be linked into the module after the fact, so
extensions must be loadable at runtime — which means Emscripten dynamic linking. Upstream's builds
deliberately forbid it, so Kormium builds its own from the upstream sources.

- The engine is built as `MAIN_MODULE=1`; extension packages ship Emscripten `SIDE_MODULE` binaries,
  loaded through the ordinary `sqlite3_load_extension` path.
- `MAIN_MODULE=1` rather than `=2` on purpose. Mode 2 keeps dead-code elimination but requires the
  engine to export a superset of whatever libc symbols any future third-party extension imports — a
  prediction Kormium would have to make on behalf of authors it does not know, which contradicts
  decision 4. Mode 1 exports everything and needs no prediction. It also sets `LINKABLE=1`
  (`tools/link.py:1298`), which keeps `-Oz` usable; mode 2 at `-Os`/`-Oz` silently drops the
  `__stack_pointer` export and side modules then fail to instantiate.
- **Two builds are published**, plain and extensible, and the engine module is injectable: the
  factories take a `SqliteWasmEngine` / `SqliteJsEngine` instead of binding a `@JsModule` at compile
  time, so a caller can point Kormium at any build — Kormium's extensible one, or a third party's.
  A caller who declares no extension keeps today's payload byte for byte and pays nothing.

This is not a fork: wa-sqlite's `Makefile` already parameterizes what is needed (`CFILES_EXTRA`,
`EMFLAGS_EXTRA`, and `WASQLITE_DEFINES` is a plain `=` assignment overridable from the command
line), so a build repository that checks out the upstream tag is enough.

## The spike

Both mechanisms were built and run before this ADR was written, rather than reasoned about.

### Native (linuxX64)

Two independent Gradle modules — separate klib, cinterop and static library each — plus
`kormium-sqlite`, linked into one test binary.

```
[spike] before registration: no such function: vec_version / no such function: uuid
[spike] vec=v0.1.9  uuid=dd02ef56-…-5c67c405c9fc  roundtrip=e4fe094c-…-6b4211c558e4
[spike] join vec0 + uuid: uid=e5da357d-…-716d04f15827  distance=0.141421377658844
```

The last line is a KNN query over a `vec0` virtual table joined to an ordinary table whose keys came
from `uuid()` — two extensions from two packages in one statement. In the linked binary,
`sqlite3_open_v2`, `sqlite3_prepare_v2`, `sqlite3_initialize`, `sqlite3_auto_extension`,
`sqlite3_vec_init` and `sqlite3_uuid_init` each have exactly one definition and zero `sqlite3_*`
symbols are unresolved: one SQLite, no duplicates. `libvec.a` is 200 KB, `libuuid.a` is 4 KB — cost
is proportional to the extension. `poolSize = 3` confirmed every pooled connection sees both.

### Browser (Emscripten 6.0.8, under Node and in Chrome)

The vendored 3.53.4 amalgamation built as a main module with wa-sqlite's own define set; sqlite-vec
and `ext/misc/uuid.c` built as side modules and `dlopen`ed at runtime.

```
[a] before load: no such function: vec_version / no such function: uuid
[b] dlopen vec: OK   dlopen uuid: OK
[c] vec_version(): v0.1.9
[d] both at once: v0.1.9 | 610d7f8a-388d-474f-b848-78511ecbc1af
[e] KNN: 1 d=0.099999994039535523
```

| Build | Bytes | Loads extensions |
| --- | ---: | --- |
| baseline `-Oz` (what wa-sqlite ships today) | 564,725 | — |
| `MAIN_MODULE=2 -Oz` | 591,090 | no — `__stack_pointer` stripped |
| `MAIN_MODULE=2 -O2`, curated export list | 1,048,730 | yes |
| **`MAIN_MODULE=1 -Oz` (chosen)** | **1,148,166** | **yes** |
| `MAIN_MODULE=1 -O2` | 1,586,959 | yes |
| `vec.so` / `uuid.so` | 83,824 / 1,291 | |

The extensible browser engine is roughly **twice** the plain one. That is the price of the
all-or-nothing position, and it is paid only by callers who ask for an extension, because both
builds are published.

A useful detail for authors: `vec.so` imports 16 libc functions from the engine and **no**
`sqlite3_*` symbols at all, because a loadable-form extension goes through the API routines table.
Side modules are therefore nearly free-standing.

The same page was then run **on a browser main thread** (headless Chrome for Testing 151), with the
side modules fetched over HTTP and written into Emscripten's virtual filesystem, and it behaved
identically — `dlopen` of the 84 KB `vec.so` succeeded synchronously and the KNN query returned.
This was the open question about `createSqliteWasmDatabase`: SQLite's `dlopen` is synchronous, and
browsers refuse synchronous compilation of large modules on the main thread. Empirically that limit
does not bite here, so no `ASYNCIFY` or startup preloading is required. Firefox and Safari have not
been checked.

The real wa-sqlite builds followed, in
[kormium/sqlite-wasm-engines](https://github.com/kormium/sqlite-wasm-engines): `sqlite-vec` loads at
runtime into **both** the synchronous and the Asyncify flavours, creating a `vec0` virtual table and
answering a KNN query. Since Asyncify is what backs wa-sqlite's IndexedDB VFS, persistence and
runtime extensions are available together — an earlier reading of a failure there, blaming Asyncify,
was wrong.

That failure is worth recording, because it is a trap for anyone rebuilding SQLite for this.
`sqlite3ext.h` switches on `#if !defined(SQLITE_CORE) && !defined(SQLITE_OMIT_LOAD_EXTENSION)`, and
wa-sqlite compiles SQLite's contributed `extension-functions.c`, which declares *itself* a loadable
module. Upstream's `SQLITE_OMIT_LOAD_EXTENSION` was quietly what kept that file on the
statically-linked path; removing the flag — the whole point of an extension-capable build — flips it
to loadable-module mode, where `sqlite3_api` is null and every call through it traps, surfacing far
from the cause. `-DSQLITE_CORE` pins the static path regardless. Emscripten's dynamic linking,
Asyncify, LTO, PIC, the export lists and SQLite's own version were each built and ruled out before
that was found.

### Findings that shaped the decisions

- **`sqlite3_auto_extension` is process-global, not per-database.** Registering an extension for one
  `createSqliteDatabase` gives it to every SQLite connection opened afterwards in the process,
  including databases that never declared it. This surfaced as a test failure when a second test
  class registered first. The API reads as per-database and the mechanism is not; document it.
  `sqlite3.h:7633` settles two adjacent worries: re-registering the same entry point is "a harmless
  no-op", and an entry point that returns an error fails the `sqlite3_open_v2` that provoked it — so
  a broken extension fails at database open, not at first query.
- **`sqlite3ext.h` is required even for `-DSQLITE_CORE` builds**, because `ext/misc` sources include
  it unconditionally (it is what turns `SQLITE_EXTENSION_INIT1` into a no-op). Only `sqlite3.h` and
  `sqlite3.c` are vendored today, so an author has to source it separately and may take a mismatched
  version. Hence the artifact in decision 6.

Unrelated but found on the way: `run_konan` swallows `-DFOO=1` arguments on **Linux** too, not only
on Windows as `kormium-sqlite/build.gradle.kts:84-87` states (the JVM launcher takes them for system
properties). The build is unaffected — it already routes everything through a response file — but
the comment is misleading and should be corrected.

## Consequences

Positive:

- Extensions work on every platform Kormium supports, and they compose everywhere: no extension
  package carries a SQLite of its own, so there is no combinatorial artifact explosion.
- For the caller, two actions on JVM, native, iOS, Android and Node: add a dependency, name it in
  `sqlite { }`. Kotlin propagates npm dependencies transitively, so Node users install nothing by
  hand. In the browser there is a third: select the extensible engine build.
- Callers who use no extensions are unaffected — same artifacts, same browser payload, same build
  time. Nothing is compiled on the consumer's machine on any platform.
- Kormium's native artifacts keep the self-containment chosen in `build.gradle.kts:29-35` (the
  glibc-2.19 sysroot constraint); the existing cinterop packaging does not change.
- `pragma()` and `onConnection { }` land as a side effect, closing a gap that has nothing to do with
  extensions: today only three pragmas take effect on native.

Negative / costs:

- **Kormium takes ownership of two browser WASM builds** (wa-sqlite and `@sqlite.org/sqlite-wasm`,
  since ADR 0010's three engines stand on both), where it previously consumed upstream artifacts.
  That means Emscripten in CI and tracking two upstreams' releases — the single largest ongoing cost
  in this ADR.
- The extensible browser engine is about twice the size of the plain one. Mitigated by publishing
  both, but a caller who wants one small extension still pays the full main-module overhead.
- Extension authors need the Kotlin/Native toolchain for the native static libraries and Emscripten
  for the side modules. An *author-side* Gradle plugin wrapping the existing `compileSqlite3*` /
  `archiveSqlite3*` tasks would fix the first; its blast radius is authors' builds, not consumer
  applications, which is the right place for that fragility.
- The JVM driver gains a wrapping `DataSource` in place of a plain `jdbcUrl` — a real change to
  `JdbcDatabase`, which is shared by **Postgres and MySQL** as well. It also fixes the pre-existing
  gap where a recycled Hikari connection silently loses whatever was done to it at startup.
- Kormium starts shipping native code for Android: `kormium-sqlite-android-ext`, ~5.5 KB per ABI,
  which puts the NDK and CMake into the build. It is a separate published artifact only because
  AGP's KMP library plugin cannot build native code at all.
- Process-global registration means the API's per-database appearance is a simplification.
- `SqliteOptions` as a new parameter of an `expect` function touches all four actuals and every api
  dump.

## To verify during implementation

- Firefox and Safari for main-thread side-module loading. Chrome is verified (see the spike); the
  synchronous-compilation limit is engine-specific, so the other two need the same page run against
  them before the browser work is called done.
- Wiring `loadLibrary` into the browser scope. The engine side is done — the build exists, the
  module is injectable, and `FS` and `sqlite3_enable_load_extension` are exported for it — but
  `WasmSqliteConnectionScope.loadLibrary` still throws.
- Apple targets and mingw for the native mechanism — identical mechanism, different linking.
- The Android JNI shim end to end on a device.

## Alternatives considered

- **Leaving the browser non-additive** — make the engine injectable, publish no builds, and let
  whoever needs `vec + uuid` build that combination themselves. Rejected: it makes browser support a
  per-combination artifact and contradicts the all-or-nothing position, in exchange for avoiding the
  WASM build ownership.
- **`MAIN_MODULE=2` with a curated libc export list** (1,048,730 bytes, ~9% smaller than the chosen
  build). Rejected: see decision 7 — it requires predicting third-party extensions' imports.
- **A consumer-side Gradle plugin that compiles SQLite plus the chosen extensions in the
  application's build.** Rejected: the spike shows it is unnecessary, and it would move the
  `run_konan` discovery, the `KONAN_DATA_DIR` layout and the response-file workaround into every
  consumer's build, coupled to KGP versions.
- **Un-embedding `libsqlite3.a` from the cinterop klib, resolving it at application link time.**
  Rejected for the same reason, plus it would break self-containment for every native consumer to
  serve a minority, and it is an either/or: with the archive embedded a second one cannot be
  supplied.
- **A curated bundle of popular extensions maintained by Kormium.** Rejected: it makes the project
  the gatekeeper of which extensions exist, creates a permanent maintenance queue, and still leaves
  third-party extensions unshippable.
- **Forking wa-sqlite.** Rejected: upstream's `Makefile` already parameterizes what is needed, so a
  fork would buy a permanent merge debt for something already supported.
- **Putting the SPI in `kormium-sqlite-dialect`.** Rejected, narrowly — it is already all-targets
  and already a dependency of all four engine modules, so this is the cheaper option. See decision 3
  for why contract stability wins.
- **`extension("/path/to/vec0.so")` — a path string in the builder.** Rejected: it is a
  JVM/desktop/Node API wearing a cross-platform hat; the portable unit is an object that knows how
  to install itself, not a path.
