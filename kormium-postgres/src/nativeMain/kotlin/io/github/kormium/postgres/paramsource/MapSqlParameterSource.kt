package io.github.kormium.postgres.paramsource


/**
 * [SqlParameterSource] implementation that holds a given Map of parameters.
 *
 * The [addValue] methods on this class will make adding several values
 * easier. The methods return a reference to the [MapSqlParameterSource]
 * itself, so you can chain several method calls together within a single statement.
 */
public class MapSqlParameterSource : AbstractSqlParameterSource {
    private val values: MutableMap<String, Any?> = LinkedHashMap()

    /**
     * Create a new MapSqlParameterSource based on a Map.
     * @param values a Map holding existing parameter values (can be `null`)
     */
    public constructor(values: Map<String, Any?>?) {
        addValues(values)
    }

    /**
     * Add a parameter to this parameter source.
     * @param paramName the name of the parameter
     * @param value the value of the parameter
     * @return a reference to this parameter source,
     * so it's possible to chain several calls together
     */
    public fun addValue(paramName: String, value: Any?): MapSqlParameterSource {
        this.values[paramName] = value
        registerSqlType(paramName, value)
        return this
    }

    /**
     * Add a parameter to this parameter source.
     * @param paramName the name of the parameter
     * @param value the value of the parameter
     * @param sqlType the SQL type of the parameter
     * @return a reference to this parameter source,
     * so it's possible to chain several calls together
     */
    public fun addValue(paramName: String, value: Any?, sqlType: UInt): MapSqlParameterSource {
        this.values[paramName] = value
        registerSqlType(paramName = paramName, sqlType = sqlType)
        return this
    }

    /**
     * Add a parameter to this parameter source.
     * @param paramName the name of the parameter
     * @param value the value of the parameter
     * @param sqlType the SQL type of the parameter
     * @param typeName the type name of the parameter
     * @return a reference to this parameter source,
     * so it's possible to chain several calls together
     */
    public fun addValue(paramName: String, value: Any?, sqlType: UInt, typeName: String?): MapSqlParameterSource {
        this.values[paramName] = value
        registerSqlType(paramName = paramName, sqlType = sqlType)
        registerTypeName(paramName, typeName!!)
        return this
    }

    /**
     * Add a Map of parameters to this parameter source.
     * @param values a Map holding existing parameter values (can be `null`)
     * @return a reference to this parameter source,
     * so it's possible to chain several calls together
     */
    public fun addValues(values: Map<String, Any?>?): MapSqlParameterSource {
        values?.forEach { (key, value) ->
            this.values[key] = value
            registerSqlType(paramName = key, value = value)
        }
        return this
    }

    /**
     * Expose the current parameter values as read-only Map.
     */
    public fun getValues(): Map<String, Any?> = this.values

    override fun hasValue(paramName: String): Boolean = this.values.containsKey(paramName)

    @Suppress("UseRequire")
    override fun getValue(paramName: String): Any? {
        if (!hasValue(paramName)) {
            throw IllegalArgumentException("No value registered for key '$paramName'")
        }
        return this.values[paramName]
    }

    override val parameterNames: Array<String> get() = this.values.keys.toTypedArray()
}

