# Kormium for AI Developers

This page collects the parts of Kormium that matter when you are building AI / LLM applications —
semantic search over embeddings, storing model output, and working alongside a coding agent. Nothing
here is a separate product; it is the ordinary Kormium DSL pointed at these use cases. Where a feature
is Postgres-only or needs an extension, this page says so plainly.

Two threads run through it:

1. **Semantic search.** A first-class pgvector column type and distance operators, so nearest-neighbour
   retrieval is an ordinary typed query.
2. **Written with an agent.** Typed queries turn a class of runtime SQL mistakes into compile errors,
   and a couple of files (`AGENTS.md`, `llms.txt`) hand a coding agent the idiomatic forms directly.

## Semantic Search with pgvector

Store an embedding in a `Column.Vector` and rank rows by a distance to a query vector. Because a
distance is an ordinary orderable `Double`, K-nearest-neighbour retrieval is a plain ascending
`orderBy` — no special query mode:

```kotlin
object Docs : Table<App, Doc>("docs", ::Doc) {
    val id        by Column.UUID().primaryKey()
    val body      by Column.Text()
    val embedding by Column.Vector(dimensions = 1536)
}
class Doc : Entity() {
    var id        by Docs.id
    var body      by Docs.body
    var embedding by Docs.embedding
}

// embed(...) is YOUR embedding model (OpenAI/Cohere/a local model ...), not a Kormium function — it
// returns a FloatArray / List<Float>. Kormium stores and searches vectors; it does not generate them.
db.transaction {
    Docs.insert(Doc().apply {
        id = Uuid.random(); body = text; embedding = Vector(embed(text))
    })
}

// Retrieve the 5 most similar documents for a question — the retrieval half of RAG:
val context = db.autocommit {
    Docs.find {
        orderBy ASC Docs.embedding.distance(Vector(embed(question)), VectorMetric.COSINE)
        limit = 5
    }
}.map { it.body }
```

`distance(query, metric)` maps to a pgvector operator; `metric` defaults to `COSINE` (the usual choice
for text embeddings). Named aliases read more directly — `cosineDistance(q)`, `euclideanDistance(q)`,
`innerProduct(q)` — and are exactly `distance(q, ...)` underneath. The query vector always binds as a
parameter (never string-interpolated), so it is injection-safe like any other value.

`Vector` wraps a `FloatArray` (build it from a `FloatArray` or a `List<Float>`), stays unboxed for
high-dimensional embeddings, and compares by value. An optional `dimensions` on the column is validated
on every write, so a wrong-length embedding fails fast with a clear message.

See [Tables and entities → Vector columns](tables-and-entities.md#vector-columns-pgvector) for the
column type and [Queries → Vector search](queries.md#vector-search-pgvector) for the full operator
table, radius filters and reading the score back.

### Requirements and scope (read this)

- **pgvector is a third-party PostgreSQL extension**, not part of core Postgres. Install the binary and
  run `CREATE EXTENSION vector` before using a vector column — see the install note in
  [Tables and entities](tables-and-entities.md#vector-columns-pgvector).
- **Kormium does not own DDL.** The extension, the `vector(n)` column and any ANN index (`ivfflat` /
  `hnsw`) are declared in raw SQL or a migration. An index's operator class must match the metric you
  query (`vector_cosine_ops` for `cosineDistance`, etc.) for it to be used.
- **Vector search is Postgres-only** today. MySQL Community has the `VECTOR` type but no distance
  function (it is HeatWave-only), and MariaDB uses a different function syntax; a MariaDB backend is a
  reasonable future step — open an issue if you need it.

## Storing Model Output

LLM output is often semi-structured JSON — tool calls, extracted fields, a scored rubric. Store it in a
JSON column and keep it typed end to end with a `@Serializable` value; it is `jsonb` on Postgres:

```kotlin
@Serializable data class Extraction(val entities: List<String>, val sentiment: Double)

object Runs : Table<App, Run>("runs", ::Run) {
    val id     by Column.UUID().primaryKey()
    val result by Column.json<Extraction>()
}
```

See [Tables and entities → Custom column types](tables-and-entities.md#custom-column-types).

## Written With a Coding Agent

Kormium is designed to be cheap and correct to write *with* a coding agent, not just by hand:

- **Typed queries fail at compile time.** A query is a typed Kotlin expression, so mistakes an agent
  would otherwise ship as runtime SQL bugs — wrong column, wrong type, `'18'` vs `18` — surface as
  compile errors the agent can see and fix in its own loop. There is no hidden persistence context: what
  is in the code is what runs.
- **[`AGENTS.md`](../AGENTS.md)** gives an agent the idiomatic forms up front — schema, reads, writes,
  joins, aggregates, migrations, and copy-ready recipes (including the vector-search recipe). Point your
  agent at it and prefer those forms over any API it might infer.
- **[`llms.txt`](../llms.txt)** is the machine-oriented overview at the repo root, in the
  [llms.txt](https://llmstxt.org/) convention.
- **Stable result keys.** Every projectable expression has a structural `resultKey()`, so a row reads
  back with a freshly built, identical expression (`row[Orders.total.sum()]`) — an agent does not have to
  hoist every projection into a `val` to read it. Reconstruct a whole entity from a join projection with
  `row.entity(Table)`.

## Where to Go Next

- [Quick start](quick-start.md) — first table, connection and CRUD.
- [Queries](queries.md) — predicates, ordering, joins, aggregations, and vector search.
- [Tables and entities](tables-and-entities.md) — column types, including vectors and JSON.
- [Backends](backends.md) — PostgreSQL, SQLite, r2dbc and platform support.
