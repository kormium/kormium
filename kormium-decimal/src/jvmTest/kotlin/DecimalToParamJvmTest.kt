import io.github.kormium.decimal.Decimal
import io.github.kormium.decimal.DecimalColumnType
import kotlin.test.Test
import kotlin.test.assertEquals

class DecimalToParamJvmTest {

    @Test
    fun bindsAsJavaBigDecimalOnTheJvm() {
        // JDBC/r2dbc drivers declare a typed numeric parameter for java.math.BigDecimal;
        // PostgresJvmTypeMapper/MySqlJvmTypeMapper pass it through untouched.
        val param = DecimalColumnType.toParam(Decimal.parse("10.50"))
        assertEquals(java.math.BigDecimal("10.50"), param)
    }
}
