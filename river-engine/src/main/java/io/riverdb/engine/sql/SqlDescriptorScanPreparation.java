package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlCommandType;
import io.riverdb.sql.SqlQuery;

/** Resolves a descriptor table and delegates its bounded physical scan shape. */
final class SqlDescriptorScanPreparation {
  private final SqlDescriptorScanContext context;
  private final SqlDescriptorScanShape shape;
  private final BoundSqlStatement bound;
  private final SqlBinder binder;

  SqlDescriptorScanPreparation(
      SqlDescriptorScanContext owner, BoundSqlStatement statement, SqlBinder statementBinder) {
    context = owner;
    bound = statement;
    binder = statementBinder;
    shape = new SqlDescriptorScanShape(owner);
  }

  StatusCode prepare(SqlCommand command, SqlQuery query, SqlPhysicalPlan plan) {
    if (query != null && query.isBlockPipeline()) return StatusCode.OK;
    StatusCode status = context.session.resolveDescriptor(
        command.tableName(), context.pin, context.detail);
    if (status == StatusCode.CONFLICT) return StatusCode.OK;
    if (!status.isOk()) return status;
    context.matched = true;
    if (query != null && (nested(query)
        || SqlDescriptorExpressionRouting.predicateRequired(command)
            && query.hasNestedTopology())) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    if (SqlDescriptorExpressionRouting.required(command)
        || SqlDescriptorExpressionRouting.predicateRequired(command)
            && !boundPredicateSupported(command.type())) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    TableDescriptor table = context.pin.descriptor();
    if (SqlDescriptorExpressionRouting.predicateRequired(command)) {
      status = prepareBoundPredicate(command, query, table);
      if (!status.isOk()) return status;
    }
    context.forUpdate = command.isSelectForUpdate();
    context.scalarAggregate = SqlDescriptorQueryTypes.scalar(command.type())
        || command.groupExpressionCount() == 0 && command.aggregateInvocationCount() > 0;
    return shape.prepare(command, query, table, plan);
  }

  private StatusCode prepareBoundPredicate(
      SqlCommand command, SqlQuery query, TableDescriptor table) {
    StatusCode status = context.boundPredicate.prepareBinding(table, bound.table);
    if (status.isOk()) status = binder.bindDataCommand(command, query, bound);
    if (status.isOk()) status = binder.captureExecutableQuery(bound);
    if (status.isOk()) status = context.boundPredicate.prepare(table);
    if (status.isOk()) context.predicate.reset();
    return status;
  }

  private static boolean nested(SqlQuery query) {
    for (int edge = 0; edge < query.edgeCount(); edge++) {
      if (query.edgeParent(edge) != 0) return true;
    }
    return false;
  }

  private static boolean boundPredicateSupported(SqlCommandType type) {
    return type == SqlCommandType.SELECT || type == SqlCommandType.SCAN;
  }
}
