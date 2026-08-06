import io.github.kormium.postgres.resultset.decodePgBytea
import io.github.kormium.postgres.resultset.encodePgBytea
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The `bytea` text codec, in isolation — no database needed.
 *
 * Regression: `getBytes` used to be `getString()?.encodeToByteArray()`, which re-encoded
 * PostgreSQL's text *encoding* of the value as UTF-8 instead of decoding it, so reading a
 * `Column.Bytes` returned the bytes of the literal `\x48656c6c6f` rather than `Hello`. The
 * write side was equally wrong: a `ByteArray` reached the wire through `toString()`, storing
 * an object identity. Neither direction had any test.
 */
@OptIn(ExperimentalForeignApi::class)
class PgByteaCodecTest {

    private inline fun <R> withCString(s: String, block: (CPointer<ByteVar>) -> R): R =
        memScoped { block(s.cstr.getPointer(this)) }

    // ---- encoding ----

    @Test
    fun encodesToHexFormat() {
        assertEquals("\\x48656c6c6f", encodePgBytea("Hello".encodeToByteArray()))
    }

    @Test
    fun encodesEmptyArrayToBareMarker() {
        assertEquals("\\x", encodePgBytea(ByteArray(0)))
    }

    @Test
    fun encodesHighBytesAsTwoLowercaseHexDigitsEach() {
        // 0x00 and 0xFF must not lose their leading digit or pick up a sign.
        assertEquals("\\x00ff7f80", encodePgBytea(byteArrayOf(0, -1, 127, -128)))
    }

    // ---- decoding, hex format (bytea_output = hex, the default) ----

    @Test
    fun decodesHexFormat() {
        withCString("\\x48656c6c6f") { assertContentEquals("Hello".encodeToByteArray(), decodePgBytea(it)) }
    }

    @Test
    fun decodesEmptyHexValue() {
        withCString("\\x") { assertContentEquals(ByteArray(0), decodePgBytea(it)) }
    }

    @Test
    fun decodesUppercaseHexDigits() {
        withCString("\\xDEADBEEF") {
            assertContentEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()), decodePgBytea(it))
        }
    }

    // ---- decoding, escape format (bytea_output = escape) ----

    @Test
    fun decodesEscapeFormatPrintableBytes() {
        withCString("Hello") { assertContentEquals("Hello".encodeToByteArray(), decodePgBytea(it)) }
    }

    @Test
    fun decodesEscapeFormatOctalAndBackslash() {
        // \\ is one backslash byte; \000 and \377 are octal escapes for 0x00 and 0xFF.
        withCString("""a\\b\000\377""") {
            assertContentEquals(
                byteArrayOf('a'.code.toByte(), 0x5C, 'b'.code.toByte(), 0x00, 0xFF.toByte()),
                decodePgBytea(it),
            )
        }
    }

    // ---- round trip ----

    @Test
    fun everyByteValueSurvivesARoundTrip() {
        val all = ByteArray(256) { (it - 128).toByte() }
        withCString(encodePgBytea(all)) { assertContentEquals(all, decodePgBytea(it)) }
    }

    @Test
    fun encodingIsWhatTheOldCodeWouldHaveMisreadAsTheValue() {
        // Pins the actual defect: the encoded text and the value are different byte strings,
        // so re-encoding the text (the old getBytes) could never produce the value.
        val value = "Hello".encodeToByteArray()
        val encoded = encodePgBytea(value)
        assertContentEquals(byteArrayOf(0x48, 0x65, 0x6c, 0x6c, 0x6f), value)
        assertEquals(12, encoded.length)
        assertEquals(12, encoded.encodeToByteArray().size, "the old path returned this many bytes, not 5")
    }
}
