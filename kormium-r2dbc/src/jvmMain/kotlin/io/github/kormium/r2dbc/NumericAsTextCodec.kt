package io.github.kormium.r2dbc

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import io.r2dbc.postgresql.api.PostgresqlConnection
import io.r2dbc.postgresql.client.EncodedParameter
import io.r2dbc.postgresql.codec.Codec
import io.r2dbc.postgresql.codec.CodecRegistry
import io.r2dbc.postgresql.extension.CodecRegistrar
import io.r2dbc.postgresql.message.Format
import java.nio.charset.StandardCharsets
import org.reactivestreams.Publisher
import reactor.core.publisher.Mono

private const val NUMERIC_OID = 1700

/**
 * Registers [NumericAsTextCodec] ahead of the driver's built-ins on every connection.
 * Installed by `createR2dbcDatabase` via `PostgresqlConnectionConfiguration.codecRegistrar`.
 */
internal object NumericAsTextCodecRegistrar : CodecRegistrar {
    override fun register(
        connection: PostgresqlConnection,
        allocator: ByteBufAllocator,
        registry: CodecRegistry,
    ): Publisher<Void> {
        registry.addFirst(NumericAsTextCodec)
        return Mono.empty()
    }
}

/**
 * Decodes a `numeric` column to its PostgreSQL text form when the caller asks for a
 * [String] — the read [io.github.kormium.resultset.ResultSet.getString] performs for
 * decimal columns.
 *
 * The driver's own path routes `numeric` through `java.math.BigDecimal`, which cannot
 * represent the `NaN` / `±Infinity` values PostgreSQL `numeric` stores
 * (pgjdbc#1941 is the same defect class), so a non-finite value blows up the row read.
 * This codec bypasses `BigDecimal` entirely: the text wire format passes through verbatim,
 * and the binary wire format (what the extended protocol actually ships) is rendered from
 * PostgreSQL's base-10000 representation exactly like the server's own `numeric_out`.
 *
 * Decode-only: parameter encoding stays with the driver's built-in codecs.
 */
internal object NumericAsTextCodec : Codec<String> {

    override fun canDecode(dataType: Int, format: Format, type: Class<*>): Boolean =
        dataType == NUMERIC_OID && type == String::class.java

    override fun decode(buffer: ByteBuf, dataType: Int, format: Format, type: Class<out String>): String =
        when (format) {
            Format.FORMAT_TEXT -> buffer.toString(StandardCharsets.US_ASCII)
            Format.FORMAT_BINARY -> decodeBinaryNumeric(buffer)
        }

    override fun canEncode(value: Any): Boolean = false
    override fun canEncodeNull(type: Class<*>): Boolean = false
    override fun encode(value: Any): EncodedParameter = throw UnsupportedOperationException("decode-only codec")
    override fun encode(value: Any, dataType: Int): EncodedParameter = throw UnsupportedOperationException("decode-only codec")
    override fun encodeNull(): EncodedParameter = throw UnsupportedOperationException("decode-only codec")
}

// PostgreSQL numeric binary wire format (see numeric.c): four int16 header words —
// ndigits, weight (of the first base-10000 digit), sign, dscale — then ndigits base-10000
// digits, most significant first. Sign words:
private const val NUMERIC_POS = 0x0000
private const val NUMERIC_NEG = 0x4000
private const val NUMERIC_NAN = 0xC000
private const val NUMERIC_PINF = 0xD000
private const val NUMERIC_NINF = 0xF000

/** Renders the binary form to the same text `numeric_out` / `::text` produces. */
internal fun decodeBinaryNumeric(buffer: ByteBuf): String {
    val ndigits = buffer.readShort().toInt()
    val weight = buffer.readShort().toInt()
    val sign = buffer.readUnsignedShort()
    val dscale = buffer.readShort().toInt() and 0x3FFF
    when (sign) {
        NUMERIC_NAN -> return "NaN"
        NUMERIC_PINF -> return "Infinity"
        NUMERIC_NINF -> return "-Infinity"
    }
    val digits = ShortArray(ndigits) { buffer.readShort() }
    val sb = StringBuilder(4 * (ndigits + 1) + 2)
    if (sign == NUMERIC_NEG) sb.append('-')
    // Integer part: base-10000 digits 0..weight; the first group prints without left padding,
    // later groups are always 4 characters. A negative weight means |value| < 1.
    if (weight < 0) {
        sb.append('0')
    } else {
        for (d in 0..weight) {
            val digit = if (d < ndigits) digits[d].toInt() else 0
            if (d == 0) sb.append(digit) else appendPadded(sb, digit)
        }
    }
    // Fraction: exactly dscale characters, sliced from the base-10000 groups after the point.
    if (dscale > 0) {
        sb.append('.')
        var produced = 0
        var index = weight + 1
        while (produced < dscale) {
            val digit = if (index in 0 until ndigits) digits[index].toInt() else 0
            val take = minOf(4, dscale - produced)
            appendPadded(sb, digit, take)
            produced += take
            index++
        }
    }
    return sb.toString()
}

/** Appends the first [take] characters of [digit] rendered as 4 zero-padded decimals. */
private fun appendPadded(sb: StringBuilder, digit: Int, take: Int = 4) {
    var divisor = 1000
    var remaining = digit
    repeat(take) {
        sb.append('0' + remaining / divisor)
        remaining %= divisor
        divisor /= 10
    }
}
