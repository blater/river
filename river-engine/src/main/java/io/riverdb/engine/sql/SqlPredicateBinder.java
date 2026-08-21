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
  private final SqlJoinHashSelector joinHash = new SqlJoinHashSelector();

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
    for (int stage = 0; stage < command.joinChain().stageCount(); stage++) {
      bound.predicateCount += command.joinChain().onPredicates(stage).leafCount();
    }
    bound.accessPredicate = -1;
    bound.predicateColumn = -1;
    bound.accessComparison = null;
    bound.resetJoinAccess();
    bound.resetJoinStrategies();
    StatusCode status = StatusCode.OK;
    for (int stage = 0;
        status.isOk() && stage < command.joinChain().stageCount(); stage++) {
      status = booleans.bindJoinOn(command, bound, stage);
    }
    if (status.isOk()) status = booleans.bindJoinWhere(command, bound);
    if (status.isOk() && command.wherePredicates().isAvailable()) {
      access.select(command.wherePredicates(), bound.whereBoolean, bound);
    }
    for (int stage = 0;
        status.isOk() && stage < command.joinChain().stageCount(); stage++) {
      joinAccess.select(
          command.joinChain().onPredicates(stage),
          bound.onBoolean(stage),
          bound,
          stage);
    }
    joinHash.begin();
    for (int stage = 0;
        status.isOk() && stage < command.joinChain().stageCount(); stage++) {
      joinHash.select(
          command.joinChain().onPredicates(stage),
          bound.onBoolean(stage),
          bound,
          stage);
    }
    return status;
  }

}
