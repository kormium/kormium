package io.github.kormium.ktor.di

import io.github.kormium.Catalog
import io.github.kormium.SuspendScope
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.ktor.KormiumHandle
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve

/**
 * Resolves the [SuspendDatabase] for catalog [G] from Ktor's built-in DI, wrapped in a
 * [KormiumHandle]. Register it with the matching parameterized type so the catalog is part of the
 * key (Ktor DI keys by full type, so distinct catalogs don't collide):
 * ```
 * dependencies { provide<SuspendDatabase<AppCatalog>> { createDatabase(...) } }
 * ```
 * This is the chain form (c): `call.kormium<AppCatalog>().transaction { ... }`.
 */
public suspend inline fun <reified G : Catalog> ApplicationCall.kormium(): KormiumHandle<G> =
    KormiumHandle(application.dependencies.resolve<SuspendDatabase<G>>())

// --- (a) catalog as a TYPE argument — `call.transaction<AppCatalog, _> { ... }` ---------------
// The `_` lets the return type infer while the catalog is given explicitly as a type.

public suspend inline fun <reified G : Catalog, R> ApplicationCall.transaction(
    noinline block: suspend SuspendScope<G>.() -> R,
): R = kormium<G>().database.suspendTransaction(block = block)

public suspend inline fun <reified G : Catalog, R> ApplicationCall.autocommit(
    noinline block: suspend SuspendScope<G>.() -> R,
): R = kormium<G>().database.suspendAutocommit(block)

// --- (b) catalog as a VALUE — `call.transaction(AppCatalog) { ... }` --------------------------
// Both type parameters infer (G from the catalog argument, R from the block); no `_` needed.

public suspend inline fun <reified G : Catalog, R> ApplicationCall.transaction(
    catalog: G,
    noinline block: suspend SuspendScope<G>.() -> R,
): R = kormium<G>().database.suspendTransaction(block = block)

public suspend inline fun <reified G : Catalog, R> ApplicationCall.autocommit(
    catalog: G,
    noinline block: suspend SuspendScope<G>.() -> R,
): R = kormium<G>().database.suspendAutocommit(block)
