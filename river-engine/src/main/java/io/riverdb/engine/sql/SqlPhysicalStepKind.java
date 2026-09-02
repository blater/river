package io.riverdb.engine.sql;

/** Physical execution family selected for one bound transaction-program step. */
enum SqlPhysicalStepKind {
  COMMAND,
  POINT_PRIMARY,
  POINT_UNIQUE,
  SCAN_SINGLETON,
  ROW_SET,
  AGGREGATE;

  boolean point() {
    return this == POINT_PRIMARY || this == POINT_UNIQUE;
  }
}
