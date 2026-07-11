package io.github.kormium

/**
 * Marks raw-SQL escape hatches: [RawExpression] and the `execute`/`executeUpdate`/`execSql`
 * members of [Scope]/[SuspendScope]. These bypass the typed DSL — you are responsible for
 * parameterizing values (never concatenate untrusted input into the SQL text) and, for
 * `execute`/`executeUpdate`, for declaring which tables a write touches via `invalidates` so
 * `kormium-observe` sees it. Opt in with `@OptIn(DelicateKormiumApi::class)` at the call site.
 */
@RequiresOptIn(
    message = "Raw SQL bypasses Kormium's typed DSL. You are responsible for parameterization " +
        "(never concatenate untrusted input) and, for execute/executeUpdate, for declaring which " +
        "tables a write touches via `invalidates`.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
public annotation class DelicateKormiumApi
