package io.github.kormium.migrate

import io.github.kormium.Catalog
import io.github.kormium.SqlExecutor
import io.github.kormium.database.Database
import kotlin.time.Clock

/**
 * A named, ordered schema change expressed as raw SQL. Kormium does not own schema, so a migration
 * is whatever SQL your backend needs — it is intentionally **not** portable across databases.
 *
 * [id] must be unique and stable: it is the key recorded once the migration is applied, and the
 * key used to detect tampering (see [migrate]'s checksum validation). Migrations are applied in
 * the order they appear in the list passed to [migrate].
 *
 * Construct with a single SQL string (split into statements on top-level `;`, honouring quoted
 * strings/identifiers, `--` and `/* */` comments, and Postgres `$tag$…$tag$` dollar-quoting):
 *
 * ```kotlin
 * Migration("001-create-users", """
 *     CREATE TABLE "users" ("id" integer PRIMARY KEY, "name" text NOT NULL);
 *     CREATE INDEX users_name_idx ON "users" ("name");
 * """)
 * ```
 *
 * For SQL the splitter cannot handle (e.g. a Postgres function body that itself contains `;`),
 * pass the statements explicitly with the `List<String>` constructor.
 */
public class Migration<G : Catalog> private constructor(
    public val id: String,
    internal val statements: List<String>,
    internal val checksum: String,
) {
    // The journal records the id as varchar(MAX_ID_LENGTH) (see [migrate]); reject an over-long id
    // here, at construction, rather than letting it fail later on INSERT into the journal.
    init {
        require(id.length <= MAX_ID_LENGTH) {
            "Migration id must be at most $MAX_ID_LENGTH characters " +
                "(it is stored in the kormium_migrations.id varchar($MAX_ID_LENGTH) column), " +
                "was ${id.length}: \"$id\""
        }
    }

    /** A migration written as one SQL string; statements are separated by top-level `;`. */
    public constructor(id: String, sql: String) : this(id, splitStatements(sql), crc32(normalize(sql)))

    /** A migration written as explicit statements; bypasses the SQL splitter. */
    public constructor(id: String, statements: List<String>) :
        this(id, statements, crc32(statements.joinToString("\n;\n") { normalize(it) }))
}

/**
 * Maximum length of a [Migration.id]. It must fit the journal's `id varchar(MAX_ID_LENGTH)` primary
 * key — a value MySQL accepts as a key (a `TEXT` PK needs a prefix length) and that is equally valid
 * on Postgres and SQLite. 255 is comfortably longer than any sensible migration id.
 */
private const val MAX_ID_LENGTH = 255

/** Thrown when an already-applied migration's SQL no longer matches the recorded checksum. */
public class MigrationChecksumException(
    public val id: String,
    public val recorded: String,
    public val current: String,
) : RuntimeException(
    "Migration \"$id\" was modified after it was applied " +
        "(recorded checksum $recorded, current $current). " +
        "Migrations are immutable once applied — add a new migration instead.",
)

/**
 * The advisory-lock key for a migration run, used only by backends whose dialect returns
 * advisory-lock SQL. It is *derived* (not secret, not configurable) from a kormium-specific namespace
 * and the journal name, so it is stable across builds and lands in PostgreSQL's single-`bigint`
 * advisory-lock space with a negligible chance of colliding with an application's own advisory
 * locks. Computed lazily so it does not depend on top-level initialization order.
 */
private val migrationLockKey: Long by lazy {
    (crc32("io.github.kormium").toLong(16) shl 32) or crc32(JOURNAL).toLong(16)
}

private const val JOURNAL = "kormium_migrations"

/**
 * Applies every migration in [migrations] whose [Migration.id] is not yet recorded in the
 * `kormium_migrations` table, in list order. The whole run executes in **one transaction**:
 *
 * - On backends that support it (PostgreSQL), a transaction-scoped advisory lock is taken first,
 *   so concurrently-starting application instances serialize cleanly — the second instance waits,
 *   then sees the migrations already applied and does nothing. SQLite has no advisory lock, so
 *   concurrent cross-process migration is not fully serialized; it is still safe (the journal's
 *   primary key plus the all-or-nothing transaction prevent double-application — at worst one
 *   instance fails to take the write lock and rolls back, then no-ops on restart), but prefer
 *   migrating SQLite from a single process.
 * - Each already-applied migration is **checksum-validated**: if its SQL changed since it was
 *   applied, [MigrationChecksumException] is thrown (Flyway-style fail-fast).
 * - The journal records `id`, `checksum`, `applied_at` and the apply-order index.
 *
 * Because the run is one transaction, it is **all-or-nothing**: if any migration fails, the whole
 * batch rolls back and nothing is recorded, so the next start retries from a clean state. (A rare
 * statement that cannot run inside a transaction — e.g. `CREATE INDEX CONCURRENTLY` — therefore
 * cannot be part of a migration batch.) Running the same list again is a safe no-op, so calling
 * `migrate(...)` on every startup is fine — typically from a `beforeStart { migrate(appMigrations) }`
 * block on the `createX { }` builder.
 */
public fun <G : Catalog> Database<G>.migrate(migrations: List<Migration<G>>) {
    usePinned(transactional = true) { exec ->
        exec.dialect.advisoryLockSql(migrationLockKey)?.let { exec.execute(it) }

        // id is varchar(255), not text: MySQL forbids a TEXT/BLOB column as a PRIMARY KEY without a
        // prefix length (error 1170), and varchar(255) is equally valid on Postgres and SQLite.
        exec.execute(
            "CREATE TABLE IF NOT EXISTS $JOURNAL " +
                "(id varchar($MAX_ID_LENGTH) NOT NULL PRIMARY KEY, checksum text NOT NULL, applied_at text NOT NULL, idx integer NOT NULL)",
        )

        val applied: Map<String, String> = exec
            .execute("SELECT id, checksum FROM $JOURNAL") { rs -> rs.getString(0)!! to rs.getString(1)!! }
            .toMap()

        var nextIndex = applied.size
        for (migration in migrations) {
            val recorded = applied[migration.id]
            if (recorded != null) {
                if (recorded != migration.checksum) {
                    throw MigrationChecksumException(migration.id, recorded, migration.checksum)
                }
                continue
            }
            for (statement in migration.statements) {
                exec.execute(statement)
            }
            exec.executeUpdate(
                "INSERT INTO $JOURNAL (id, checksum, applied_at, idx) VALUES (:id, :checksum, :appliedAt, :idx)",
                mapOf(
                    "id" to migration.id,
                    "checksum" to migration.checksum,
                    "appliedAt" to Clock.System.now().toString(),
                    "idx" to nextIndex,
                ),
            )
            nextIndex++
        }
    }
}

private fun SqlExecutor.execute(sql: String): Long = execute(sql, emptyMap())

// --- SQL helpers (multiplatform, no external deps) ---

/** Normalizes line endings and trims so cosmetic whitespace doesn't change the checksum. */
internal fun normalize(sql: String): String = sql.replace("\r\n", "\n").replace("\r", "\n").trim()

/**
 * A small, stable CRC32 (polynomial 0xEDB88320) over the UTF-8 bytes of [s], returned as an
 * 8-char hex string. This is change-detection for migration tampering, not a security primitive.
 */
internal fun crc32(s: String): String {
    var crc = 0.inv()
    for (b in s.encodeToByteArray()) {
        crc = crc32Table[(crc xor b.toInt()) and 0xFF] xor (crc ushr 8)
    }
    crc = crc.inv()
    return (crc.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
}

private val crc32Table: IntArray = IntArray(256) { n ->
    var c = n
    repeat(8) { c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1) }
    c
}

/**
 * Splits [sql] into individual statements on top-level `;`, ignoring `;` inside single-quoted
 * strings, double-quoted identifiers, `--` line comments, `/* */` block comments, and Postgres
 * `$tag$…$tag$` dollar-quoted bodies. Empty statements (e.g. trailing `;`) are dropped.
 */
internal fun splitStatements(sql: String): List<String> {
    val out = mutableListOf<String>()
    val sb = StringBuilder()
    var i = 0
    val n = sql.length
    while (i < n) {
        val c = sql[i]
        when {
            c == '-' && i + 1 < n && sql[i + 1] == '-' -> {
                while (i < n && sql[i] != '\n') { sb.append(sql[i]); i++ }
            }
            c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                sb.append("/*"); i += 2
                while (i + 1 < n && !(sql[i] == '*' && sql[i + 1] == '/')) { sb.append(sql[i]); i++ }
                if (i + 1 < n) { sb.append("*/"); i += 2 } else { while (i < n) { sb.append(sql[i]); i++ } }
            }
            c == '\'' || c == '"' -> i = copyQuoted(sql, i, c, sb)
            c == '$' -> {
                val tag = dollarTag(sql, i)
                if (tag == null) { sb.append(c); i++ } else {
                    sb.append(tag); i += tag.length
                    val end = sql.indexOf(tag, i)
                    if (end < 0) { sb.append(sql, i, n); i = n } else { sb.append(sql, i, end + tag.length); i = end + tag.length }
                }
            }
            c == ';' -> {
                sb.toString().trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
                sb.clear(); i++
            }
            else -> { sb.append(c); i++ }
        }
    }
    sb.toString().trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
    return out
}

/** Copies a quoted run starting at the opening [quote] (with doubled-quote escaping); returns the new index. */
private fun copyQuoted(sql: String, start: Int, quote: Char, sb: StringBuilder): Int {
    var i = start
    val n = sql.length
    sb.append(sql[i]); i++ // opening quote
    while (i < n) {
        sb.append(sql[i])
        if (sql[i] == quote) {
            if (i + 1 < n && sql[i + 1] == quote) { sb.append(sql[i + 1]); i += 2; continue }
            return i + 1
        }
        i++
    }
    return i
}

/** Returns the dollar-quote tag (`$$` or `$name$`) starting at [start], or null if not one. */
private fun dollarTag(sql: String, start: Int): String? {
    val n = sql.length
    var i = start + 1
    if (i < n && sql[i] == '$') return "$$"
    val sb = StringBuilder("$")
    while (i < n && (sql[i].isLetterOrDigit() || sql[i] == '_')) { sb.append(sql[i]); i++ }
    return if (i < n && sql[i] == '$') sb.append('$').toString() else null
}
