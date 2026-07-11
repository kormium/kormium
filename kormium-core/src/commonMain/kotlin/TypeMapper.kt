package io.github.kormium

/** Backend-specific conversion of a bound value to the driver's wire form. */
public interface TypeMapper {
    /** Converts [value] to the form bound as a parameter (e.g. UUID/Decimal → text). */
    public fun toParameter(value: Any?): Any?
}

/**
 * Text-based mapping shared by drivers that bind values as text (libpq, and JDBC with
 * `stringtype=unspecified`). Non-primitive values are sent through toString(). Reading is
 * handled per column by [ColumnType.read], not here.
 */
public object StandardTypeMapper : TypeMapper {
    override fun toParameter(value: Any?): Any? = when (value) {
        // ByteArray passes through so backends can bind it as binary (blob/bytea) rather than text.
        null, is Boolean, is Int, is Long, is Double, is String, is ByteArray -> value
        else -> value.toString()
    }
}
