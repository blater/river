package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;

/** Resolves one block's canonical Boolean predicate against its child schema. */
final class SqlBlockPredicateBinder {
  private final SqlBooleanPredicateBinder predicates =
      new SqlBooleanPredicateBinder();

  StatusCode bind(
      SqlCommand command,
      SqlBlockSchema child,
      BoundSqlStatement bound,
      int block) {
    bound.predicateCount = command.wherePredicates().leafCount();
    return predicates.bindBlockWhere(command, bound, child);
  }
}
