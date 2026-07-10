package io.github.kormium.postgres


/**
 * In the future, we might want to support binary format
 * This list is the supported columns for binary, according to:
 * https://github.com/pgjdbc/pgjdbc/blob/master/pgjdbc/src/main/java/org/postgresql/jdbc/PgConnection.java#L393
 */
@Suppress("UnusedPrivateProperty")
private val supportedBinaryOids = hashSetOf(
    Oid.BYTEA,
    Oid.INT2,
    Oid.INT4,
    Oid.INT8,
    Oid.FLOAT4,
    Oid.FLOAT8,
    Oid.TIME,
    Oid.DATE,
    Oid.TIMETZ,
    Oid.TIMESTAMP,
    Oid.TIMESTAMPTZ,
    Oid.BYTEA_ARRAY,
    Oid.INT2_ARRAY,
    Oid.INT4_ARRAY,
    Oid.INT8_ARRAY,
    Oid.OID_ARRAY,
    Oid.FLOAT4_ARRAY,
    Oid.FLOAT8_ARRAY,
    Oid.VARCHAR_ARRAY,
    Oid.TEXT_ARRAY,
    Oid.POINT,
    Oid.BOX,
    Oid.UUID
)


@Suppress("MagicNumber")
public enum class Oid(public val value: Int) {
    UNSPECIFIED(0),
    INT2(21),
    INT2_ARRAY(1005),
    INT4(23),
    INT4_ARRAY(1007),
    INT8(20),
    INT8_ARRAY(1016),
    TEXT(25),
    TEXT_ARRAY(1009),
    NUMERIC(1700),
    NUMERIC_ARRAY(1231),
    FLOAT4(700),
    FLOAT4_ARRAY(1021),
    FLOAT8(701),
    FLOAT8_ARRAY(1022),
    BOOL(16),
    BOOL_ARRAY(1000),
    DATE(1082),
    DATE_ARRAY(1182),
    TIME(1083),
    TIME_ARRAY(1183),
    TIMETZ(1266),
    TIMETZ_ARRAY(1270),
    TIMESTAMP(1114),
    TIMESTAMP_ARRAY(1115),
    TIMESTAMPTZ(1184),
    TIMESTAMPTZ_ARRAY(1185),
    BYTEA(17),
    BYTEA_ARRAY(1001),
    VARCHAR(1043),
    VARCHAR_ARRAY(1015),
    OID(26),
    OID_ARRAY(1028),
    BPCHAR(1042),
    BPCHAR_ARRAY(1014),
    MONEY(790),
    MONEY_ARRAY(791),
    NAME(19),
    NAME_ARRAY(1003),
    BIT(1560),
    BIT_ARRAY(1561),
    VOID(2278),
    INTERVAL(1186),
    INTERVAL_ARRAY(1187),
    CHAR(18),
    CHAR_ARRAY(1002),
    VARBIT(1562),
    VARBIT_ARRAY(1563),
    UUID(2950),
    UUID_ARRAY(2951),
    XML(142),
    XML_ARRAY(143),
    POINT(600),
    POINT_ARRAY(1017),
    BOX(603),
    JSONB(3802),
    JSONB_ARRAY(3807),
    JSON(114),
    JSON_ARRAY(199),
    REF_CURSOR(1790),
    REF_CURSOR_ARRAY(2201);

    private val oidToName: MutableMap<Int, String> = mutableMapOf()
    private val nameToOid: MutableMap<String, Int> = mutableMapOf()

    init {
        Oid.values().forEach {
            oidToName[it.value] = it.name
            nameToOid[it.name] = it.value
        }
    }
}
