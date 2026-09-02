package io.riverdb.engine.sql;

/** Caller-owned exact page reservation for one materialized sort operator. */
final class SqlMaterializedSortReservation {
  private SqlMaterializedStatement statement;
  private int pages;

  boolean available() { return statement == null; }

  void attach(SqlMaterializedStatement owner, int count) {
    statement = owner;
    pages = count;
  }

  boolean ownedBy(SqlMaterializedStatement owner) { return statement == owner; }
  int pages() { return pages; }

  void clear() {
    statement = null;
    pages = 0;
  }
}
