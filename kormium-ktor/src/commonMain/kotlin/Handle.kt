package io.github.kormium.ktor

import io.github.kormium.Catalog
import io.github.kormium.SuspendScope
import io.github.kormium.TransactionIsolation
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import kotlin.jvm.JvmInline

/**
 * An allocation-free wrapper around a resolved [SuspendDatabase] that offers terse [transaction] /
 * [autocommit] without a second type argument. It's the chain form (c) of the DI helpers:
 * `call.kormium<AppCatalog>().transaction { ... }` keeps the catalog a pure type (no value, no `_`)
 * at the cost of one extra `.kormium<G>()` hop. Built by the `kormium-ktor-di` / `kormium-ktor-koin`
 * `kormium<G>()` accessors.
 */
@JvmInline
public value class KormiumHandle<G : Catalog>(public val database: SuspendDatabase<G>)

/** Runs [block] in a transaction on the wrapped database; see [io.github.kormium.suspendTransaction]. */
public suspend fun <G : Catalog, R> KormiumHandle<G>.transaction(
    isolation: TransactionIsolation? = null,
    readOnly: Boolean = false,
    block: suspend SuspendScope<G>.() -> R,
): R = database.suspendTransaction(isolation, readOnly, block)

/** Runs [block] in autocommit on the wrapped database; see [io.github.kormium.suspendAutocommit]. */
public suspend fun <G : Catalog, R> KormiumHandle<G>.autocommit(block: suspend SuspendScope<G>.() -> R): R =
    database.suspendAutocommit(block)
