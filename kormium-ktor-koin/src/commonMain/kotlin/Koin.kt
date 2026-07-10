package io.github.kormium.ktor.koin

import io.github.kormium.Catalog
import io.github.kormium.SuspendScope
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.ktor.KormiumHandle
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import io.ktor.server.application.ApplicationCall
import org.koin.core.qualifier.Qualifier
import org.koin.ktor.ext.getKoin

/**
 * Resolves the [SuspendDatabase] for catalog [G] from Koin, wrapped in a [KormiumHandle] — the chain
 * form (c): `call.kormium<AppCatalog>().transaction { ... }`.
 *
 * Note: Koin keys by `KClass`, so generics are erased — `get<SuspendDatabase<AppCatalog>>()` and
 * `get<SuspendDatabase<OtherCatalog>>()` resolve to the same key. If you run more than one catalog,
 * register and resolve them with a [qualifier]:
 * ```
 * single<SuspendDatabase<AppCatalog>>(named("app")) { createDatabase(...) }
 * // call.kormium<AppCatalog>(named("app"))
 * ```
 */
public inline fun <reified G : Catalog> ApplicationCall.kormium(qualifier: Qualifier? = null): KormiumHandle<G> =
    KormiumHandle(getKoin().get(qualifier = qualifier))

// --- (a) catalog as a TYPE argument — `call.transaction<AppCatalog, _> { ... }` ---------------
// The `_` lets the return type infer while the catalog is given explicitly as a type.
// For a named dependency use the value form (b) or `kormium<G>(qualifier).transaction { }`.

public suspend inline fun <reified G : Catalog, R> ApplicationCall.transaction(
    noinline block: suspend SuspendScope<G>.() -> R,
): R = kormium<G>().database.suspendTransaction(block = block)

public suspend inline fun <reified G : Catalog, R> ApplicationCall.autocommit(
    noinline block: suspend SuspendScope<G>.() -> R,
): R = kormium<G>().database.suspendAutocommit(block)

// --- (b) catalog as a VALUE — `call.transaction(AppCatalog) { ... }` --------------------------
// Both type parameters infer; pass a [qualifier] for a named dependency.

public suspend inline fun <reified G : Catalog, R> ApplicationCall.transaction(
    catalog: G,
    qualifier: Qualifier? = null,
    noinline block: suspend SuspendScope<G>.() -> R,
): R = kormium<G>(qualifier).database.suspendTransaction(block = block)

public suspend inline fun <reified G : Catalog, R> ApplicationCall.autocommit(
    catalog: G,
    qualifier: Qualifier? = null,
    noinline block: suspend SuspendScope<G>.() -> R,
): R = kormium<G>(qualifier).database.suspendAutocommit(block)
