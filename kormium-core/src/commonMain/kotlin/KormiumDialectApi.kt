package io.github.kormium

/**
 * Marks the seams a **dialect module** needs in order to extend the DSL: the tagged
 * scope/query-builder constructors, the `runTransaction`-style entry points, and
 * [QueryBuilderOf.rowLock]. Application code never needs them — it goes through the gated DSL
 * (e.g. [forUpdate]), which is where the compile-time capability check lives.
 *
 * Opting in bypasses that check: setting [QueryBuilderOf.rowLock] directly will happily build a
 * locking query for a backend that cannot run one, and the failure then surfaces at render time as
 * [UnsupportedByDialectException].
 */
@RequiresOptIn(
    message = "Dialect-module SPI: it bypasses the compile-time capability check that the typed " +
        "DSL provides. Application code should use the gated DSL instead.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
public annotation class KormiumDialectApi
