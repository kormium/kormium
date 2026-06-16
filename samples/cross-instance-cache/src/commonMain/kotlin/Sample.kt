package io.github.kormium.samples.crossinstancecache

import eu.vendeli.rethis.ReThis
import eu.vendeli.rethis.command.pubsub.publish
import eu.vendeli.rethis.command.pubsub.subscribe
import eu.vendeli.rethis.types.interfaces.MessageEventHandler
import io.github.kormium.Catalog
import io.github.kormium.Column
import io.github.kormium.Entity
import io.github.kormium.NotificationTransport
import io.github.kormium.Query
import io.github.kormium.Table
import io.github.kormium.autocommit
import io.github.kormium.connectNotifications
import io.github.kormium.database.Database
import io.github.kormium.database.createDatabase
import io.github.kormium.decodeTablePayload
import io.github.kormium.encodeTablePayload
import io.github.kormium.eq
import io.github.kormium.transaction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object Shop : Catalog

class Product : Entity() {
    var id by Products.id
    var name by Products.name
}

object Products : Table<Shop, Product>("products", ::Product) {
    val id by Column.Int().primaryKey()
    val name by Column.Text()

    init { id; name }
}

/**
 * A [NotificationTransport] over Redis pub/sub, built with the multiplatform `rethis` client — so
 * it runs on JVM and Native like the rest of kormium. publish/subscribe are rethis suspend calls;
 * the wire format is core's [encodeTablePayload]/[decodeTablePayload], so this transport
 * interoperates with the built-in Postgres LISTEN/NOTIFY transports on the same channel.
 *
 * This is the whole "write your own transport" surface: two methods.
 */
class RedisNotificationTransport(
    private val client: ReThis,
    private val channel: String = "kormium_changes",
) : NotificationTransport {
    override suspend fun publish(tables: Set<String>) {
        client.publish(channel, encodeTablePayload(tables))
    }

    override fun subscribe(): Flow<Set<String>> {
        val ch = channel // capture before callbackFlow, whose ProducerScope shadows `channel`
        return callbackFlow {
            val producer = this
            client.subscribe(
                ch,
                callback = MessageEventHandler { _, message -> producer.trySend(decodeTablePayload(message)) },
            )
            awaitClose { /* the rethis subscription is torn down when the client is closed */ }
        }
    }
}

/**
 * A tiny in-process read-through cache. Invalidation is driven by kormium's commit hook: a write on
 * ANY instance is delivered here (through the Redis transport) and clears the affected table.
 *
 * Deliberately minimal: not thread-safe and table-granular. A real cache needs a concurrent store,
 * per-key eviction and — because notification delivery is best-effort — a TTL safety net.
 */
class ProductCache(private val db: Database<Shop>) {
    private val byId = mutableMapOf<Int, String?>()

    init {
        db.writeListeners.add { tables -> if ("products" in tables) byId.clear() }
    }

    fun get(id: Int): String? {
        if (byId.containsKey(id)) {
            println("cache HIT  $id")
            return byId[id]
        }
        println("cache MISS $id -> read Postgres")
        val name = db.autocommit { Products.findById(id)?.name }
        byId[id] = name
        return name
    }
}

/**
 * Two driver handles over one Postgres act as two app instances; a write on instance A invalidates
 * instance B's cache via Redis, even though B never saw the write directly.
 */
suspend fun runSample(
    pgHost: String = "localhost",
    pgPort: Int = 5432,
    pgDatabase: String = "postgres",
    pgUser: String = "postgres",
    pgPassword: String = "password",
    redisHost: String = "localhost",
    redisPort: Int = 6379,
) {
    val driverA = createDatabase(pgHost, pgPort, pgDatabase, pgUser, pgPassword)
    val driverB = createDatabase(pgHost, pgPort, pgDatabase, pgUser, pgPassword)
    val dbA: Database<Shop> = driverA
    val dbB: Database<Shop> = driverB
    val redisForA = ReThis(redisHost, redisPort)
    val redisForB = ReThis(redisHost, redisPort)
    try {
        dbA.transaction {
            Products.execSql("""DROP TABLE IF EXISTS "products"""")
            Products.execSql("""CREATE TABLE "products" ("id" integer PRIMARY KEY, "name" text NOT NULL)""")
            Products.insert(Product().apply { id = 1; name = "Keyboard" })
        }

        val cache = ProductCache(dbB)
        val regB = driverB.connectNotifications(RedisNotificationTransport(redisForB))
        val regA = driverA.connectNotifications(RedisNotificationTransport(redisForA))
        try {
            delay(1_000) // let instance B's Redis subscription establish

            println("get(1) = ${cache.get(1)}") // MISS -> populate
            println("get(1) = ${cache.get(1)}") // HIT

            // Instance A updates the row. Its commit is published to Redis; instance B receives it
            // and clears its cache — even though the write never went through B.
            dbA.transaction {
                Products.update(Query(Products.id eq 1), Product().apply { id = 1; name = "Mechanical Keyboard" })
            }
            delay(1_000) // let the notification propagate through Redis

            println("get(1) = ${cache.get(1)}") // MISS again -> fresh value from Postgres
        } finally {
            regA.remove()
            regB.remove()
        }
    } finally {
        driverA.close()
        driverB.close()
        redisForA.close()
        redisForB.close()
    }
}
