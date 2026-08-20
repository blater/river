package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Resolves predicates and selects the best reusable access predicate. */
final class SqlPredicateBinder {
  private final SqlBooleanPredicateBinder booleans =
      new SqlBooleanPredicateBinder();
  private final SqlAccessEdgeSelector access = new SqlAccessEdgeSelector();

  StatusCode bind(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound) {
    bound.predicateCount = command.wherePredicates().leafCount();
    bound.accessPredicate = -1;
    bound.predicateColumn = -1;
    bound.pointTextColumn = -1;
    bound.accessComparison = null;
    if (query.hasNestedTopology()) {
      bound.whereBoolean.reset();
      return StatusCode.OK;
    }
    StatusCode status = booleans.bindWhere(command, bound);
    if (status.isOk() && command.wherePredicates().isAvailable()) {
      access.select(command.wherePredicates(), bound.whereBoolean, bound);
    }
    return status;
  }

  StatusCode bindJoin(SqlCommand command, BoundSqlStatement bound) {
    bound.predicateCount = command.wherePredicates().leafCount();
    bound.accessPredicate = -1;
    bound.predicateColumn = -1;
    bound.accessComparison = null;
    StatusCode status = booleans.bindJoinWhere(command, bound);
    if (status.isOk() && command.wherePredicates().isAvailable()) {
      access.select(command.wherePredicates(), bound.whereBoolean, bound);
    }
    return status;
  }

}
