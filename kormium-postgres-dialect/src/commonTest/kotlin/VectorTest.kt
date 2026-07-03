import io.github.kormium.ColumnType
import io.github.kormium.Operand
import io.github.kormium.ParamBuilder
import io.github.kormium.PostgresDialect
import io.github.kormium.StandardTypeMapper
import io.github.kormium.Vector
import io.github.kormium.VectorColumnType
import io.github.kormium.VectorMetric
import io.github.kormium.cosineDistance
import io.github.kormium.distance
import io.github.kormium.euclideanDistance
import io.github.kormium.innerProduct
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A stand-in vector operand rendering a fixed identifier, so distance rendering is testable without a Table. */
private class FakeVectorColumn(private val sql: String) : Operand<Vector> {
    override val columnType: ColumnType<Vector> = VectorColumnType()
    override fun toSql(builder: ParamBuilder): String = "\"$sql\""
    override fun resultKey(): Any = "docs.$sql"
}

class VectorTest {
    private fun builder() = ParamBuilder(PostgresDialect, StandardTypeMapper)

    @Test
    fun textFormRoundTrips() {
        assertEquals("[1.0,2.0,3.0]", Vector.of(1f, 2f, 3f).toString())
        val parsed = Vector.parse("[1, 2.5, -3]")
        assertEquals(3, parsed.size)
        assertEquals(2.5f, parsed[1])
        // toString of a parsed value is again valid pgvector text.
        assertEquals(Vector.of(1f, 2.5f, -3f), Vector.parse(parsed.toString()))
    }

    @Test
    fun valueEquality() {
        assertEquals(Vector.of(1f, 2f), Vector(listOf(1f, 2f)))
        assertEquals(Vector.of(1f, 2f).hashCode(), Vector(floatArrayOf(1f, 2f)).hashCode())
        assertTrue(Vector.of(1f, 2f) != Vector.of(1f, 3f))
    }

    @Test
    fun parseRejectsMalformed() {
        assertFailsWith<IllegalArgumentException> { Vector.parse("1,2,3") }
    }

    @Test
    fun bindCastsToVector() {
        val placeholder = builder().dialect.renderBind("p0", Vector.of(1f, 2f))
        assertEquals(":p0::vector", placeholder)
    }

    @Test
    fun dimensionMismatchFailsFast() {
        val type = VectorColumnType(dimensions = 3)
        assertFailsWith<IllegalArgumentException> { type.toParam(Vector.of(1f, 2f)) }
        // matching length passes through unchanged
        val v = Vector.of(1f, 2f, 3f)
        assertEquals(v, type.toParam(v))
    }

    @Test
    fun distanceRendersOperatorAndBindsQuery() {
        val embedding = FakeVectorColumn("embedding")
        val query = Vector.of(0.1f, 0.2f, 0.3f)

        val b = builder()
        val sql = embedding.distance(query, VectorMetric.COSINE).toSql(b)
        assertEquals("(\"embedding\" <=> :p0::vector)", sql)
        assertEquals("[0.1,0.2,0.3]", b.params["p0"])
    }

    @Test
    fun namedAliasesMapToOperators() {
        val embedding = FakeVectorColumn("embedding")
        val q = Vector.of(1f, 2f, 3f)
        assertEquals("(\"embedding\" <-> :p0::vector)", embedding.euclideanDistance(q).toSql(builder()))
        assertEquals("(\"embedding\" <=> :p0::vector)", embedding.cosineDistance(q).toSql(builder()))
        assertEquals("(\"embedding\" <#> :p0::vector)", embedding.innerProduct(q).toSql(builder()))
    }

    @Test
    fun distanceDefaultsToCosine() {
        val embedding = FakeVectorColumn("embedding")
        assertEquals(
            embedding.cosineDistance(Vector.of(1f, 2f)).toSql(builder()),
            embedding.distance(Vector.of(1f, 2f)).toSql(builder()),
        )
    }

    @Test
    fun columnToColumnDistanceRenders() {
        val a = FakeVectorColumn("a")
        val b = FakeVectorColumn("b")
        assertEquals("(\"a\" <-> \"b\")", a.euclideanDistance(b).toSql(builder()))
    }

    @Test
    fun distanceResultKeyIsStructural() {
        val embedding = FakeVectorColumn("embedding")
        val query = Vector.of(1f, 2f)
        // Two freshly built, identical distance expressions share a result key.
        assertEquals(
            embedding.cosineDistance(query).resultKey(),
            embedding.cosineDistance(Vector.of(1f, 2f)).resultKey(),
        )
        // Different metric or vector -> different key.
        assertTrue(embedding.cosineDistance(query).resultKey() != embedding.euclideanDistance(query).resultKey())
        assertTrue(embedding.cosineDistance(query).resultKey() != embedding.cosineDistance(Vector.of(1f, 3f)).resultKey())
    }
}
