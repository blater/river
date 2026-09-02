package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;

/** Chooses direct index ordering or bounded materialization for one descriptor scan. */
final class SqlDescriptorScanMaterialization {
  private final SqlDescriptorScanContext context;

  SqlDescriptorScanMaterialization(SqlDescriptorScanContext owner) { context = owner; }

  StatusCode prepare(SqlCommand command, TableDescriptor table, SqlPhysicalPlan plan) {
    boolean ordered = command.isOrdered();
    if (context.scalarAggregate && (ordered || context.subqueries.active())) {
      return StatusCode.FEATURE_NOT_SUPPORTED;
    }
    boolean indexOrder = ordered && context.index.orderCovered();
    context.materialized = ordered && !indexOrder
        || context.subqueries.active() || context.sets.active();
    plan.setSort(ordered && !indexOrder);
    int orderColumn = context.sets.active() ? context.sets.sourceColumn()
        : ordered ? context.projection.orderColumn(command, table) : -1;
    if (ordered && orderColumn < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!context.materialized) return StatusCode.OK;
    if (context.sets.active()) return context.ordered.begin(
        table, context.sets.materialization(), context.sets.sortColumns(),
        context.sets.descending(), context.sets.keyCount());
    return context.projection.orderCount() > 1
        ? context.ordered.begin(
            table, context.projection.orderColumns(),
            context.projection.orderDescending(), context.projection.orderCount())
        : context.ordered.begin(table, orderColumn, command.isDescendingOrder());
  }
}
