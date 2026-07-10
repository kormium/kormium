# ADR 0010 — Browser SQLite ships as three engines; OPFS reader pool is experimental

- Status: Accepted
- Date: 2026-07-05

## Context

`kormium-sqlite-wasm` began as one browser SQLite engine, `createSqliteWasmDatabase`, on
[wa-sqlite](https://github.com/rhashimoto/wa-sqlite): a single connection on the **main thread**,
`:memory:` or IndexedDB-persisted. Two limits pushed for more. (1) SQLite executes on the main
thread, so a multi-hundred-ms query freezes UI rendering while it runs. (2) There is no concurrency
— every statement serializes through one `Mutex`. This surfaced concretely in `kormium/playground`'s
`sql-demo`: independent read-only aggregates over a 1M-row dataset could not overlap, and the tab
janked during each.

The `docs/web-targets.md` Phase 4 plan proposed one fix: an opt-in reader **pool** so analytical
dashboards get real concurrency, matching the pooled JVM/Node backends. Investigating it before
committing turned up that our `wa-sqlite` dependency was npm `1.0.0` (January 2024, unmaintained),
whose example VFS explicitly does not support multiple connections — so the pool had to be built on
a different foundation. That became the standalone
[`kormium/sqlite-wasm-kt`](https://github.com/kormium/sqlite-wasm-kt) library over the officially
maintained `@sqlite.org/sqlite-wasm`, using its `opfs-wl` VFS (SQLite 3.53.0+) for
multi-connection OPFS access, plus a companion `@kormium/sqlite-wasm-worker` bundle so a `Worker`
can host a connection without the consuming app needing its own Kotlin/Wasm executable target.

Then the pool was measured against the workload that motivated it, and the result inverted the
plan's premise.

## Decision

Ship **three** browser SQLite engines, not one configurable engine, and mark the pooled one
**experimental**:

| Factory | Host | Storage | Concurrency | Needs COOP/COEP |
| --- | --- | --- | --- | --- |
| `createSqliteWasmDatabase(dataDir?)` | main thread (wa-sqlite) | `:memory:` or IndexedDB | single connection | no |
| `createWorkerSqliteWasmDatabase()` | dedicated Worker | `:memory:` | single connection | no |
| `createPooledSqliteWasmDatabase(opfsPath, readerPoolSize)` | Worker pool | OPFS (`opfs-wl`) | 1 writer + N readers | **yes** |

- **`createWorkerSqliteWasmDatabase` is the recommended default** for data that fits in memory and
  need not survive a reload. Same single-connection/`Mutex` model and memory speed as the original
  engine, but SQLite runs off the main thread (UI keeps rendering during a query) and it measured
  ~35% faster per query than main-thread wa-sqlite on the same workload (the official non-Asyncify
  build). No COOP/COEP requirement.
- **`createPooledSqliteWasmDatabase` is experimental, with a narrow niche:** persistent OPFS data
  that must survive reloads, *infrequent* heavy queries (hundreds of ms to seconds each), and the
  writes-don't-block-reads property. It is **not** the default and **not** for bursts of fast
  indexed queries.
- **`createSqliteWasmDatabase` stays** as the only engine offering IndexedDB persistence without
  COOP/COEP, and to avoid breaking existing callers.

## Consequences

Positive:

- Each engine is a clear, honest point on the trade-off surface, named at the factory. There is no
  single engine whose behavior silently changes with a flag, and no default that is wrong for most
  callers (the reader pool was measured to be exactly that — see below).
- The recommended default (`createWorkerSqliteWasmDatabase`) is strictly better than what shipped
  before it on the common case: faster *and* it stops freezing the UI.
- The pooled engine's real capability (concurrent readers on persistent OPFS data) is available to
  the workloads it actually helps, gated behind an explicit factory and an experimental label.
- The infrastructure built for the pool (the standalone `sqlite-wasm-kt` library, the worker RPC
  bundle, the `PRAGMA cache_size`/`temp_store` and no-trace fixes) is reused by the worker-hosted
  engine, so it is not wasted even though the pool itself is niche.

Negative / costs:

- Three factories to explain and document instead of one — mitigated by the table above and the
  per-engine guidance in `backends.md`.
- Two underlying WASM SQLite builds now ship in one module: wa-sqlite (for
  `createSqliteWasmDatabase`) and `@sqlite.org/sqlite-wasm` via `sqlite-wasm-kt` (for the other
  two). A consumer that only uses one engine still pays install/lockfile weight for both until
  Kotlin/Wasm dead-code elimination and separate artifacts are revisited.
- The pooled engine adds a real hosting constraint (COOP/COEP response headers, confirmed
  empirically — `OpfsWlDb` silently fails to construct without cross-origin isolation) and a
  consumer-side webpack config addition. Both are documented; neither applies to the other two
  engines.

## Why the reader pool did not become the default (the measurements)

The Phase 4 plan assumed pooling would speed up the dashboard. After three fixes took a single
1M-row indexed aggregate from ~1.7 s to ~100–125 ms — the wa-sqlite-era `'ct'` open flags had
enabled per-statement SQL tracing to `console.log` (`t` = trace); SQLite's ~2 MB default page cache
forced re-reading hot index pages through OPFS (now `PRAGMA cache_size=-32768` + `temp_store=MEMORY`
per connection); and the BEGIN/COMMIT wrap around every read-only block cost two extra `postMessage`
round trips — the premise inverted:

- With queries that fast, `opfs-wl`'s per-statement lock handoff between connections (Web Locks +
  `Atomics.waitAsync`; OPFS sync access handles are exclusive by spec) **dominates**. The same
  4-query dashboard burst measured ~410 ms wall on **one** reader vs ~1030 ms on **four**. This
  matches upstream wa-sqlite findings (~60k tps single connection → ~1.5k with two).
- Under rapid repeated bursts (dragging a filter slider) multi-connection lock acquisition **fails
  outright** — `xLock() GetSyncHandleError` after `opfs-wl`'s retries, because cancelled coroutines
  do not cancel requests already queued on the Workers.
- The workload that motivated the phase was best served by the opposite of a pool: one in-memory
  connection at memory speed with zero lock traffic. `sql-demo` runs on
  `createWorkerSqliteWasmDatabase`.

So reader pooling is a real capability for a real niche, but it is the wrong default — hence
experimental and opt-in, not the engine everyone gets.

## Alternatives considered

- **One engine, `createSqliteWasmDatabase(readerPool = N)`, pool as a flag on the existing factory
  (the original plan sketch).** Rejected: the storage backend, hosting requirement (COOP/COEP), and
  underlying WASM build all differ between the modes — a single factory whose fundamental
  characteristics flip on an integer argument hides exactly the trade-offs a caller needs to see,
  and would have made the measured-bad pool configuration reachable by default-ish call sites.
- **Make the pool the default and tune it** (adaptive pool size, backoff on `GetSyncHandleError`).
  Rejected for now: the measurements show a single connection wins on both speed and reliability for
  the common (fast-query) case, so no amount of pool tuning makes it the right default; tuning is
  parked for the niche where the pool is chosen deliberately.
- **Drop the pool entirely, ship only the worker-hosted in-memory engine.** Rejected: concurrent
  readers on persistent OPFS data is a genuine capability with no other engine covering it, and the
  infrastructure already exists. Experimental-and-opt-in keeps it available without making it a
  promise.
- **Keep everything on wa-sqlite.** Rejected: the npm package is unmaintained since January 2024 and
  its multi-connection VFS is example code its own README flags as not for production; the official
  `@sqlite.org/sqlite-wasm` is maintained in lockstep with SQLite releases.

## Notes

The standalone `kormium/sqlite-wasm-kt` library and its `@kormium/sqlite-wasm-worker` npm bundle are
published (Maven Central / npm, 0.1.0); `kormium-sqlite-wasm` consumes them as ordinary
dependencies. The parked follow-ups from the pool investigation
(`isolation = SERIALIZABLE` opt-in restoring the transaction on the pool's fast read path,
worker-side query timings surfaced to the app, `readerPoolSize` default 4 → 1, a lane/priority hint
in the core API) are recorded in `docs/web-targets.md` Phase 4. Related: [[korm-web-targets]]
[[kormium-sqlite-wasm-kt-project]].
