import io.github.kormium.AscDescOrder
import io.github.kormium.ParamBuilder
import io.github.kormium.Query
import io.github.kormium.QueryBuilder
import io.github.kormium.StandardDialect
import io.github.kormium.StandardTypeMapper
import io.github.kormium.and
import io.github.kormium.eq
import io.github.kormium.gtEq
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The block DSL ([QueryBuilder]) is a thin layer over the reusable value API ([Query]):
 * `QueryBuilder.build()` returns a `Query`. These tests pin that equivalence by rendering both
 * forms through the same path and comparing the generated SQL and bind params, so a future block
 * feature that diverges from a plain `Query` representation fails here (issue #25).
 */
class QueryEquivalenceTest {

    private fun rendered(query: Query): Pair<String, Map<String, Any?>> {
        val builder = ParamBuilder(StandardDialect, StandardTypeMapper)
        val sql = query.toSql(builder)
        return sql to builder.params
    }

    private fun build(block: QueryBuilder.() -> Unit): Query = QueryBuilder().apply(block).build()

    @Test
    fun whereOrderByLimitOffsetRenderIdentically() {
        val asValue = Query(
            whereExpression = TestTable.position gtEq 18,
            limit = 50u,
            offset = 100u,
            orderBy = linkedMapOf(TestTable.position to AscDescOrder.DESC),
        )
        val asBlock = build {
            where { TestTable.position gtEq 18 }
            orderBy DESC TestTable.position
            limit = 50
            offset = 100
        }
        assertEquals(rendered(asValue), rendered(asBlock))
    }

    @Test
    fun emptyBlockEqualsBareQuery() {
        assertEquals(rendered(Query()), rendered(build {}))
    }

    @Test
    fun singleWhereRendersIdentically() {
        val asValue = Query(whereExpression = TestTable.text eq "A")
        val asBlock = build { where { TestTable.text eq "A" } }
        assertEquals(rendered(asValue), rendered(asBlock))
    }

    @Test
    fun limitOffsetOnlyRenderIdentically() {
        assertEquals(
            rendered(Query(limit = 10u, offset = 5u)),
            rendered(build { limit = 10; offset = 5 }),
        )
    }

    @Test
    fun multiColumnOrderByPreservesOrder() {
        val asValue = Query(
            orderBy = linkedMapOf(
                TestTable.position to AscDescOrder.DESC,
                TestTable.text to AscDescOrder.ASC,
            ),
        )
        val asBlock = build {
            orderBy DESC TestTable.position
            orderBy ASC TestTable.text
        }
        assertEquals(rendered(asValue), rendered(asBlock))
    }

    /**
     * The one intentional difference: multiple `where { }` blocks are each parenthesized so a
     * block-internal `or` cannot escape its AND-combination. The bind params are identical and
     * the result is semantically equal to `Query(a and b)`; only the defensive parens differ.
     */
    @Test
    fun multipleWhereBlocksAreParenthesizedButParamEquivalent() {
        val asValue = Query(whereExpression = (TestTable.position gtEq 18) and (TestTable.text eq "A"))
        val asBlock = build {
            where { TestTable.position gtEq 18 }
            where { TestTable.text eq "A" }
        }
        // Same bind values in the same order.
        assertEquals(rendered(asValue).second, rendered(asBlock).second)
        // The block form wraps each predicate in parentheses for precedence safety.
        assertTrue("(" in rendered(asBlock).first)
    }
}
