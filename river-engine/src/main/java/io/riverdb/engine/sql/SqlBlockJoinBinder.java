package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.sql.SqlCommand;

/** Binds the deepest two-table source and publishes its stable block boundary. */
final class SqlBlockJoinBinder {
  private final SqlBinder binder;
  private final SqlNestedTableResolver tables = new SqlNestedTableResolver();

  SqlBlockJoinBinder(SqlBinder sharedBinder) {
    binder = sharedBinder;
  }

  StatusCode resolve(
      RelationalSession session,
      BoundSqlStatement bound,
      SqlCommand command,
      int block) {
    return tables.resolveContextRoles(
        session, command, bound.joinContext(block));
  }

  StatusCode preflight(
      BoundSqlStatement bound,
      int block,
      SqlCommand command,
      SqlBoundJoinContext context,
      SqlBooleanPredicateEvaluator predicates,
      SqlRowProjectionEvaluator projections) {
    boolean nestedSource = bound.executableQuery.edgeCount() > 0
        && block == bound.executableQuery.sourceBlockCount() - 1;
    StatusCode status = predicates == null || nestedSource ? StatusCode.OK
        : prepareOn(command, context, predicates);
    if (status.isOk() && predicates != null && !nestedSource) {
      status = predicates.prepare(command, bound.whereBoolean);
    }
    return status.isOk() && projections != null
        ? projections.prepare(bound) : status;
  }

  private static StatusCode prepareOn(
      SqlCommand command,
      SqlBoundJoinContext context,
      SqlBooleanPredicateEvaluator predicates) {
    StatusCode status = StatusCode.OK;
    for (int stage = 0;
        status.isOk() && stage < command.joinChain().stageCount(); stage++) {
      status = predicates.prepare(command, context.onBoolean(stage));
    }
    return status;
  }

  StatusCode bind(
      BoundSqlStatement bound,
      SqlBoundBlockPlans plans,
      int block,
      SqlBlockSchema output) {
    if (block != plans.count() - 1) return StatusCode.FEATURE_NOT_SUPPORTED;
    SqlCommand command = plans.command(block);
    SqlBoundJoinContext context = bound.existingJoinContext(block);
    if (context == null) return StatusCode.CORRUPTION;
    boolean nestedSource = bound.executableQuery.edgeCount() > 0
        && block == bound.executableQuery.sourceBlockCount() - 1;
    StatusCode status = nestedSource
        ? binder.bindJoinProjection(command, bound, context)
        : binder.bindJoin(command, bound, context);
    if (!status.isOk()) return status;
    if (command.isOrdered()) status = binder.bindJoinOrder(command, bound);
    if (!status.isOk()) return status;
    output.set(bound.projectedColumnCount);
    bound.joinProjectedColumnCount = bound.projectedColumnCount;
    for (int column = 0; column < bound.projectedColumnCount; column++) {
      output.setColumn(
          column,
          command.columnOutputName(column),
          bound.projectedTypeDescriptors[column],
          SqlJoinResultNullability.nullable(command, context, bound, column));
    }
    plans.setJoinAccess(block, command, context);
    return StatusCode.OK;
  }
}
