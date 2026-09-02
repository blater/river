package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDescriptorIndexBounds;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;

/** Owns and rebinds one child descriptor's composite scan bounds. */
final class SqlDescriptorSubqueryIndexAccess {
  private final SqlDescriptorSubqueryIndexPlan plan =
      new SqlDescriptorSubqueryIndexPlan();
  private final SqlDescriptorSubqueryBoundWriter writer;
  private TableDescriptor table;
  private SqlCommand command;
  private long tableId;
  private long rowLayoutId;
  private long catalogGeneration;

  SqlDescriptorSubqueryIndexAccess(SqlSessionShapeBudget budget) {
    writer = new SqlDescriptorSubqueryBoundWriter(budget);
  }

  StatusCode prepare(
      SqlCommand source, TableDescriptor descriptor,
      SqlDescriptorCorrelatedBindings bindings) {
    reset();
    table = descriptor;
    command = source;
    tableId = descriptor.tableId();
    rowLayoutId = descriptor.rowLayoutId();
    catalogGeneration = descriptor.catalogGeneration();
    plan.prepare(source, descriptor, bindings);
    return writer.prepare(plan, descriptor, source);
  }

  StatusCode bind(SqlDescriptorValueSource outer) {
    if (!active()) return StatusCode.OK;
    return writer.bind(plan, table, command, outer);
  }

  boolean active() { return plan.active(); }
  boolean empty() { return writer.empty(); }
  boolean matches(TableDescriptor candidate) {
    return candidate != null && candidate.tableId() == tableId
        && candidate.rowLayoutId() == rowLayoutId
        && candidate.catalogGeneration() == catalogGeneration;
  }
  RelationalDescriptorIndexBounds bounds() { return writer.bounds(); }

  int accessColumn() {
    if (!active()) return -1;
    if (plan.key().kind() == io.riverdb.engine.schema.KeyDescriptor.KIND_PRIMARY) return 0;
    return plan.key().columnOrdinalAt(0);
  }

  int accessKind() {
    return !active() ? 1
        : plan.key().kind() == io.riverdb.engine.schema.KeyDescriptor.KIND_PRIMARY ? 2 : 3;
  }

  void reset() {
    writer.reset();
    table = null;
    command = null;
    tableId = 0;
    rowLayoutId = 0;
    catalogGeneration = 0;
  }
}
