package io.github.kormium

import io.github.kormium.resultset.ResultSet

private val logger = kormiumLogger()

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
    /**
     * Builds an entity from a loaded field map (the database read path). Fails fast at the
     * database boundary when a column the entity declares non-null came back as SQL NULL — that
     * is a schema mismatch or a bad row, and would otherwise surface as a confusing null only when
     * the property is later read. Nullable columns hydrate NULL normally.
     *
     * [absentFields] are entity fields that were not present in the result at all (e.g. a column a
     * projection/join did not select), as opposed to a column that was selected and came back NULL;
     * the two get different, actionable messages.
     */
    internal fun hydrate(fields: MutableMap<String, Any?>, absentFields: Set<String> = emptySet()): T {
        for ((fieldName, column) in fieldDisplayName) {
            if (!column.nullable && fields[fieldName] == null) {
                val expected = column.columnType.description
                throw ResultMappingException(
                    if (fieldName in absentFields) {
                        "Column '${column.name}' of table '$tableName' is non-null but was not selected " +
                            "(entity field '$fieldName', expected Korm type $expected). Add it to the " +
                            "projection/SELECT, or read the full row, before mapping into this entity."
                    } else {
                        "Column '${column.name}' of table '$tableName' is non-null, but the database " +
                            "returned NULL for it (entity field '$fieldName', expected Korm type $expected). " +
                            "The row or the schema does not match the entity definition."
                    },
                )
            }
        }
        return factory().also { it.replaceFields(fields) }
    }

    // Columns carry their entity type T, so a conflict-target type like `Column<*, *, T>` can
    // reject a column from another (differently-typed) table at compile time.
    private val fieldDisplayName: MutableMap<String, Column<*, *, T>> = mutableMapOf()

    /** The table's columns keyed by entity field name (Kotlin property name), in declaration order. */
    fun getFieldDisplayNames(): Map<String, Column<*, *, *>> = fieldDisplayName

    /**
     * The primary-key column(s): those declared with `primaryKey = true`, or the column
     * named "id" if none are marked.
     */
    val primaryKey: List<Column<*, *, T>>
        get() = fieldDisplayName.values.filter { it.isPrimaryKey }
            .ifEmpty { fieldDisplayName.values.filter { it.fieldKey == "id" } }
    internal fun addColumn(fieldName: String, column: Column<*, *, T>) {
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
            fields[fieldName] = readColumn(column, fieldName, rs, index)
            index++
        }
        return hydrate(fields)
    }

    // Reads one column's value, wrapping any backend/conversion failure in a ResultMappingException
    // that names the table, SQL column, entity field, expected Korm type and result index — and
    // preserves the original error as the cause.
    private fun readColumn(column: Column<*, *, T>, fieldName: String, rs: ResultSet, index: Int): Any? =
        try {
            column.columnType.read(rs, index)
        } catch (e: ResultMappingException) {
            throw e
        } catch (e: Throwable) {
            throw ResultMappingException(
                "Failed to read column '${column.name}' of table '$tableName' (entity field '$fieldName', " +
                    "expected Korm type ${column.columnType.description}, result index $index): ${e.message}",
                cause = e,
            )
        }

    // Only the columns the entity actually assigned (present in its fields map),
    // so update() can tell "leave untouched" (absent) from "set to NULL" (present and null).
    private fun generatePresentFields(dao: T): List<Pair<String, Any?>> {
        return this.fieldDisplayName.filter { dao.fields.containsKey(it.key) }.map {
            it.value.name to it.value.bindParam(dao.fields[it.key])
        }
    }

    // ---- pure SQL builders (no I/O) — shared by the blocking and suspend runners ----

    // Re-selects a just-written row by its primary key, for backends without RETURNING (MySQL):
    // the insert/upsert runs first, then this reads the stored row back (DB defaults applied).
    private fun selectByPkSql(entity: T, dialect: Dialect, typeMapper: TypeMapper): Pair<String, Map<String, Any?>> {
        val pk = primaryKey
        check(pk.isNotEmpty()) {
            "returning=true on $tableName needs a primary key: this backend has no RETURNING, " +
                "so the written row is re-selected by primary key"
        }
        val builder = paramBuilder(dialect, typeMapper)
        val where = pk.joinToString(" AND ") { col ->
            require(entity.fields.containsKey(col.fieldKey)) {
                "returning=true on $tableName needs the primary-key column \"${col.name}\" set on the " +
                    "entity (this backend re-selects the written row by primary key; it has no RETURNING)"
            }
            "${dialect.quoteIdentifier(col.name)} = ${builder.bind(col.bindParam(entity.fields[col.fieldKey]))}"
        }
        val sql = "SELECT ${getColumnNames(dialect).joinToString(", ")} FROM ${qualifiedTableName(dialect)} WHERE $where"
        return sql.trimIndent() to builder.params
    }

    internal fun selectSql(query: Query, dialect: Dialect, typeMapper: TypeMapper): Pair<String, Map<String, Any?>> {
        val builder = paramBuilder(dialect, typeMapper)
        val queryStr = query.toSql(builder)
        val sql = "SELECT ${getColumnNames(dialect).joinToString(", ")} FROM ${qualifiedTableName(dialect)} $queryStr"
        return sql.trimIndent() to builder.params
    }

    internal fun selectAllSql(dialect: Dialect): String =
        "SELECT ${getColumnNames(dialect).joinToString(", ")} FROM ${qualifiedTableName(dialect)}".trimIndent()

    internal fun insertSql(entity: T, dialect: Dialect, typeMapper: TypeMapper, returning: Boolean): Pair<String, Map<String, Any?>> {
        val builder = paramBuilder(dialect, typeMapper)
        // Only the present fields go into the INSERT: an absent field is omitted (so the
        // database can apply its default / generated value), an explicit null is bound as NULL.
        val presentFields = generatePresentFields(entity)
        val base = if (presentFields.isEmpty()) {
            dialect.renderInsertDefaultValues(qualifiedTableName(dialect))
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
    internal class BatchStatement(val sql: String, val params: Map<String, Any?>, val indices: List<Int>)

    internal fun buildBatchStatements(
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
                        dialect.renderInsertDefaultValues(qualifiedTableName(dialect)) + returningSuffix,
                        emptyMap(),
                        listOf(idx),
                    )
                }
            } else {
                val builder = paramBuilder(dialect, typeMapper)
                val colSql = group.columns.joinToString(", ") { dialect.quoteIdentifier(it.name) }
                val tuples = group.entityIndices.joinToString(", ") { idx ->
                    val entity = entities[idx]
                    "(${group.columns.joinToString(", ") { col -> builder.bind(col.bindParam(entity.fields[col.fieldKey])) }})"
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

    // Runtime backstop for the conflict target. The DSL overloads already constrain `onConflict`
    // to `Column<*, *, T>`, so a column from a *differently-typed* table is rejected at compile
    // time. This still guards what types can't express: a non-empty target, and a column from a
    // different table that happens to share this entity type (or direct internal calls).
    private fun validateConflictTarget(op: String, conflict: List<Column<*, *, *>>) {
        require(conflict.isNotEmpty()) { "$op() conflict target must contain at least one column on '$tableName'" }
        for (column in conflict) {
            require(column.tableRef === this) {
                "$op() conflict column '${column.name}' belongs to table '${column.tableRef.tableName}', " +
                    "not '$tableName' — pass conflict columns of the table being written"
            }
        }
    }

    internal fun upsertSql(
        entity: T,
        conflict: List<Column<*, *, *>>,
        update: T,
        dialect: Dialect,
        typeMapper: TypeMapper,
        returning: Boolean,
    ): Pair<String, Map<String, Any?>> {
        val builder = paramBuilder(dialect, typeMapper)
        validateConflictTarget("upsert", conflict)
        val insertFields = generatePresentFields(entity)
        require(insertFields.isNotEmpty()) { "upsert() needs at least one field set on the insert entity" }
        val columns = insertFields.joinToString(", ") { dialect.quoteIdentifier(it.first) }
        val values = insertFields.joinToString(", ") { builder.bind(it.second) }
        val conflictCols = conflict.map { dialect.quoteIdentifier(it.name) }
        val updateFields = generatePresentFields(update)
        require(updateFields.isNotEmpty()) { "upsert() needs at least one field set on the update entity" }
        val setClause = updateFields.joinToString(", ") { "${dialect.quoteIdentifier(it.first)} = ${builder.bind(it.second)}" }
        val base = "INSERT INTO ${qualifiedTableName(dialect)} ($columns) VALUES ($values) " +
            dialect.renderUpsertSuffix(conflictCols, setClause)
        val sql = if (returning) "$base RETURNING ${getColumnNames(dialect).joinToString(", ")}" else base
        return sql to builder.params
    }

    internal fun insertOrIgnoreSql(
        entity: T,
        conflict: List<Column<*, *, *>>,
        dialect: Dialect,
        typeMapper: TypeMapper,
    ): Pair<String, Map<String, Any?>> {
        val builder = paramBuilder(dialect, typeMapper)
        validateConflictTarget("insertOrIgnore", conflict)
        val insertFields = generatePresentFields(entity)
        require(insertFields.isNotEmpty()) { "insertOrIgnore() needs at least one field set on the entity" }
        val columns = insertFields.joinToString(", ") { dialect.quoteIdentifier(it.first) }
        val values = insertFields.joinToString(", ") { builder.bind(it.second) }
        val conflictCols = conflict.map { dialect.quoteIdentifier(it.name) }
        return "INSERT INTO ${qualifiedTableName(dialect)} ($columns) VALUES ($values) " +
            dialect.renderInsertOrIgnoreSuffix(conflictCols) to builder.params
    }

    internal fun countSql(query: Query, dialect: Dialect, typeMapper: TypeMapper): Pair<String, Map<String, Any?>> {
        val builder = paramBuilder(dialect, typeMapper)
        // Count the rows matching the predicate only: ORDER BY / LIMIT / OFFSET must not apply
        // to an aggregate (an OFFSET would skip the single COUNT row and read as 0).
        val queryStr = query.toWhereSql(builder)
        val sql = "SELECT COUNT(*) FROM ${qualifiedTableName(dialect)} $queryStr"
        return sql.trimIndent() to builder.params
    }

    internal fun updateSql(query: Query, entity: T, dialect: Dialect, typeMapper: TypeMapper): Pair<String, Map<String, Any?>> {
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

    internal fun updateSql(query: Query, assignments: Map<Column<*, *, *>, Expression>, dialect: Dialect, typeMapper: TypeMapper): Pair<String, Map<String, Any?>> {
        val builder = paramBuilder(dialect, typeMapper)
        // Order matters: render SET (collecting binds) before WHERE so placeholders are numbered
        // left-to-right as they appear in the statement.
        val setClause = assignments.entries.joinToString(", ") { (column, expr) ->
            "${dialect.quoteIdentifier(column.name)} = ${expr.toSql(builder)}"
        }
        // WHERE only: a plain UPDATE doesn't take ORDER BY / LIMIT / OFFSET (invalid in Postgres).
        val queryStr = query.toWhereSql(builder)
        val sql = """
            UPDATE ${qualifiedTableName(dialect)}
            SET $setClause
           $queryStr
        """
        return sql.trimIndent() to builder.params
    }

    internal fun deleteSql(query: Query, dialect: Dialect, typeMapper: TypeMapper): Pair<String, Map<String, Any?>> {
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

    internal fun select(query: Query, exec: SqlExecutor): List<T> {
        val (sql, params) = selectSql(query, exec.dialect, exec.typeMapper)
        return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }
    }

    internal fun selectAll(exec: SqlExecutor): List<T> =
        exec.execute(selectAllSql(exec.dialect)) { rs -> mapToDao(rs, exec.typeMapper) }

    internal fun insert(entity: T, exec: SqlExecutor, returning: Boolean): T? {
        val dialect = exec.dialect
        val emitReturning = returning && dialect.supportsReturning
        val (sql, params) = insertSql(entity, dialect, exec.typeMapper, emitReturning)
        if (returning && emitReturning) {
            return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
        }
        exec.executeUpdate(sql = sql, namedParameters = params)
        if (!returning) return entity
        // No RETURNING (MySQL): re-select the stored row by primary key.
        val (selSql, selParams) = selectByPkSql(entity, dialect, exec.typeMapper)
        return exec.execute(selSql, selParams) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
    }

    internal fun insertAll(entities: List<T>, exec: SqlExecutor, returning: Boolean, mode: BatchInsertMode): List<T> {
        if (entities.isEmpty()) return emptyList()
        val dialect = exec.dialect
        val emitReturning = returning && dialect.supportsReturning
        val statements = buildBatchStatements(entities, mode, dialect, exec.typeMapper, emitReturning)
        if (returning && emitReturning) {
            val out = arrayOfNulls<Entity>(entities.size)
            for (s in statements) {
                val rows = exec.execute(s.sql, s.params) { rs -> mapToDao(rs, exec.typeMapper) }
                rows.forEachIndexed { k, row -> out[s.indices[k]] = row }
            }
            @Suppress("UNCHECKED_CAST")
            return out.toList() as List<T>
        }
        for (s in statements) exec.executeUpdate(sql = s.sql, namedParameters = s.params)
        if (!returning) return entities
        // No RETURNING (MySQL): re-select every row by primary key, in input order.
        return entities.map { entity ->
            val (selSql, selParams) = selectByPkSql(entity, dialect, exec.typeMapper)
            exec.execute(selSql, selParams) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
                ?: error("inserted row not found re-selecting by primary key in $tableName")
        }
    }

    internal fun upsert(entity: T, conflict: List<Column<*, *, *>>, update: T, exec: SqlExecutor, returning: Boolean): T? {
        val dialect = exec.dialect
        val emitReturning = returning && dialect.supportsReturning
        val (sql, params) = upsertSql(entity, conflict, update, dialect, exec.typeMapper, emitReturning)
        if (returning && emitReturning) {
            return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
        }
        exec.executeUpdate(sql = sql, namedParameters = params)
        if (!returning) return entity
        // No RETURNING (MySQL): re-select the upserted row by primary key.
        val (selSql, selParams) = selectByPkSql(entity, dialect, exec.typeMapper)
        return exec.execute(selSql, selParams) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
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

    internal fun updateRows(query: Query, assignments: Map<Column<*, *, *>, Expression>, exec: SqlExecutor): Long {
        val (sql, params) = updateSql(query, assignments, exec.dialect, exec.typeMapper)
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

    internal suspend fun select(query: Query, exec: SuspendSqlExecutor): List<T> {
        val (sql, params) = selectSql(query, exec.dialect, exec.typeMapper)
        return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }
    }

    internal suspend fun selectAll(exec: SuspendSqlExecutor): List<T> =
        exec.execute(selectAllSql(exec.dialect)) { rs -> mapToDao(rs, exec.typeMapper) }

    internal suspend fun insert(entity: T, exec: SuspendSqlExecutor, returning: Boolean): T? {
        val dialect = exec.dialect
        val emitReturning = returning && dialect.supportsReturning
        val (sql, params) = insertSql(entity, dialect, exec.typeMapper, emitReturning)
        if (returning && emitReturning) {
            return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
        }
        exec.executeUpdate(sql = sql, namedParameters = params)
        if (!returning) return entity
        val (selSql, selParams) = selectByPkSql(entity, dialect, exec.typeMapper)
        return exec.execute(selSql, selParams) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
    }

    internal suspend fun insertAll(entities: List<T>, exec: SuspendSqlExecutor, returning: Boolean, mode: BatchInsertMode): List<T> {
        if (entities.isEmpty()) return emptyList()
        val dialect = exec.dialect
        val emitReturning = returning && dialect.supportsReturning
        val statements = buildBatchStatements(entities, mode, dialect, exec.typeMapper, emitReturning)
        if (returning && emitReturning) {
            val out = arrayOfNulls<Entity>(entities.size)
            for (s in statements) {
                val rows = exec.execute(s.sql, s.params) { rs -> mapToDao(rs, exec.typeMapper) }
                rows.forEachIndexed { k, row -> out[s.indices[k]] = row }
            }
            @Suppress("UNCHECKED_CAST")
            return out.toList() as List<T>
        }
        for (s in statements) exec.executeUpdate(sql = s.sql, namedParameters = s.params)
        if (!returning) return entities
        return entities.map { entity ->
            val (selSql, selParams) = selectByPkSql(entity, dialect, exec.typeMapper)
            exec.execute(selSql, selParams) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
                ?: error("inserted row not found re-selecting by primary key in $tableName")
        }
    }

    internal suspend fun upsert(entity: T, conflict: List<Column<*, *, *>>, update: T, exec: SuspendSqlExecutor, returning: Boolean): T? {
        val dialect = exec.dialect
        val emitReturning = returning && dialect.supportsReturning
        val (sql, params) = upsertSql(entity, conflict, update, dialect, exec.typeMapper, emitReturning)
        if (returning && emitReturning) {
            return exec.execute(sql, params) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
        }
        exec.executeUpdate(sql = sql, namedParameters = params)
        if (!returning) return entity
        val (selSql, selParams) = selectByPkSql(entity, dialect, exec.typeMapper)
        return exec.execute(selSql, selParams) { rs -> mapToDao(rs, exec.typeMapper) }.firstOrNull()
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

    internal suspend fun updateRows(query: Query, assignments: Map<Column<*, *, *>, Expression>, exec: SuspendSqlExecutor): Long {
        val (sql, params) = updateSql(query, assignments, exec.dialect, exec.typeMapper)
        return exec.executeUpdate(sql = sql, namedParameters = params)
    }

    internal suspend fun deleteRows(query: Query, exec: SuspendSqlExecutor): Long {
        val (sql, params) = deleteSql(query, exec.dialect, exec.typeMapper)
        return exec.executeUpdate(sql = sql, namedParameters = params)
    }
}
