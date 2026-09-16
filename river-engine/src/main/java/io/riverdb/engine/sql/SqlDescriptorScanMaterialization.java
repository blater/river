package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;

/** Chooses direct index ordering or bounded materialization for one descriptor scan. */
final class SqlDescriptorScanMaterialization {
  private final SqlDescriptorScanContext context;

  SqlDescriptorScanMaterialization(SqlDescriptorScanContext owner) { context = owner; }

  StatusCode prepare(SqlCommand command, TableDescriptor table, SqlPhysicalPlan plan) {
    boolean ordered = (command.orderBy().count() > 0);
    StatusCode status = validate(ordered);
    if (!status.isOk()) return status;
    return configure(command, table, plan, ordered);
  }

  private StatusCode configure(
      SqlCommand command, TableDescriptor table, SqlPhysicalPlan plan, boolean ordered) {
    boolean indexOrder = ordered && context.index.orderCovered();
    context.materialized = ordered && !indexOrder
        || context.subqueries.active() || context.sets.active();
    plan.setSort(ordered && !indexOrder);
    int orderColumn = orderColumn(command, table, ordered);
    if (ordered && orderColumn < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!context.materialized) return StatusCode.OK;
    return begin(table, command, orderColumn);
  }

  private StatusCode validate(boolean ordered) {
    return context.scalarAggregate && (ordered || context.subqueries.active())
        ? StatusCode.FEATURE_NOT_SUPPORTED : StatusCode.OK;
  }

  private int orderColumn(SqlCommand command, TableDescriptor table, boolean ordered) {
    if (context.sets.active()) return context.sets.sourceColumn();
    return ordered ? context.projection.orderColumn(command, table) : -1;
  }

  private StatusCode begin(TableDescriptor table, SqlCommand command, int orderColumn) {
    if (context.sets.active()) {
      return context.ordered.begin(
          table, context.sets.materialization(), context.sets.sortColumns(),
          context.sets.descending(), context.sets.keyCount());
    }
    if (context.projection.orderCount() > 1) {
      return context.ordered.begin(
          table, context.projection.orderColumns(),
          context.projection.orderDescending(), context.projection.orderCount());
    }
    return context.ordered.begin(table, orderColumn, command.isDescendingOrder());
  }
}
