package io.github.kormium

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.time.toJavaInstant
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toJavaLocalTime
import kotlinx.serialization.json.JsonElement
import org.postgresql.util.PGobject
import java.time.ZoneOffset
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

/**
 * JVM Postgres parameter mapping: binds values as properly-typed JDBC objects instead of
 * text. pgjdbc then declares the real parameter type (uuid, numeric, timestamptz, ...) when
 * the statement is server-prepared, so the server never re-infers an untyped parameter —
 * re-inference costs an extra protocol round-trip on every execution (wire-traced: an
 * untyped text bind under `stringtype=unspecified` is 2 ReadyForQuery per op, a typed bind
 * is 1 — the shape Hibernate produces, and the whole ~2x read gap to it).
 *
 * The native (libpq) driver keeps text binding — all-text is libpq's protocol mode — with
 * explicit `::type` casts from [PostgresDialect] where inference needs help; r2dbc is
 * natively typed already.
 */
object PostgresJvmTypeMapper : TypeMapper {
    override fun toParameter(value: Any?): Any? = when (value) {
        // StandardTypeMapper would toString() these (text is fine for libpq, wrong here):
        // pass them through so they bind as real float4 / int2.
        is Float, is Short -> value
        is Uuid -> value.toJavaUuid()
        is BigDecimal -> java.math.BigDecimal(value.toString())
        // pgjdbc has no java.time.Instant binding; OffsetDateTime at UTC is the same instant
        // and binds as timestamptz.
        is Instant -> value.toJavaInstant().atOffset(ZoneOffset.UTC)
        is LocalDate -> value.toJavaLocalDate()
        is LocalTime -> value.toJavaLocalTime()
        is LocalDateTime -> value.toJavaLocalDateTime()
        is JsonElement -> PGobject().apply {
            type = "jsonb"
            this.value = value.toString()
        }
        else -> StandardTypeMapper.toParameter(value)
    }
}
