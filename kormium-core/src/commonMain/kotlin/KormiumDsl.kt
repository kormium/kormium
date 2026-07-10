package io.github.kormium

/**
 * Scope-control marker for Kormium's builder DSLs (see [Scope], [SuspendScope], [QueryBuilder]).
 *
 * Because these receivers nest — a `find { }` / `count { }` / `update { }` block runs with a
 * [QueryBuilder] receiver while the surrounding [Scope] receiver stays in lexical scope — Kotlin
 * would otherwise let an outer-scope member be called implicitly from the inner block, e.g.
 * a mutation inside a read-only query block:
 *
 * ```kotlin
 * Users.find {
 *     where { Users.age gtEq 18 }
 *     Users.insert(someUser) // Scope.insert: a compile error with this marker
 * }
 * ```
 *
 * [DslMarker] restricts each block to its nearest receiver, turning such accidental cross-scope
 * calls into compile errors; reaching the outer receiver on purpose still works via an explicit
 * `this@transaction` qualifier.
 */
@DslMarker
public annotation class KormiumDsl
