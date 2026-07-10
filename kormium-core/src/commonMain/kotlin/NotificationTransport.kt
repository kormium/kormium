package io.github.kormium

import io.github.kormium.database.SuspendDatabase
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * A cross-process change transport: it carries "these tables were written" signals between
 * separate database instances (different processes / nodes) so that [transaction]-style
 * commit notifications — and anything built on them, like `kormium-observe` or an app-level
 * cache — see writes made by OTHER instances, not just the local one.
 *
 * This is a user extension point. Kormium ships the Postgres `LISTEN/NOTIFY` transport (no
 * external dependency); a broker-backed transport (Redis, Kafka, …) is a few lines on top of
 * this interface — see the `cross-instance-cache` sample. The interface is intentionally a
 * SINGLE suspend shape (not a blocking/suspend pair): the sync-vs-suspend bridging is handled
 * by [connectNotifications], so an implementer writes exactly one version.
 */
public interface NotificationTransport {
    /**
     * Publishes the table names a local commit just wrote. `suspend` so coroutine-based clients
     * (rethis, r2dbc) are idiomatic; [connectNotifications] invokes this fire-and-forget on a
     * background scope, so it never blocks the committing thread. Delivery is best-effort.
     */
    public suspend fun publish(tables: Set<String>)

    /**
     * A cold [Flow] of remote change signals: each element is the set of tables some OTHER
     * instance committed. [connectNotifications] collects it on a background coroutine and feeds
     * each element into the local write-notification registry.
     */
    public fun subscribe(): Flow<Set<String>>
}

/**
 * Connects [transport] to this database so change notifications cross process boundaries:
 *
 * - inbound: remote signals from [NotificationTransport.subscribe] are delivered into this
 *   database's local [WriteListeners], exactly as if a local commit had touched those tables —
 *   so `kormium-observe` queries (and any other listener) re-fire cluster-wide with no change
 *   on their side;
 * - outbound: every LOCAL commit's dirty tables are forwarded to [NotificationTransport.publish]
 *   (fire-and-forget). Remote-delivered signals are NOT re-published, so instances don't echo.
 *
 * Returns a [Registration]; call [Registration.remove] to stop the subscription and outbound
 * publishing (it cancels the background scope). [close][AutoCloseable.close]-ing the database
 * does not auto-remove it, so remove it explicitly (or just let the process exit).
 *
 * Defined on [SuspendDatabase] because every backend implements it (blocking backends implement
 * both interfaces and share one [writeListeners]); the publish hook therefore also fires for the
 * blocking [transaction]/[autocommit] path.
 */
/**
 * Standard wire encoding for a table-name change set: a comma-joined list. Shared by transport
 * implementations so that, e.g., a JDBC instance and an r2dbc instance on the same channel
 * interoperate. Table-granular and tiny (well within Postgres NOTIFY's ~8KB payload). Table names
 * don't contain commas in practice; [decodeTablePayload] drops blanks defensively.
 */
public fun encodeTablePayload(tables: Set<String>): String = tables.joinToString(",")

/** Inverse of [encodeTablePayload]. */
public fun decodeTablePayload(payload: String): Set<String> =
    payload.split(",").filter { it.isNotEmpty() }.toSet()

public fun <G : Catalog> SuspendDatabase<G>.connectNotifications(transport: NotificationTransport): Registration {
    val listeners = writeListeners
    // Errors are swallowed (best-effort delivery); a real deployment relies on a cache TTL as the
    // safety net for dropped notifications. See the cross-instance-cache sample README.
    val scope = CoroutineScope(ioDispatcher + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
    scope.launch { transport.subscribe().collect { listeners.fire(it) } }
    listeners.setCommitPublish { tables -> scope.launch { transport.publish(tables) } }
    return Registration {
        listeners.setCommitPublish(null)
        scope.cancel()
    }
}
