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
        : predicates.prepare(bound.command, bound.onBoolean());
    if (status.isOk() && predicates != null) {
      status = predicates.prepare(bound.command, bound.whereBoolean);
    }
    return status.isOk() && projections != null
        ? projections.prepare(bound) : status;
  }

  StatusCode bind(
      BoundSqlStatement bound,
      SqlBoundBlockPlans plans,
      int block,
      SqlBlockSchema output) {
    if (block != plans.count() - 1) return StatusCode.FEATURE_NOT_SUPPORTED;
    StatusCode status = binder.bindJoin(bound.command, bound);
    if (!status.isOk()) return status;
    output.set(bound.projectedColumnCount);
    for (int column = 0; column < bound.projectedColumnCount; column++) {
      output.setColumn(
          column,
          bound.command.columnOutputName(column),
          bound.projectedTypeDescriptors[column],
          SqlJoinResultNullability.nullable(
              bound, bound.command.isLeftJoin(), column));
    }
    int right = bound.joinInnerColumn;
    int outerAccess = bound.accessPredicate >= 0
        && (bound.predicateColumn == 0
            || bound.table.hasIndexOn(bound.predicateColumn))
        ? bound.predicateColumn : -1;
    plans.setJoinAccess(
        block,
        right,
        outerAccess,
        right >= 0 && (right == 0 || bound.joinTable.hasIndexOn(right)));
    return StatusCode.OK;
  }
}
