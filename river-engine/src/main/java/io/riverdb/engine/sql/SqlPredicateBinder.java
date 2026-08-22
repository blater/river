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
  private final SqlJoinMergeSelector joinMerge = new SqlJoinMergeSelector();
  private final SqlJoinPlanner joinPlanner = new SqlJoinPlanner();

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

  StatusCode bindJoin(
      SqlCommand command,
      BoundSqlStatement bound,
      SqlBoundJoinContext context) {
    bound.predicateCount = command.wherePredicates().leafCount();
    for (int stage = 0; stage < command.joinChain().stageCount(); stage++) {
      bound.predicateCount += command.joinChain().onPredicates(stage).leafCount();
    }
    context.resetJoinAccess();
    context.resetStrategies();
    StatusCode status = StatusCode.OK;
    for (int stage = 0;
        status.isOk() && stage < command.joinChain().stageCount(); stage++) {
      status = booleans.bindJoinOn(command, bound, context, stage);
    }
    if (status.isOk()) status = booleans.bindJoinWhere(command, bound, context);
    return status.isOk()
        ? selectJoin(command, bound.whereBoolean, context) : status;
  }

  StatusCode bindNestedJoin(
      SqlCommand command,
      BoundSqlStatement bound,
      BoundSqlQuery query,
      int block,
      SqlBoundJoinContext context) {
    context.resetJoinAccess();
    context.resetStrategies();
    StatusCode status = StatusCode.OK;
    for (int stage = 0;
        status.isOk() && stage < command.joinChain().stageCount(); stage++) {
      status = booleans.bindNestedJoinOn(
          command, bound, context, query, block, stage);
    }
    if (status.isOk()) status = booleans.bindNested(command, bound, query, block);
    return status.isOk()
        ? selectJoin(command, bound.nestedBoolean(block), context) : status;
  }

  private StatusCode selectJoin(
      SqlCommand command,
      SqlBoundBooleanPredicateProgram where,
      SqlBoundJoinContext context) {
    if (command.wherePredicates().isAvailable()) {
      access.selectJoin(command.wherePredicates(), where, context);
    }
    for (int stage = 0;
        stage < command.joinChain().stageCount(); stage++) {
      joinAccess.select(
          command.joinChain().onPredicates(stage),
          context.onBoolean(stage),
          context,
          stage);
    }
    joinHash.begin();
    for (int stage = 0;
        stage < command.joinChain().stageCount(); stage++) {
      joinHash.select(
          command.joinChain().onPredicates(stage),
          context.onBoolean(stage),
          context,
          stage);
    }
    joinMerge.select(command, context);
    joinPlanner.select(command, context);
    return StatusCode.OK;
  }

}
