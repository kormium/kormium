package io.github.kormium.postgres.paramsource

import io.github.kormium.postgres.exception.AnonymousClassException
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import io.github.kormium.SqlParameterSource
import kotlin.reflect.KClass


/**
 * Abstract base class for [SqlParameterSource] implementations.
 * Provides registration of SQL types per parameter and a friendly
 * [toString] representation.
 * Concrete subclasses must implement [hasValue] and [getValue].
 */
public abstract class AbstractSqlParameterSource : SqlParameterSource {
    private val sqlTypes: MutableMap<String, UInt> = HashMap()
    private val typeNames: MutableMap<String, String> = HashMap()

    /**
     * Register an SQL type for the given parameter.
     * @param paramName the name of the parameter
     * @param sqlType the SQL type of the parameter
     */
    public fun registerSqlType(paramName: String, sqlType: UInt) {
        sqlTypes[paramName] = sqlType
    }

    public fun registerSqlType(paramName: String, value: Any?) {
        registerSqlType(
            paramName = paramName,
            sqlType = value?.let { oidMap[it::class.simpleName ?: throw AnonymousClassException()] }
                ?: SqlParameterSource.TYPE_UNKNOWN
        )
    }

    /**
     * Register an SQL type for the given parameter.
     * @param paramName the name of the parameter
     * @param typeName the type name of the parameter
     */
    public fun registerTypeName(paramName: String, typeName: String) {
        typeNames[paramName] = typeName
    }

    /**
     * Return the SQL type for the given parameter, if registered.
     * @param paramName the name of the parameter
     * @return the SQL type of the parameter,
     * or `TYPE_UNKNOWN` if not registered
     */
    override fun getSqlType(paramName: String): UInt = sqlTypes[paramName] ?: SqlParameterSource.TYPE_UNKNOWN

    /**
     * Return the type name for the given parameter, if registered.
     * @param paramName the name of the parameter
     * @return the type name of the parameter,
     * or `null` if not registered
     */
    public fun getTypeName(paramName: String): String? = typeNames[paramName]

    /**
     * Enumerate the parameter names and values with their corresponding SQL type if available,
     * or just return the simple `SqlParameterSource` implementation class name otherwise.
     * @since 5.2
     * @see .getParameterNames
     */
    @Suppress("NestedBlockDepth")
    override fun toString(): String {
        val parameterNames: Array<String>? = parameterNames
        return if (parameterNames != null) {
            val array = ArrayList<String>(parameterNames.size)
            for (parameterName in parameterNames) {
                val value = getValue(parameterName)
                /*
                if (value is SqlParameterValue) {
                    value = (value as SqlParameterValue?).getValue()
                }
                 */
                var typeName = getTypeName(parameterName)
                if (typeName == null) {
                    val sqlType = getSqlType(parameterName)
                    if (sqlType != SqlParameterSource.TYPE_UNKNOWN) {
                        typeName = sqlTypeNames[sqlType]
                        if (typeName == null) {
                            typeName = sqlType.toString()
                        }
                    }
                }
                val entry = StringBuilder()
                entry.append(parameterName).append('=').append(value)
                if (typeName != null) {
                    entry.append(" (type:").append(typeName).append(')')
                }
                array.add(entry.toString())
            }
            array.joinToString(
                separator = ", ",
                prefix = this::class.simpleName + " {",
                postfix = "}"
            )
        } else {
            this::class.simpleName!!
        }
    }
}

// Full list here: https://jdbc.postgresql.org/documentation/publicapi/constant-values.html
private val oidMap: Map<String, UInt> = hashMapOf(
    Boolean::class.namedClassName to 16u,
    ByteArray::class.namedClassName to 17u,
    Long::class.namedClassName to 20u,
    Int::class.namedClassName to 23u,
    String::class.namedClassName to 25u,
    Double::class.namedClassName to 701u,
    LocalDate::class.namedClassName to 1082u,
    LocalTime::class.namedClassName to 1083u,
    LocalDateTime::class.namedClassName to 1114u,
    Instant::class.namedClassName to 1184u,
    // intervalOid = 1186u
    // uuidOid = 2950u
)

private val sqlTypeNames: Map<UInt, String> = oidMap.entries.associateBy({ it.value }) { it.key }

private val KClass<*>.namedClassName get() = this.simpleName!!
