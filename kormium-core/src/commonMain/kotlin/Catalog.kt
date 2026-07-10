package io.github.kormium

/**
 * Marker for a logical database group. Each distinct backing database is its
 * own [Catalog] type (e.g. `object Main : Catalog`); a
 * [io.github.kormium.database.Database] is tagged with the catalog it connects
 * to, and a [Table] is tagged with the catalog it belongs to. Many database instances
 * may share a catalog (sharding).
 */
public interface Catalog
