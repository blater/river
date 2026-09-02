package io.riverdb.engine.table;

/** Primitive recovery evidence for bounded relational mutation suboperations. */
final class IndexedRelationalSuboperations extends IndexedRelationalSuboperationLog {
  IndexedRelationalSuboperations(int capacity) { super(capacity); }
}
