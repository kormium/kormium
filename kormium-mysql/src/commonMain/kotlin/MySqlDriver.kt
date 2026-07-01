package io.github.kormium

import io.github.kormium.database.Database
import io.github.kormium.database.SuspendDatabase

/**
 * A MySQL/MariaDB-backed [Database] (and [SuspendDatabase]). This is the type returned by the
 * driver factories; it adds [AutoCloseable] so the underlying connection pool can be released
 * (or used via a `use { }` block). Blocking query methods come from [Database]; the suspend path
 * (suspendTransaction/suspendAutocommit) comes from [SuspendDatabase]. Mirrors [PostgresDriver].
 */
interface MySqlDriver : Database<Nothing>, SuspendDatabase<Nothing>, AutoCloseable {
    // Resolves the config default inherited from both Database and SuspendDatabase; concrete
    // drivers supply it (from the createDatabase config argument).
    override val config: KormiumConfig

    // Resolves the writeListeners default inherited from both interfaces; concrete drivers
    // supply a real registry so change observation (kormium-observe) works.
    override val writeListeners: WriteListeners

    // Resolves the isClosed default inherited from both interfaces; concrete drivers track it.
    override val isClosed: Boolean

    // Resolves the dialect default inherited from both interfaces; concrete drivers supply MySqlDialect.
    override val dialect: Dialect
}
