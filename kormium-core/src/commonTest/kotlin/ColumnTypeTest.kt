import io.github.kormium.TextColumnType
import io.github.kormium.convert
import io.github.kormium.enumColumnType
import io.github.kormium.jsonColumnType
import io.github.kormium.resultset.ResultSet
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Serializable
private data class Prefs(val theme: String, val pushes: Boolean)

private enum class Role { ADMIN, USER }

// A ResultSet that returns one fixed String for every column (enough to drive the
// text-based getters getString/getJson/getUUID used by these column types).
private class StringRs(private val value: String?) : ResultSet {
    override val columns = arrayOf("c")
    override fun next() = true
    override fun getString(columnIndex: Int) = value
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

class ColumnTypeTest {

    @Test
    fun enumStoresAndReadsByName() {
        val type = enumColumnType<Role>()
        assertEquals("ADMIN", type.toParam(Role.ADMIN))
        assertEquals(Role.ADMIN, type.read(StringRs("ADMIN"), 0))
        assertEquals(null, type.read(StringRs(null), 0))
    }

    @Test
    fun jsonEncodesToElementAndDecodesBack() {
        val type = jsonColumnType<Prefs>()
        val prefs = Prefs("dark", pushes = true)
        val param = type.toParam(prefs)
        assertTrue(param is JsonElement, "json column binds a JsonElement (so Postgres casts ::jsonb)")
        // The JsonElement renders to JSON text; reading parses it back into the value.
        assertEquals(prefs, type.read(StringRs(param.toString()), 0))
    }

    @Test
    fun customConverterMapsBothWays() {
        // Store an Int as a hex string on top of the text column type.
        val hex = TextColumnType.convert<Int, String>(toStored = { it.toString(16) }, fromStored = { it.toInt(16) })
        assertEquals("ff", hex.toParam(255))
        assertEquals(255, hex.read(StringRs("ff"), 0))
    }
}
