import io.github.kormium.ABSENT
import io.github.kormium.Entity
import io.github.kormium.Table

/**
 * Hydrates a row of [this] table from field-name → value pairs.
 *
 * The read path builds an entity's values positionally, indexed by column ordinal, which is
 * awkward to write by hand in a test. This translates the readable name-keyed form into it.
 * A field the map does not mention is left absent — the same state a projection that did not
 * select that column produces.
 */
internal fun <T : Entity> Table<*, T>.hydrateNamed(fields: Map<String, Any?>): T {
    val columns = getFieldDisplayNames()
    val values = Array<Any?>(columns.size) { ABSENT }
    for ((fieldName, column) in columns) {
        if (fields.containsKey(fieldName)) values[column.ordinal] = fields[fieldName]
    }
    return hydrate(values)
}
