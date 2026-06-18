package io.github.kormium

import io.github.kormium.database.Database
import io.github.kormium.database.SuspendDatabase
import io.github.kormium.WriteListeners

/**
 * A SQLite-backed [Database] (and [SuspendDatabase]). This is the type returned by
 * [createSqliteDatabase]; it adds [AutoCloseable] so the underlying connection(s) can be
 * released (or used via a `use { }` block). Blocking query methods come from [Database];
 * the suspend path (suspendTransaction/suspendAutocommit) comes from [SuspendDatabase].
 */
interface SqliteDriver : Database<Nothing>, SuspendDatabase<Nothing>, AutoCloseable {
    // Resolves the config default inherited from both Database and SuspendDatabase; concrete
    // drivers supply it (from the createSqliteDatabase config argument).
    override val config: KormiumConfig

    // Resolves the writeListeners default inherited from both interfaces; concrete drivers
    // supply a real registry so change observation (kormium-observe) works.
    override val writeListeners: WriteListeners

    // Resolves the isClosed default inherited from both interfaces; concrete drivers track it.
    override val isClosed: Boolean
}
