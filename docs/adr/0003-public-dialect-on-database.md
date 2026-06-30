# ADR 0003 — `dialect` is a public member of `Database` / `SuspendDatabase`

- Status: Accepted
- Date: 2026-06-30

## Context

The SQL [Dialect] (placeholder style, identifier quoting, LIMIT/OFFSET, upsert tail, …) was a
private detail of each backend driver: drivers held it as `private val dialect` and wired it into
the executor that renders SQL. It was private for three reasons, all correct at the time:

1. **Minimal SPI surface.** `Database` is the extension point backends implement; it exposed only
   what a *consumer* needs (`config`, `writeListeners`, `usePinned`, `close`). The fewer required
   members, the easier a new driver is to write.
2. **No consumer use case.** A user never renders SQL themselves — the DSL + scope + executor do it
   internally. There was nothing the dialect needed to be exposed *for*.
3. **Consistency with [ADR 0001].** Concrete dialects are pluggable internals wired into drivers;
   core knows only the `Dialect` abstraction.

[renderSql] (rendering a query to its SQL without a connection) is the first feature that needs the
dialect from the outside — both the offline `renderSql(catalog, dialect) { }` and the convenience
`Database.renderSql { }`, which should preview SQL for *that* backend.

## Decision

Make `val dialect: Dialect` a public member of both `Database` and `SuspendDatabase`, with a neutral
`StandardDialect` default (mirroring how `config` / `writeListeners` are defaulted members), and
override it in the shipped drivers with their concrete dialect. The driver *interfaces*
(`SqliteDriver` / `PostgresDriver` / `MySqlDriver`), which extend both `Database` and
`SuspendDatabase`, re-declare `override val dialect` to resolve the inherited-default diamond — as
they already do for `config` / `writeListeners` / `isClosed`.

Only the **dialect** is exposed. The rendered SQL string depends on the dialect (and the value
types), not on the `TypeMapper` — in `ParamBuilder.bind`, the type mapper only converts the stored
parameter value, while `renderBind` sees the original value. So `typeMapper` stays internal, and
`renderSql` uses `StandardTypeMapper` for the displayed parameter values.

Being pre-1.0 is what makes this the right time: there are no released consumers, and the only
out-of-tree implementor (the `kormium/pglite` engine) is also pre-release and in the same org.

## Consequences

Positive:

- Enables `renderSql` / `db.renderSql`, and gives future tooling (query `explain`, an MCP server,
  debugging) a first-class way to ask "what dialect does this database speak".
- Formalizes something every SQL backend already has; it is a stable, static, cheap property.
- Per-table `*Sql` builders and `buildSelect` became `internal` (from `private`) so the render scope
  reuses the **exact** statement builders execution uses — the preview cannot drift from reality.

Negative / costs:

- It widens the `Database` / `SuspendDatabase` SPI: a custom implementation that already had a
  member named `dialect` must now `override` it, and one that implements both interfaces must
  resolve the diamond. For shipped drivers this is a one-line change; the out-of-tree pglite engine
  adapts on its next update.
- `db.renderSql` is offered only on `Database<G>` (not `SuspendDatabase<G>`) to avoid an overload
  ambiguity on drivers that implement both. A suspend-only backend (r2dbc) renders with the offline
  form, passing `db.dialect`: `renderSql(App, db.dialect) { ... }`.

## Alternatives considered

- **Keep `dialect` private; open a connection in `db.renderSql` to read it off the executor.**
  Rejected: opening a real (network) connection just to *render* SQL contradicts the feature, and
  fails when the database is unreachable.
- **A `HasDialect` marker interface drivers opt into.** Rejected: a dialect is universal to every
  SQL backend, so it belongs on the base interface, not an optional capability — and pre-1.0 makes
  the direct member clean rather than a compatibility workaround.
- **Ship only the offline `renderSql(dialect) { }`, no `db.renderSql`.** Considered while the change
  looked like a broad SPI break; reconsidered once pre-1.0 and the dialect-only scope made exposing
  it clean.

## Notes

The web engines' database classes (`SqliteWasmDatabase`, `PgDatabase`, `MySqlDatabase`) implement
only `SuspendDatabase`, so they compile unchanged on the `StandardDialect` default; overriding them
with their real dialect (so `db.dialect` is exact on the web stack) is a small follow-up.

[Dialect]: ../design.md
[ADR 0001]: 0001-standalone-dialect-modules.md
[renderSql]: ../api-cookbook.md
