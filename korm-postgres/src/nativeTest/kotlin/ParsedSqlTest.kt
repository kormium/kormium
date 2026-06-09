package io.github.moreirasantos.pgkn.sql

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression tests for the native named-parameter parser, focused on string-literal
 * handling. `skipCommentsAndQuotes` toggles in/out of a string on each `'`, so a doubled
 * `''` (SQL-standard escaped quote) is correctly treated as still-inside the literal.
 */
class ParsedSqlTest {

    @Test
    fun escapedQuoteInLiteralIsNotAParameter() {
        // Regression (#47): ':not_param' is inside the literal 'it''s :not_param'.
        val parsed = parseSql("SELECT 'it''s :not_param' AS v")
        assertEquals(emptyList(), parsed.parameterNames)
    }

    @Test
    fun multipleEscapedQuotesThenRealParam() {
        val parsed = parseSql("SELECT 'a''b''c :x' AS v, :real FROM t")
        assertEquals(listOf("real"), parsed.parameterNames)
    }

    @Test
    fun plainNamedParameterIsParsed() {
        val parsed = parseSql("SELECT * FROM t WHERE id = :id")
        assertEquals(listOf("id"), parsed.parameterNames)
    }
}
