package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Resolves predicates and selects the best reusable access predicate. */
final class SqlPredicateBinder {
  private final SqlBooleanPredicateBinder booleans =
      new SqlBooleanPredicateBinder();
  private final SqlAccessEdgeSelector access = new SqlAccessEdgeSelector();
  private final SqlJoinAccessSelector joinAccess = new SqlJoinAccessSelector();

  StatusCode bind(
      SqlCommand command, SqlQuery query, BoundSqlStatement bound) {
    bound.predicateCount = command.wherePredicates().leafCount();
    bound.accessPredicate = -1;
    bound.predicateColumn = -1;
    bound.pointTextColumn = -1;
    bound.accessComparison = null;
    StatusCode status = booleans.bindWhere(command, bound);
    if (status.isOk() && command.wherePredicates().isAvailable()) {
      access.select(command.wherePredicates(), bound.whereBoolean, bound);
    }
    return status;
  }

  StatusCode bindJoin(SqlCommand command, BoundSqlStatement bound) {
    bound.predicateCount = command.onPredicates().leafCount()
        + command.wherePredicates().leafCount();
    bound.accessPredicate = -1;
    bound.predicateColumn = -1;
    bound.accessComparison = null;
    bound.joinOuterColumn = -1;
    bound.joinInnerColumn = -1;
    StatusCode status = booleans.bindJoinOn(command, bound);
    if (status.isOk()) status = booleans.bindJoinWhere(command, bound);
    if (status.isOk() && command.wherePredicates().isAvailable()) {
      access.select(command.wherePredicates(), bound.whereBoolean, bound);
    }
    if (status.isOk()) {
      joinAccess.select(command.onPredicates(), bound.onBoolean(), bound);
    }
    return status;
  }

}
