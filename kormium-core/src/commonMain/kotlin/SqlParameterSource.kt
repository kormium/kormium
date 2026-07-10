package io.github.kormium


/**
 * Interface that defines common functionality for objects that can
 * offer parameter values for named SQL parameters.
 *
 * This interface allows for the specification of SQL type in addition
 * to parameter values. All parameter values and types are identified by
 * specifying the name of the parameter.
 *
 */
public interface SqlParameterSource {
    /**
     * Determine whether there is a value for the specified named parameter.
     * @param paramName the name of the parameter
     * @return whether there is a value defined
     */
    public fun hasValue(paramName: String): Boolean

    /**
     * Return the parameter value for the requested named parameter.
     * @param paramName the name of the parameter
     * @return the value of the specified parameter
     * @throws IllegalArgumentException if there is no value for the requested parameter
     */
    @Throws(IllegalArgumentException::class)
    public fun getValue(paramName: String): Any?

    /**
     * Determine the SQL type for the specified named parameter.
     * @param paramName the name of the parameter
     * @return the SQL type of the specified parameter,
     * or `TYPE_UNKNOWN` if not known
     * @see .TYPE_UNKNOWN
     */
    public fun getSqlType(paramName: String): UInt = TYPE_UNKNOWN

    /**
     * Determine the type name for the specified named parameter.
     * @param paramName the name of the parameter
     * @return the type name of the specified parameter,
     * or `null` if not known
     */
    public fun getTypeName(paramName: String?): String? = null

    /**
     * Enumerate all available parameter names if possible.
     */
    public val parameterNames: Array<String>?
        get() = null

    public companion object {
        /**
         * Constant that indicates an unknown (or unspecified) SQL type.
         * To be returned from `getType` when no specific SQL type known.
         * @see getSqlType
         *
         */
        public val TYPE_UNKNOWN: UInt = UInt.MIN_VALUE
        // TODO check if libpq has a default param type oid
    }
}
