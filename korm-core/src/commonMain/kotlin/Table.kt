package io.github.knyazevs.korm

import io.github.knyazevs.korm.resultset.ResultSet
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * A table definition: its SQL table name and columns, tagged with the catalog
 * [G] it belongs to. A table holds no connection — operations run inside a
 * [transaction] / [autocommit] scope (or [Scope]) which supplies the pinned
 * [SqlExecutor], or inside their suspend counterparts via [SuspendScope]. The
 * operation methods are `internal`; call them through [Scope] / [SuspendScope].
 *
 * Each operation is built in two parts: a pure `*Sql` helper that renders SQL +
 * params (no I/O), and a thin runner. The blocking runner (taking [SqlExecutor])
 * and the suspend runner (taking [SuspendSqlExecutor]) share the same `*Sql`
 * helper and differ only in how they execute it.
 */
abstract class Table<G: Catalog, T: Entity>(val tableName: String, val factory: () -> T) {
    /** Builds an entity from a loaded field map (the database read path). */
    internal fun hydrate(fields: MutableMap<String, Any?>): T = factory().also { it.replaceFields(fields) }

    private val fieldDisplayName: MutableMap<String, Column<*, *, *>> = mutableMapOf()

    fun getFieldDisplayNames() = fieldDisplayName

    /**
     * The primary-key column(s): those declared with `primaryKey = true`, or the column
     * named "id" if none are marked.
     */
    val primaryKey: List<Column<*, *, *>>
        get() = fieldDisplayName.values.filter { it.isPrimaryKey }
            .ifEmpty { fieldDisplayName.values.filter { it.fieldKey == "id" } }
    internal fun addColumn(fieldName: String, column: Column<*, *, *>) {
        logger.trace { "add column/field ${column.name}/$fieldName" }
        fieldDisplayName[fieldName] = column
    }

    private fun getColumnNames(dialect: Dialect): List<String> {
        logger.trace { "get column names" }
        return fieldDisplayName.map { dialect.quoteIdentifier(it.value.name) }
    }

    private fun qualifiedTableName(dialect: Dialect): String = qualifiedName(dialect)

    private fun paramBuilder(dialect: Dialect, typeMapper: TypeMapper) = ParamBuilder(dialect, typeMapper)

    // find/findById/all SELECT every column in fieldDisplayName order, so the result columns
    // line up positionally — read them by index straight into the entity's field map, with no
    // per-row name→index map or intermediate allocations.
    private fun mapToDao(rs: ResultSet, typeMapper: TypeMapper): T {
        val fields = HashMap<String, Any?>(fieldDisplayName.size * 2)
        var index = 0
        for ((fieldName, column) in fieldDisplayName) {
            fields[fieldName] = typeMapper.fromResult(rs, index, column.columnType)
            index++
        }
        return hydrate(fields)
    }

    // Only the columns the entity actually assigned (present in its fields map),
    // so update() can tell "leave untouched" (absent) from "set to NULL" (present and null).
    private fun generatePresentFields(dao: T): List<Pair<String, Any?>> {
        return this.fieldDisplayName.filter { dao.fields.containsKey(it.key) }.map {
            it.value.name to dao.fields[it.key]
        }
    }

    // ---- pure SQL builders (no I/O) — shared by the blocking and suspend runners ----

    private fun selectByIdSql(id: Any, dialect: Dialect, typeMapper: TypeMapper): Pair<String, Map<String, Any?>> {
        val pk = primaryKey.singleOrNull()
            ?: throw IllegalStateException(
                "findById requires a single-column primary key on $tableName; " +
                    "use find(...) for composite (or missing) keys",
            )
        val builder = paramBuilder(dialect, typeMapper)
        val idPlaceholder = builder.bind(id)
        val sql = "SELECT ${getColumnNames(dialect).joinToString(", ")} FROM ${qualifiedTableName(dialect)} WHERE ${dialect.quoteIdentifier(pk.name)} = $idPlaceholder"
        return sql.trimIndent() to builder.params
    }

    private fun selectSql(query: Query, dialect: Dialect, typeMapper: TypeMapper): Pair<String, Map<String, Any?>> {
        val builder = paramBuilder(dialect, typeMapper)
        val queryStr = query.toSql(builder)
        val sql = "SELECT ${getColumnNames(dialect).joinToString(", ")} FROM ${qualifiedTableName(dialect)} $queryStr"
        return sql.trimIndent() to builder.params
    }

    private fun selectAllSql(dialect: Dialect): String =
        "SELECT ${getColumnNames(dialect).joinToString(", ")} FROM ${qualifiedTableName(dialect)}".trimIndent()

    private fun insertSql(entity: T, dialect: Dialect, typeMapper: TypeMapper, returning: Boolean): Pair<String, Map<String, Any?>> {
        val builder = paramBuilder(dialect, typeMapper)
        // Only the present fields go into the INSERT: an absent field is omitted (so the
        // database can apply its default / generated value), an explicit null is bound as NULL.
        val presentFields = generatePresentFields(entity)
        val base = if (presentFields.isEmpty()) {
            "INSERT INTO ${qualifiedTableName(dialect)} DEFAULT VALUES"
        } else {
            val columns = presentFields.joinToString(", ") { dialect.quoteIdentifier(it.first) }
            val values = presentFields.joinToString(", ") { builder.bind(it.second) }
            "INSERT INTO ${qualifiedTableName(dialect)} ($columns) VALUES ($values)"
        }
        val sql = if (returning) "$base RETURNING ${getColumnNames(dialect).joinToString(", ")}" else base
        return sql to builder.params
    }

    // The present columns of [entity] (its "shape"), in table-declaration order.
    private fun presentColumns(entity: T): List<Column<*, *, *>> =
        fieldDisplayName.values.filter { entity.fields.containsKey(it.fieldKey) }

    private class BatchGroup(val columns: List<Column<*, *, *>>, val entityIndices: List<Int>)

    // Splits a batch into groups per the [BatchInsertMode]. Each group becomes one INSERT.
    private fun batchGroups(entities: List<T>, mode: BatchInsertMode): List<BatchGroup> {
        val shapes = entities.map { presentColumns(it) }
        return when (mode) {
            BatchInsertMode.Strict -> {
                val firstKeys = shapes.first().map { it.fieldKey }
                require(shapes.all { it.map { c -> c.fieldKey } == firstKeys }) {
                    "insertAll(Strict) requires every entity to have the same assigned fields; " +
                        "split the batch or use GroupByAssignedFields / UnionNulls"
                }
                listOf(BatchGroup(shapes.first(), entities.indices.toList()))
            }
            BatchInsertMode.GroupByAssignedFields -> {
                val byShape = LinkedHashMap<List<String>, MutableList<Int>>()
                shapes.forEachIndexed { i, cols -> byShape.getOrPut(cols.map { it.fieldKey }) { mutableListOf() }.add(i) }
                byShape.values.map { idxs -> BatchGroup(shapes[idxs.first()], idxs) }
            }
            BatchInsertMode.UnionNulls -> {
                val union = fieldDisplayName.values.filter { col -> entities.any { it.fields.containsKey(col.fieldKey) } }
                listOf(BatchGroup(union, entities.indices.toList()))
            }
        }
    }

    // One executable statement of a batch: SQL, its params, and the original input indices it
    // covers (so RETURNING results can be scattered back into input order).
    private class BatchStatement(val sql: String, val params: Map<String, Any?>, val indices: List<Int>)

    private fun buildBatchStatements(
        entities: List<T>,
        mode: BatchInsertMode,
        dialect: Dialect,
        typeMapper: TypeMapper,
        returning: Boolean,
    ): List<BatchStatement> {
        val returningSuffix = if (returning) " RETURNING ${getColumnNames(dialect).joinToString(", ")}" else ""
        val statements = mutableListOf<BatchStatement>()
        for (group in batchGroups(entities, mode)) {
            if (group.columns.isEmpty()) {
                // No assigned fields: a multi-row DEFAULT VALUES isn't valid, so emit one per row.
                for (idx in group.entityIndices) {
                    statements += BatchStatement(
                        "INSERT INTO ${qualifiedTableName(dialect)} DEFAULT VALUES$returningSuffix",
                        emptyMap(),
                        listOf(idx),
                    )
                }
            } else {
                val builder = paramBuilder(dialect, typeMapper)
                val colSql = group.columns.joinToString(", ") { dialect.quoteIdentifier(it.name) }
                val tuples = group.entityIndices.joinToString(", ") { idx ->
                    val entity = entities[idx]
                    "(${group.columns.joinToString(", ") { col -> builder.bind(entity.fields[col.fieldKey]) }})"
                }
                statements += BatchStatement(
                    "INSERT INTO ${qualifiedTableName(dialect)} ($colSql) VALUES $tuples$returningSuffix",
                    builder.params,
                    group.entityIndices,
                )
            }
        }
        return statements
    }

    private fun upsertSql(
        entity: T,
        conflict: List<Column<*, *, *>>,
        update: T,
        dialect: Dialect,
        typeMapper: TypeMapper,
        returning: Boolean,
    ): Pair<String, Map<String, Any?>> {
        val builder = paramBuilder(dialect, typeMapper)
        require(conflict.isNotEmpty()) { "upsert() conflict target must contain at least one column" }
        val insertFields = generatePresentFields(entity)
        require(insertFields.isNotEmpty()) { "upsert() needs at least one field set on the insert entity" }
        val columns = insertFields.joinToString(", ") { dialect.quoteIdentifier(it.first) }
        val values = insertFields.joinToString(", ") { builder.bind(it.second) }
        val conflictCols = conflict.joinToString(", ") { dialect.quoteIdentifier(it.name) }
        val updateFields = generatePresentFields(update)
        require(updateFields.isNotEmpty()) { "upsert() needs at least one field set on the update entity" }
        val setClause = updateFields.joinToString(", ") { "${dialect.quoteIdentifier(it.first)} = ${builder.bind(it.second)}" }
        val base = "INSERT INTO ${qualifiedTableName(dialect)} ($columns) VALUES ($values) " +
            "ON CONFLICT ($conflictCols) DO UPDATE SET $setClause"
        val sql = if (returning) "$base RETURNING ${getColumnNames(dialect).joinToString(", ")}" else base
        return sql to builder.params
    }

    private fun insertOrIgnoreSql(
        entity: T,
        conflict: List<Column<*, *, *>>,
        dialect: Dialect,
        typeMapper: TypeMapper,
    ): Pair<String, Map<String, Any?>> {
        val builder = paramBuilder(dialect, typeMapper)
        require(conflict.isNotEmpty()) { "insertOrIgnore() conflict target must contain at least one column" }
        val insertFields = generatePresentFields(entity)
        require(insertFields.isNotEmpty()) { "insertOrIgnore() needs at least one field set on the entity" }
        val columns = insertFields.joinToString(", ") { dialect.quoteIdentifier(it.first) }
        val values = insertFields.joinToString(", ") { builder.bind(it.second) }
        val conflictCols = conflict.joinToString(", ") { dialect.quoteIdentifier(it.name) }
        return "INSERT INTO ${qualifiedTableName(dialect)} ($columns) VALUES ($values) " +
            "ON CONFLICT ($conflictCols) DO NOTHING" to builder.params
    }

    private fun countSql(query: Query, dialect: Dialect, typeMapper: TypeMapper): Pair<String, Map<String, Any?>> {
        val builder = paramBuilder(dialect, typeMapper)
        // Count the rows matching the predicate only: ORDER BY / LIMIT / OFFSET must not apply
        // to an aggregate (an OFFSET would skip the single COUNT row and read as 0).
        val queryStr = query.toWhereSql(builder)
        val sql = "SELECT COUNT(*) FROM ${qualifiedTableName(dialect)} $queryStr"
        return sql.trimIndent() to builder.params
    }

    private fun updateSql(query: Query, entity: T, dialect: Dialect, typeMapper: TypeMapper): Pair<String, Map<String, Any?>> {
        val builder = paramBuilder(dialect, typeMapper)
        val updateFields = generatePresentFields(entity)
        require(updateFields.isNotEmpty()) {
            "update() needs at least one field set on the entity to update ${qualifiedTableName(dialect)}"
        }
        val generatedUpdateFields = updateFields
            .joinToString(", ") { "${dialect.quoteIdentifier(it.first)}=${builder.bind(it.second)}" }
        // WHERE only: a plain UPDATE doesn't take ORDER BY / LIMIT / OFFSET (invalid in Postgres).
        val queryStr = query.toWhereSql(builder)
        val sql = """
            UPDATE ${qualifiedTableName(dialect)}
            SET $generatedUpdateFields
           $queryStr
        """
        return sql.trimIndent() to builder.params
    }

    private fun deleteSql(query: Query, dialect: Dialect, typeMapper: TypeMapper): Pair<String, Map<String, Any?>> {
        val builder = paramBuilder(dialect, typeMapper)
        // WHERE only: a plain DELETE doesn't take ORDER BY / LIMIT / OFFSET (invalid in Postgres).
        val queryStr = query.toWhereSql(builder)
        val sql = "DELETE FROM ${qualifiedTableName(dialect)} $queryStr"
        return sql.trimIndent() to builder.params
    }

    // ---- blocking runners (called by Scope) ----

    internal fun runRaw(sql: String, exec: SqlExecutor) {
        exec.execute(sql = sql.trimIndent())
    }

    internal fun selectById(id: Any, exec: SqlExecutor): T? {
        val (sql, params) = selectByIdSql(id, exec.dialect, exec.typeMapper)
        return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
    }

    internal fun select(query: Query, exec: SqlExecutor): List<T> {
        val (sql, params) = selectSql(query, exec.dialect, exec.typeMapper)
        return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }
    }

    internal fun selectAll(exec: SqlExecutor): List<T> =
        exec.execute(selectAllSql(exec.dialect)) { rs -> mapToDao(rs, exec.typeMapper) }

    internal fun insert(entity: T, exec: SqlExecutor, returning: Boolean): T? {
        val (sql, params) = insertSql(entity, exec.dialect, exec.typeMapper, returning)
        if (!returning) {
            exec.executeUpdate(sql = sql, namedParameters = params)
            return entity
        }
        return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
    }

    internal fun insertAll(entities: List<T>, exec: SqlExecutor, returning: Boolean, mode: BatchInsertMode): List<T> {
        if (entities.isEmpty()) return emptyList()
        val statements = buildBatchStatements(entities, mode, exec.dialect, exec.typeMapper, returning)
        if (!returning) {
            for (s in statements) exec.executeUpdate(sql = s.sql, namedParameters = s.params)
            return entities
        }
        val out = arrayOfNulls<Entity>(entities.size)
        for (s in statements) {
            val rows = exec.execute(s.sql, s.params) { rs -> mapToDao(rs, exec.typeMapper) }
            rows.forEachIndexed { k, row -> out[s.indices[k]] = row }
        }
        @Suppress("UNCHECKED_CAST")
        return out.toList() as List<T>
    }

    internal fun upsert(entity: T, conflict: List<Column<*, *, *>>, update: T, exec: SqlExecutor, returning: Boolean): T? {
        val (sql, params) = upsertSql(entity, conflict, update, exec.dialect, exec.typeMapper, returning)
        if (!returning) {
            exec.executeUpdate(sql = sql, namedParameters = params)
            return entity
        }
        return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
    }

    internal fun insertOrIgnore(entity: T, conflict: List<Column<*, *, *>>, exec: SqlExecutor): Long {
        val (sql, params) = insertOrIgnoreSql(entity, conflict, exec.dialect, exec.typeMapper)
        return exec.executeUpdate(sql = sql, namedParameters = params)
    }

    internal fun count(query: Query, exec: SqlExecutor): Long {
        val (sql, params) = countSql(query, exec.dialect, exec.typeMapper)
        return exec.execute(sql, params) { rs -> rs.getLong(0) ?: 0L }.firstOrNull() ?: 0L
    }

    internal fun updateRows(query: Query, entity: T, exec: SqlExecutor): Long {
        val (sql, params) = updateSql(query, entity, exec.dialect, exec.typeMapper)
        return exec.executeUpdate(sql = sql, namedParameters = params)
    }

    internal fun deleteRows(query: Query, exec: SqlExecutor): Long {
        val (sql, params) = deleteSql(query, exec.dialect, exec.typeMapper)
        return exec.executeUpdate(sql = sql, namedParameters = params)
    }

    // ---- suspend runners (called by SuspendScope) — same *Sql helpers, suspend execution ----

    internal suspend fun runRaw(sql: String, exec: SuspendSqlExecutor) {
        exec.execute(sql = sql.trimIndent())
    }

    internal suspend fun selectById(id: Any, exec: SuspendSqlExecutor): T? {
        val (sql, params) = selectByIdSql(id, exec.dialect, exec.typeMapper)
        return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
    }

    internal suspend fun select(query: Query, exec: SuspendSqlExecutor): List<T> {
        val (sql, params) = selectSql(query, exec.dialect, exec.typeMapper)
        return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }
    }

    internal suspend fun selectAll(exec: SuspendSqlExecutor): List<T> =
        exec.execute(selectAllSql(exec.dialect)) { rs -> mapToDao(rs, exec.typeMapper) }

    internal suspend fun insert(entity: T, exec: SuspendSqlExecutor, returning: Boolean): T? {
        val (sql, params) = insertSql(entity, exec.dialect, exec.typeMapper, returning)
        if (!returning) {
            exec.executeUpdate(sql = sql, namedParameters = params)
            return entity
        }
        return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
    }

    internal suspend fun insertAll(entities: List<T>, exec: SuspendSqlExecutor, returning: Boolean, mode: BatchInsertMode): List<T> {
        if (entities.isEmpty()) return emptyList()
        val statements = buildBatchStatements(entities, mode, exec.dialect, exec.typeMapper, returning)
        if (!returning) {
            for (s in statements) exec.executeUpdate(sql = s.sql, namedParameters = s.params)
            return entities
        }
        val out = arrayOfNulls<Entity>(entities.size)
        for (s in statements) {
            val rows = exec.execute(s.sql, s.params) { rs -> mapToDao(rs, exec.typeMapper) }
            rows.forEachIndexed { k, row -> out[s.indices[k]] = row }
        }
        @Suppress("UNCHECKED_CAST")
        return out.toList() as List<T>
    }

    internal suspend fun upsert(entity: T, conflict: List<Column<*, *, *>>, update: T, exec: SuspendSqlExecutor, returning: Boolean): T? {
        val (sql, params) = upsertSql(entity, conflict, update, exec.dialect, exec.typeMapper, returning)
        if (!returning) {
            exec.executeUpdate(sql = sql, namedParameters = params)
            return entity
        }
        return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
    }

    internal suspend fun insertOrIgnore(entity: T, conflict: List<Column<*, *, *>>, exec: SuspendSqlExecutor): Long {
        val (sql, params) = insertOrIgnoreSql(entity, conflict, exec.dialect, exec.typeMapper)
        return exec.executeUpdate(sql = sql, namedParameters = params)
    }

    internal suspend fun count(query: Query, exec: SuspendSqlExecutor): Long {
        val (sql, params) = countSql(query, exec.dialect, exec.typeMapper)
        return exec.execute(sql, params) { rs -> rs.getLong(0) ?: 0L }.firstOrNull() ?: 0L
    }

    internal suspend fun updateRows(query: Query, entity: T, exec: SuspendSqlExecutor): Long {
        val (sql, params) = updateSql(query, entity, exec.dialect, exec.typeMapper)
        return exec.executeUpdate(sql = sql, namedParameters = params)
    }

    internal suspend fun deleteRows(query: Query, exec: SuspendSqlExecutor): Long {
        val (sql, params) = deleteSql(query, exec.dialect, exec.typeMapper)
        return exec.executeUpdate(sql = sql, namedParameters = params)
    }
}
