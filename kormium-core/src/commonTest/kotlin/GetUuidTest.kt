import io.github.kormium.resultset.ResultSet
import io.github.kormium.sql.getUUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * `ResultSet.getUUID` — the read path for every UUID column.
 *
 * It takes a fast path for the canonical hyphenated form and falls back to [Uuid.parse] for
 * anything else. The fast path must agree with the stdlib on every accepted value and must
 * never turn a malformed value into a plausible one: a bad UUID has to keep failing loudly,
 * exactly as it did when this delegated to [Uuid.parse] unconditionally.
 */
class GetUuidTest {

    private fun read(text: String?): Uuid? = SingleStringResultSet(text).getUUID(0)

    @Test
    fun nullColumnReadsAsNull() {
        assertNull(read(null))
    }

    @Test
    fun canonicalFormMatchesStdlib() {
        for (sample in listOf(
            "00000000-0000-0000-0000-000000000000",
            "ffffffff-ffff-ffff-ffff-ffffffffffff",
            "00000000-0000-4000-8000-000000000001",
            "123e4567-e89b-12d3-a456-426614174000",
            "deadbeef-0000-4000-8000-feedfacecafe",
        )) {
            assertEquals(Uuid.parse(sample), read(sample), sample)
        }
    }

    @Test
    fun uppercaseAndMixedCaseParseTheSame() {
        val lower = "a1b2c3d4-e5f6-4789-8abc-def012345678"
        assertEquals(Uuid.parse(lower), read(lower.uppercase()))
        assertEquals(Uuid.parse(lower), read("A1b2C3d4-E5f6-4789-8AbC-dEf012345678"))
    }

    @Test
    fun randomValuesRoundTripThroughTheirOwnText() {
        repeat(200) {
            val uuid = Uuid.random()
            assertEquals(uuid, read(uuid.toString()))
        }
    }

    @Test
    fun nonHyphenatedFormStillParsesViaTheFallback() {
        // Uuid.parse accepts the bare 32-digit form; the fast path declines it and defers.
        val hex = "123e4567e89b12d3a456426614174000"
        assertEquals(Uuid.parse(hex), read(hex))
    }

    @Test
    fun malformedValuesStillThrow() {
        for (bad in listOf(
            "",                                        // empty
            "not-a-uuid",                              // nonsense
            "123e4567-e89b-12d3-a456-42661417400",     // one digit short
            "123e4567-e89b-12d3-a456-4266141740000",   // one digit long
            "123e4567-e89b-12d3-a456-42661417400g",    // non-hex digit, right length
            "123e4567xe89b-12d3-a456-426614174000",    // hyphen in the wrong place
            "123e4567-e89b-12d3-a456_426614174000",    // wrong separator, right length
        )) {
            assertFailsWith<IllegalArgumentException>(bad) { read(bad) }
        }
    }
}

/** A one-column, one-row result whose only value is [text]. */
private class SingleStringResultSet(private val text: String?) : ResultSet {
    override val columns: Array<String> = arrayOf("id")
    private var consumed = false

    override fun next(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }

    override fun getString(columnIndex: Int): String? = text

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
