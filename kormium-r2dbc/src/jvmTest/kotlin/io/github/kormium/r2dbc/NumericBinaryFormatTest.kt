package io.github.kormium.r2dbc

import io.netty.buffer.Unpooled
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the base-10000 binary `numeric` renderer against hand-built wire frames
 * (the integration suite covers the same path end-to-end against a real server; here the
 * corner shapes are pinned without needing one).
 */
class NumericBinaryFormatTest {

    private fun frame(ndigits: Int, weight: Int, sign: Int, dscale: Int, vararg digits: Int): String {
        val buf = Unpooled.buffer()
        buf.writeShort(ndigits)
        buf.writeShort(weight)
        buf.writeShort(sign)
        buf.writeShort(dscale)
        digits.forEach { buf.writeShort(it) }
        return decodeBinaryNumeric(buf)
    }

    @Test
    fun specials() {
        assertEquals("NaN", frame(0, 0, 0xC000, 0))
        assertEquals("Infinity", frame(0, 0, 0xD000, 0))
        assertEquals("-Infinity", frame(0, 0, 0xF000, 0))
    }

    @Test
    fun zeroKeepsDisplayScale() {
        assertEquals("0", frame(0, 0, 0x0000, 0))
        assertEquals("0.00", frame(0, 0, 0x0000, 2))
    }

    @Test
    fun integers() {
        assertEquals("42", frame(1, 0, 0x0000, 0, 42))
        // 100000000 = 1 × 10000² — the groups after the first must zero-pad to 4 chars.
        assertEquals("100000000", frame(1, 2, 0x0000, 0, 1))
        assertEquals("12345678", frame(2, 1, 0x0000, 0, 1234, 5678))
        assertEquals("-12345678", frame(2, 1, 0x4000, 0, 1234, 5678))
    }

    @Test
    fun fractions() {
        // 12.34 with dscale 2: fraction group 3400 sliced to two characters.
        assertEquals("12.34", frame(2, 0, 0x0000, 2, 12, 3400))
        // Trailing zeros preserved via dscale, groups beyond ndigits render as zeros.
        assertEquals("12.340000", frame(2, 0, 0x0000, 6, 12, 3400))
        // |value| < 1: negative weight puts the first digit group inside the fraction.
        assertEquals("0.0001", frame(1, -1, 0x0000, 4, 1))
        // First group deeper than the first fraction slot: leading fraction zeros synthesized.
        assertEquals("0.00000001", frame(1, -2, 0x0000, 8, 1))
        assertEquals("-0.5000", frame(1, -1, 0x4000, 4, 5000))
    }

    @Test
    fun mixedBoundaries() {
        // 12345678.90 → integer groups 1234|5678, fraction group 9000 sliced to 2.
        assertEquals("12345678.90", frame(3, 1, 0x0000, 2, 1234, 5678, 9000))
        // Integer wider than ndigits: 10000 = 1 × 10000¹ with no second group stored.
        assertEquals("10000", frame(1, 1, 0x0000, 0, 1))
        // Interior zero group is stored explicitly: 10000.5 = [1, 0, 5000] at weight 1.
        assertEquals("10000.5", frame(3, 1, 0x0000, 1, 1, 0, 5000))
    }
}
