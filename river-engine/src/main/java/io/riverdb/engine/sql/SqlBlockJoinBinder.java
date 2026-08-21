package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;

/** Binds the deepest two-table source and publishes its stable block boundary. */
final class SqlBlockJoinBinder {
  private final SqlBinder binder;

  SqlBlockJoinBinder(SqlBinder sharedBinder) {
    binder = sharedBinder;
  }

  StatusCode resolve(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlCommand command) {
    return binder.resolveJoinRoles(session, command, bound, true);
  }

  StatusCode preflight(
      BoundSqlStatement bound,
      SqlBooleanPredicateEvaluator predicates,
      SqlRowProjectionEvaluator projections) {
    StatusCode status = predicates == null ? StatusCode.OK
        : prepareOn(bound, predicates);
    if (status.isOk() && predicates != null) {
      status = predicates.prepare(bound.command, bound.whereBoolean);
    }
    return status.isOk() && projections != null
        ? projections.prepare(bound) : status;
  }

  private static StatusCode prepareOn(
      BoundSqlStatement bound, SqlBooleanPredicateEvaluator predicates) {
    StatusCode status = StatusCode.OK;
    for (int stage = 0;
        status.isOk() && stage < bound.command.joinChain().stageCount(); stage++) {
      status = predicates.prepare(bound.command, bound.onBoolean(stage));
    }
    return status;
  }

  StatusCode bind(
      BoundSqlStatement bound,
      SqlBoundBlockPlans plans,
      int block,
      SqlBlockSchema output) {
    if (block != plans.count() - 1) return StatusCode.FEATURE_NOT_SUPPORTED;
    StatusCode status = binder.bindJoin(bound.command, bound);
    if (!status.isOk()) return status;
    if (bound.command.isOrdered()) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    output.set(bound.projectedColumnCount);
    for (int column = 0; column < bound.projectedColumnCount; column++) {
      output.setColumn(
          column,
          bound.command.columnOutputName(column),
          bound.projectedTypeDescriptors[column],
          SqlJoinResultNullability.nullable(bound, column));
    }
    plans.setJoinAccess(block, bound);
    return StatusCode.OK;
  }
}
