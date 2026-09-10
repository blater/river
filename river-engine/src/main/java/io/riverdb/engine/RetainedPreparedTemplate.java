package io.riverdb.engine;

import io.riverdb.engine.sql.SqlPreparedPlan;

/** One budgeted plan/key owned by its live handles; session access is serialized. */
final class RetainedPreparedTemplate {
  static final long ACCOUNTED_BYTES = 64;
  final SqlPreparedPlan plan;
  final String sql;
  final long bytes;
  RetainedPreparedTemplate next;
  int references = 1;

  RetainedPreparedTemplate(SqlPreparedPlan plan, String sql, long bytes) {
    this.plan = plan;
    this.sql = sql;
    this.bytes = bytes;
  }
}
