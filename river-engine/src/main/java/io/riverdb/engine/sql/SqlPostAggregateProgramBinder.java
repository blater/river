package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Binds HAVING through the common bounded Boolean predicate binder. */
final class SqlPostAggregateProgramBinder {
  private final SqlBooleanPredicateBinder predicates =
      new SqlBooleanPredicateBinder();

  StatusCode bind(SqlCommand command, BoundSqlStatement bound) {
    return predicates.bindHaving(command, bound);
  }
}
