package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlQuery;

/** Binds row values, predicates, projection, and tuple-index bounds once per open. */
final class SqlDescriptorScanShape {
  private final SqlDescriptorScanContext context;
  private final SqlDescriptorScanBindings bindings;
  private final SqlDescriptorScanMaterialization materialization;

  SqlDescriptorScanShape(SqlDescriptorScanContext owner) {
    context = owner;
    bindings = new SqlDescriptorScanBindings(owner);
    materialization = new SqlDescriptorScanMaterialization(owner);
  }

  StatusCode prepare(
      SqlCommand command, SqlQuery query, TableDescriptor table, SqlPhysicalPlan plan) {
    plan.setFilterCount(command.wherePredicates().leafCount());
    StatusCode status = bindings.prepare(command, query, table, plan);
    if (status.isOk()) status = context.index.prepare(
        command, table, context.predicate.bindings(),
        context.scalarAggregate || context.sets.active()
            ? 0 : context.projection.orderCount(),
        context.scalarAggregate || context.sets.active()
            ? null : context.projection.orderColumns(),
        context.scalarAggregate || context.sets.active()
            ? null : context.projection.orderDescending());
    if (status.isOk() && context.index.active()) {
      plan.setAccessColumn(context.index.accessColumn());
    }
    if (status.isOk()) status = materialization.prepare(command, table, plan);
    return status.isOk() && !context.sets.active() && !context.scalarAggregate
        ? context.projection.configurePlan(command, table, plan) : status;
  }
}
