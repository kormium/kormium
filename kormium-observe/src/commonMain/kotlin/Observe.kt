package io.github.kormium.observe

import io.github.kormium.Catalog
import io.github.kormium.Entity
import io.github.kormium.QueryBuilder
import io.github.kormium.SuspendScope
import io.github.kormium.Table
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.suspendAutocommit
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Observes a query as a [Flow]: it emits the result once now, then re-runs [fetch] and emits
 * again after every committed write (through this database) that touches one of [tables].
 *
 * This is the generic building block — most callers use the typed [Table.observe] overload.
 * Reads run in [suspendAutocommit]. Bursts of writes are conflated, so a flood of commits
 * collapses into a single re-fetch rather than one emission each.
 *
 * Notification covers writes made through this same database handle's API. Writes by another
 * process, another `Database` instance, or raw SQL that does not name its tables are not seen
 * (the same default boundary Room has); pass the affected table names explicitly for raw SQL.
 * Observation only fires on backends whose driver enables it (all shipped drivers do); on a
 * backend with notification disabled the flow emits the initial value and nothing more.
 */
public fun <G : Catalog, R> SuspendDatabase<G>.observe(
    tables: Set<String>,
    fetch: suspend SuspendScope<G>.() -> R,
): Flow<R> = channelFlow {
    // CONFLATED: while a re-fetch is in flight, extra commits collapse into one pending signal.
    val signals = Channel<Unit>(Channel.CONFLATED)
    val registration = writeListeners.add { changed ->
        if (changed.any { it in tables }) signals.trySend(Unit)
    }
    try {
        send(suspendAutocommit(fetch)) // initial value
        for (signal in signals) {
            send(suspendAutocommit(fetch))
        }
    } finally {
        registration.remove()
        signals.close()
    }
}

/**
 * Observes this table as a [Flow] of entity lists, re-querying after every committed write to
 * the table. `Users.observe(db) { where { Users.age gtEq 18 } }` emits the matching users now
 * and again whenever the `users` table changes. With no [query] block it observes every row.
 */
public fun <G : Catalog, T : Entity> Table<G, T>.observe(
    db: SuspendDatabase<G>,
    query: QueryBuilder.() -> Unit = {},
): Flow<List<T>> {
    val table = this
    return db.observe(setOf(tableName)) { table.find(query) }
}
