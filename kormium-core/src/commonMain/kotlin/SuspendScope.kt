package io.github.kormium

import io.github.kormium.database.SuspendDatabase
import io.github.kormium.resultset.ResultSet
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * The suspend counterpart of [Scope], the receiver inside a [suspendTransaction] /
 * [suspendAutocommit] block. It pins one connection (via a [SuspendSqlExecutor]) and
 * exposes the same table operations as [Scope], constrained to `Table<G, _>`, but as
 * `suspend` functions — so the block may itself suspend (call other suspend code) while
 * the connection stays pinned. Raw SQL run through [execute] / [executeUpdate] goes to
 * the same pinned connection.
 */
@KormiumDsl
class SuspendScope<G : Catalog> internal constructor(
    private val exec: SuspendSqlExecutor,
    /** The owning database's configuration (e.g. the default [BatchInsertMode]). */
    internal val config: KormiumConfig = KormiumConfig(),
    /** Tables written during this scope; see [Scope.dirtyTables]. */
    internal val dirtyTables: MutableSet<String> = mutableSetOf(),
    /** Whether this scope runs inside a transaction (see [savepoint]). */
    private val transactional: Boolean = true,
) {
    private var savepointCounter = 0

    private fun Table<G, *>.markWritten() {
        dirtyTables.add(tableName)
    }

    /** Inserts [entity]; see [Scope.insert]. */
    suspend fun <T : Entity> Table<G, T>.insert(entity: T, returning: Boolean = false): T? {
        markWritten()
        return insert(entity, exec, returning)
    }

    /** Inserts all [entities] in one statement; see [Scope.insertAll]. */
    suspend fun <T : Entity> Table<G, T>.insertAll(
        entities: List<T>,
        returning: Boolean = false,
        batchInsertMode: BatchInsertMode = config.batchInsertMode,
    ): List<T> {
        markWritten()
        return insertAll(entities, exec, returning, batchInsertMode)
    }

    /** Insert-or-update on a single-column conflict target; see [Scope.upsert]. */
    suspend fun <T : Entity> Table<G, T>.upsert(entity: T, onConflict: Column<*, *, T>, update: T, returning: Boolean = false): T? {
        markWritten()
        return upsert(entity, listOf(onConflict), update, exec, returning)
    }

    /** Insert-or-update on a composite conflict target; see [Scope.upsert]. */
    suspend fun <T : Entity> Table<G, T>.upsert(entity: T, onConflict: List<Column<*, *, T>>, update: T, returning: Boolean = false): T? {
        markWritten()
        return upsert(entity, onConflict, update, exec, returning)
    }

    /** Insert-or-do-nothing on a single-column conflict target; see [Scope.insertOrIgnore]. */
    suspend fun <T : Entity> Table<G, T>.insertOrIgnore(entity: T, onConflict: Column<*, *, T>): Long {
        markWritten()
        return insertOrIgnore(entity, listOf(onConflict), exec)
    }

    /** Insert-or-do-nothing on a composite conflict target; see [Scope.insertOrIgnore]. */
    suspend fun <T : Entity> Table<G, T>.insertOrIgnore(entity: T, onConflict: List<Column<*, *, T>>): Long {
        markWritten()
        return insertOrIgnore(entity, onConflict, exec)
    }

    /** Counts rows matching [query] (all rows by default). */
    suspend fun <T : Entity> Table<G, T>.count(query: Query = Query()): Long = count(query, exec)

    /** Block form of [count]; see [Scope.count]. */
    suspend fun <T : Entity> Table<G, T>.count(block: QueryBuilder.() -> Unit): Long =
        count(QueryBuilder().apply(block).build(), exec)

    suspend fun <T : Entity> Table<G, T>.find(query: Query): List<T> = select(query, exec)

    /** Block form of [find]; see [Scope.find]. */
    suspend fun <T : Entity> Table<G, T>.find(block: QueryBuilder.() -> Unit): List<T> =
        select(QueryBuilder().apply(block).build(), exec)
    suspend fun <T : Entity> Table<G, T>.findById(id: Any): T? = selectById(id, exec)
    suspend fun <T : Entity> Table<G, T>.all(): List<T> = selectAll(exec)
    /** Updates rows matching [query] with the present fields of [entity]; returns the affected row count. */
    suspend fun <T : Entity> Table<G, T>.update(query: Query, entity: T): Long {
        markWritten()
        return updateRows(query, entity, exec)
    }

    /** Block form of [update]; see [Scope.update]. */
    suspend fun <T : Entity> Table<G, T>.update(entity: T, block: QueryBuilder.() -> Unit): Long {
        markWritten()
        return updateRows(QueryBuilder().apply(block).build(), entity, exec)
    }

    /** Expression form of [update]: `Posts.views set (Posts.views + 1)`; see [Scope.update]. */
    suspend fun <T : Entity> Table<G, T>.update(block: UpdateBuilder.() -> Unit): Long {
        markWritten()
        val builder = UpdateBuilder().apply(block)
        return updateRows(builder.buildWhere(), builder.buildAssignments(), exec)
    }

    /** Deletes rows matching [query]; returns the affected row count. */
    suspend fun <T : Entity> Table<G, T>.deleteWhere(query: Query): Long {
        markWritten()
        return deleteRows(query, exec)
    }

    /** Block form of [deleteWhere]; see [Scope.deleteWhere]. */
    suspend fun <T : Entity> Table<G, T>.deleteWhere(block: QueryBuilder.() -> Unit): Long {
        markWritten()
        return deleteRows(QueryBuilder().apply(block).build(), exec)
    }

    suspend fun <T : Entity> Table<G, T>.execSql(sql: String) {
        markWritten()
        runRaw(sql, exec)
    }

    /** Runs the query, selecting the given fields (or all columns if none are given). */
    suspend fun Join<G>.select(vararg fields: Selectable<*>): List<ResultRow> =
        runSelect(exec, this, if (fields.isEmpty()) allColumns() else fields.toList())

    /** Runs the query, mapping each [ResultRow] with [map] (a projection into your own type). */
    suspend fun <R> Join<G>.select(vararg fields: Selectable<*>, map: (ResultRow) -> R): List<R> =
        select(*fields).map(map)

    /** Runs a two-table join, selecting the given fields (or all columns if none are given). */
    suspend fun <A : Entity, B : Entity> JoinPair<G, A, B>.select(vararg fields: Selectable<*>): List<ResultRow> =
        asJoin().select(*fields)

    /** Runs a two-table join, mapping each [ResultRow] with [map]. */
    suspend fun <A : Entity, B : Entity, R> JoinPair<G, A, B>.select(vararg fields: Selectable<*>, map: (ResultRow) -> R): List<R> =
        asJoin().select(*fields, map = map)

    /** Runs a two-table join, reconstructing both sides as a `Pair` of entities. */
    suspend fun <A : Entity, B : Entity> JoinPair<G, A, B>.find(): List<Pair<A, B>> =
        hydrateInnerPairs(left, right, runSelect(exec, asJoin(), pairSelectFields(left, right)))

    /** Runs a two-table LEFT join, selecting the given fields (or all columns if none are given). */
    suspend fun <A : Entity, B : Entity> LeftJoinPair<G, A, B>.select(vararg fields: Selectable<*>): List<ResultRow> =
        asJoin().select(*fields)

    /** Runs a two-table LEFT join, mapping each [ResultRow] with [map]. */
    suspend fun <A : Entity, B : Entity, R> LeftJoinPair<G, A, B>.select(vararg fields: Selectable<*>, map: (ResultRow) -> R): List<R> =
        asJoin().select(*fields, map = map)

    /**
     * Runs a two-table LEFT join, reconstructing both sides as entity pairs. The right side
     * is `null` for left rows with no match (detected by a NULL right-side primary key).
     */
    suspend fun <A : Entity, B : Entity> LeftJoinPair<G, A, B>.find(): List<Pair<A, B?>> =
        hydrateLeftPairs(left, right, runSelect(exec, asJoin(), pairSelectFields(left, right)))

    /**
     * Runs a raw query on the pinned connection, mapping each row with [handler]. Pass any
     * tables the SQL writes in [invalidates] to notify write listeners on commit; see
     * [Scope.execute].
     */
    suspend fun <R> execute(
        sql: String,
        params: Map<String, Any?> = emptyMap(),
        invalidates: List<Table<G, *>> = emptyList(),
        handler: (ResultSet) -> R,
    ): List<R> {
        invalidates.forEach { it.markWritten() }
        return exec.execute(sql, params, handler)
    }

    /** Runs raw SQL on the pinned connection, returning the row/update count. See [invalidates]. */
    suspend fun execute(
        sql: String,
        params: Map<String, Any?> = emptyMap(),
        invalidates: List<Table<G, *>> = emptyList(),
    ): Long {
        invalidates.forEach { it.markWritten() }
        return exec.execute(sql, params)
    }

    /** Runs a raw statement (DDL/DML) on the pinned connection. See [invalidates]. */
    suspend fun executeUpdate(
        sql: String,
        params: Map<String, Any?> = emptyMap(),
        invalidates: List<Table<G, *>> = emptyList(),
    ) {
        invalidates.forEach { it.markWritten() }
        exec.executeUpdate(sql, params)
    }

    /**
     * Runs [block] inside a SAVEPOINT on the same connection: if it throws, only its
     * work is rolled back (ROLLBACK TO SAVEPOINT) and the exception propagates; the
     * enclosing transaction may continue if the caller catches it.
     *
     * Requires a [suspendTransaction] scope — calling it inside [suspendAutocommit] throws
     * [IllegalStateException] (a savepoint without a surrounding transaction is a server
     * error on PostgreSQL and backend-dependent elsewhere).
     */
    @OptIn(ExperimentalContracts::class)
    suspend fun <R> savepoint(block: suspend SuspendScope<G>.() -> R): R {
        contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
        check(transactional) { "savepoint { } requires a transaction; use suspendTransaction { }, not suspendAutocommit { }" }
        val name = "korm_sp_${savepointCounter++}"
        exec.executeUpdate("SAVEPOINT $name")
        return try {
            block().also { exec.executeUpdate("RELEASE SAVEPOINT $name") }
        } catch (e: Throwable) {
            exec.executeUpdate("ROLLBACK TO SAVEPOINT $name")
            throw e
        }
    }
}

/**
 * Runs [block] in a transaction on a pinned connection: COMMIT when it returns,
 * ROLLBACK if it throws. The suspend counterpart of [transaction]; [block] may itself
 * suspend while the connection stays pinned. Whether this is true async or a blocking
 * driver offloaded to a dispatcher depends on the backend's [SuspendDatabase.useConnection].
 */
@OptIn(ExperimentalContracts::class)
suspend fun <G : Catalog, R> SuspendDatabase<G>.suspendTransaction(block: suspend SuspendScope<G>.() -> R): R {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    val dirty = mutableSetOf<String>()
    val result = useConnection(transactional = true) { SuspendScope<G>(it.observed(config), config, dirty, transactional = true).block() }
    writeListeners.fire(dirty)
    writeListeners.publishCommit(dirty)
    return result
}

/**
 * Runs [block] on a pinned connection in autocommit (no surrounding transaction) — the
 * suspend counterpart of [autocommit], the cheap path for reads / single statements.
 */
@OptIn(ExperimentalContracts::class)
suspend fun <G : Catalog, R> SuspendDatabase<G>.suspendAutocommit(block: suspend SuspendScope<G>.() -> R): R {
    contract { callsInPlace(block, InvocationKind.EXACTLY_ONCE) }
    val dirty = mutableSetOf<String>()
    val result = useConnection(transactional = false) { SuspendScope<G>(it.observed(config), config, dirty, transactional = false).block() }
    writeListeners.fire(dirty)
    writeListeners.publishCommit(dirty)
    return result
}
