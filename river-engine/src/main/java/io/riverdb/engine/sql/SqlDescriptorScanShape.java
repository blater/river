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
    if (status.isOk()) status = prepareIndex(command, table);
    if (status.isOk()) setAccessColumn(plan);
    if (status.isOk()) status = materialization.prepare(command, table, plan);
    return configurePlan(status, command, table, plan);
  }

  private StatusCode prepareIndex(SqlCommand command, TableDescriptor table) {
    boolean suppressOrder = context.scalarAggregate || context.sets.active();
    return context.index.prepare(
        command, table, context.predicate.bindings(),
        suppressOrder ? 0 : context.projection.orderCount(),
        suppressOrder ? null : context.projection.orderColumns(),
        suppressOrder ? null : context.projection.orderDescending());
  }

  private void setAccessColumn(SqlPhysicalPlan plan) {
    if (context.index.active()) plan.setAccessColumn(context.index.accessColumn());
  }

  private StatusCode configurePlan(
      StatusCode status, SqlCommand command, TableDescriptor table, SqlPhysicalPlan plan) {
    if (!status.isOk() || context.sets.active() || context.scalarAggregate) return status;
    return context.projection.configurePlan(command, table, plan);
  }
}
