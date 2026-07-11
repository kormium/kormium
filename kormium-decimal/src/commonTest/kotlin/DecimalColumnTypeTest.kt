import io.github.kormium.StandardTypeMapper
import io.github.kormium.decimal.Decimal
import io.github.kormium.decimal.DecimalColumnType
import io.github.kormium.resultset.ResultSet
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

private class SingleStringResultSet(private val value: String?) : ResultSet {
    override val columns: Array<String> = arrayOf("value")
    override fun next(): Boolean = true
    override fun getString(columnIndex: Int): String? = value
    override fun getBoolean(columnIndex: Int): Boolean? = null
    override fun getShort(columnIndex: Int): Short? = null
    override fun getInt(columnIndex: Int): Int? = null
    override fun getLong(columnIndex: Int): Long? = null
    override fun getFloat(columnIndex: Int): Float? = null
    override fun getDouble(columnIndex: Int): Double? = null
    override fun getBytes(columnIndex: Int): ByteArray? = null
    override fun getDate(columnIndex: Int): LocalDate? = null
    override fun getTime(columnIndex: Int): LocalTime? = null
    override fun getLocalDateTime(columnIndex: Int): LocalDateTime? = null
    override fun getInstant(columnIndex: Int): Instant? = null
}

class DecimalColumnTypeTest {

    @Test
    fun readsDecimalTextFromTheResultSet() {
        assertEquals(Decimal.parse("10.50"), DecimalColumnType.read(SingleStringResultSet("10.50"), 0))
        assertEquals(Decimal.parse("-0.007"), DecimalColumnType.read(SingleStringResultSet("-0.007"), 0))
    }

    @Test
    fun readsSqlNullAsNull() {
        assertNull(DecimalColumnType.read(SingleStringResultSet(null), 0))
    }

    @Test
    fun paramRendersAsDecimalTextThroughStandardTypeMapper() {
        // Platform-independent contract: whatever toParam yields (java.math.BigDecimal on the
        // JVM, Decimal elsewhere), the text-binding backends end up with the same decimal text.
        val param = DecimalColumnType.toParam(Decimal.parse("10.50"))
        assertEquals("10.50", StandardTypeMapper.toParameter(param))
    }

    @Test
    fun description() {
        assertEquals("Decimal", DecimalColumnType.description)
    }
}
