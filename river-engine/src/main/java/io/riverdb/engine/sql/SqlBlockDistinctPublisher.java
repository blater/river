package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Atomically prepares the retained DISTINCT key before publishing its row. */
final class SqlBlockDistinctPublisher {
  private SqlBlockDistinctPublisher() { }

  static StatusCode append(
      SqlBlockRow source, SqlBlockRow retained, SqlBlockRowStore output) {
    StatusCode status = retained.copyFrom(source);
    if (status.isOk()) retained.setKey(0);
    return status.isOk() ? output.append(retained) : status;
  }
}
