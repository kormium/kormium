package io.github.kormium.ktor

import io.github.kormium.Catalog
import io.github.kormium.SuspendScope
import io.github.kormium.TransactionIsolation
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.suspendAutocommit
import io.github.kormium.suspendTransaction
import io.ktor.server.application.ApplicationCall

/**
 * Runs [block] in a transaction on [db] (BEGIN/COMMIT, ROLLBACK on throw) without blocking the
 * worker — [block] is itself `suspend` and may suspend while the connection stays pinned. Works
 * with any backend's [SuspendDatabase] (offload JDBC/libpq, or truly-async r2dbc).
 *
 * This overload is DI-agnostic: you pass the database explicitly, so it works with any way of
 * obtaining it — Koin (`call.transaction(call.get()) { ... }`), Ktor's built-in DI, or a plain
 * reference. The `kormium-ktor-di` / `kormium-ktor-koin` artifacts add reified overloads that resolve
 * the database for you.
 */
public suspend fun <G : Catalog, R> ApplicationCall.transaction(
    db: SuspendDatabase<G>,
    isolation: TransactionIsolation? = null,
    readOnly: Boolean = false,
    block: suspend SuspendScope<G>.() -> R,
): R = db.suspendTransaction(isolation, readOnly, block)

/**
 * Runs [block] on [db] in autocommit (no surrounding transaction) — the cheap path for reads /
 * single statements. See [transaction] for the DI-agnostic rationale.
 */
public suspend fun <G : Catalog, R> ApplicationCall.autocommit(
    db: SuspendDatabase<G>,
    block: suspend SuspendScope<G>.() -> R,
): R = db.suspendAutocommit(block)
