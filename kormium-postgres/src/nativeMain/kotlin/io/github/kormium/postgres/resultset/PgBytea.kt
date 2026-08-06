package io.github.kormium.postgres.resultset

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get

/**
 * `bytea` text-format codec for the libpq driver.
 *
 * The driver binds and reads every value as text, so a `ByteArray` cannot simply be handed to
 * `toString()` (that yields an object identity) nor read back with `encodeToByteArray()` (that
 * re-encodes the *encoding* rather than decoding it). Both directions go through this codec.
 *
 * PostgreSQL emits `bytea` in one of two text formats, selected by the server's `bytea_output`
 * setting: `hex` (the default since 9.0) and the legacy `escape`. Reading accepts both, so a
 * server configured either way works; writing always emits `hex`, which every supported server
 * version accepts on input.
 */

private const val HEX_DIGITS = "0123456789abcdef"

/** Encodes [bytes] as `\x…`, the hex text format `bytea`'s input function accepts. */
internal fun encodePgBytea(bytes: ByteArray): String {
    val out = StringBuilder(2 + bytes.size * 2)
    out.append("\\x")
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        out.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0xF])
    }
    return out.toString()
}

/**
 * Decodes a `bytea` value straight from libpq's null-terminated C string — no intermediate
 * Kotlin String, matching how the integer getters read.
 */
@ExperimentalForeignApi
internal fun decodePgBytea(p: CPointer<ByteVar>): ByteArray {
    var length = 0
    while (p[length].toInt() != 0) length++

    // Hex format: "\x" followed by two hex digits per byte.
    if (length >= 2 && p[0].toInt() == BACKSLASH && p[1].toInt() == LOWER_X) {
        val out = ByteArray((length - 2) / 2)
        for (i in out.indices) {
            val hi = hexNibble(p[2 + i * 2].toInt())
            val lo = hexNibble(p[3 + i * 2].toInt())
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    // Escape format: `\\` is one backslash, `\ooo` an octal byte, anything else is itself.
    // The decoded value is never longer than its encoding, so one pass into a max-sized
    // buffer and a final trim is enough.
    val out = ByteArray(length)
    var read = 0
    var written = 0
    while (read < length) {
        val c = p[read].toInt()
        if (c != BACKSLASH) {
            out[written++] = c.toByte()
            read++
            continue
        }
        if (p[read + 1].toInt() == BACKSLASH) {
            out[written++] = BACKSLASH.toByte()
            read += 2
            continue
        }
        val o1 = p[read + 1].toInt() - ZERO
        val o2 = p[read + 2].toInt() - ZERO
        val o3 = p[read + 3].toInt() - ZERO
        out[written++] = ((o1 shl 6) or (o2 shl 3) or o3).toByte()
        read += 4
    }
    return if (written == out.size) out else out.copyOf(written)
}

private fun hexNibble(code: Int): Int = when {
    code >= ZERO && code <= NINE -> code - ZERO
    code >= LOWER_A && code <= LOWER_F -> code - LOWER_A + 10
    else -> code - UPPER_A + 10
}

private const val BACKSLASH = 0x5C
private const val LOWER_X = 0x78
private const val ZERO = 0x30
private const val NINE = 0x39
private const val LOWER_A = 0x61
private const val LOWER_F = 0x66
private const val UPPER_A = 0x41
