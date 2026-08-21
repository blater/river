package io.riverdb.engine.sql;

/** Bound physical strategy for one logical JOIN stage. */
final class SqlJoinStrategy {
  static final int NESTED_LOOP = 1;
  static final int HASH = 2;
  static final int MERGE = 3;

  private SqlJoinStrategy() {
  }
}
