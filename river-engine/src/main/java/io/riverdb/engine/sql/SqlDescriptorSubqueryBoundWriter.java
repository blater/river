package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.RelationalDescriptorIndexBounds;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;

/** Reuses typed value buffers while binding one child index reopen. */
final class SqlDescriptorSubqueryBoundWriter {
  private final SqlDescriptorPrimaryValues lower;
  private final SqlDescriptorPrimaryValues upper;
  private final RelationalDescriptorIndexBounds bounds =
      new RelationalDescriptorIndexBounds();
  private final SqlDescriptorSubqueryEqualityWriter equality =
      new SqlDescriptorSubqueryEqualityWriter();
  private final SqlDescriptorSubqueryRangeWriter range =
      new SqlDescriptorSubqueryRangeWriter();
  private int columns;
  private int textBytes;
  private boolean empty;

  SqlDescriptorSubqueryBoundWriter(SqlSessionShapeBudget budget) {
    lower = new SqlDescriptorPrimaryValues(budget);
    upper = new SqlDescriptorPrimaryValues(budget);
  }

  StatusCode prepare(
      SqlDescriptorSubqueryIndexPlan plan, TableDescriptor table, SqlCommand command) {
    if (!plan.active()) return StatusCode.OK;
    columns = table.columnCount();
    textBytes = keyTextBytes(plan);
    StatusCode status = lower.begin(columns, textBytes, command);
    if (status.isOk()) status = upper.begin(columns, textBytes, command);
    lower.reset();
    upper.reset();
    return status;
  }

  StatusCode bind(
      SqlDescriptorSubqueryIndexPlan plan, TableDescriptor table,
      SqlCommand command, SqlDescriptorValueSource outer) {
    empty = false;
    StatusCode status = lower.begin(columns, textBytes, command);
    if (status.isOk()) status = upper.begin(columns, textBytes, command);
    if (status.isOk()) status = equality.bind(plan, lower, upper, outer);
    if (status.isOk() && !equality.empty()) {
      status = range.bind(plan, lower, upper, outer, bounds);
    }
    empty = equality.empty() || range.empty();
    return status;
  }

  boolean empty() { return empty; }
  RelationalDescriptorIndexBounds bounds() { return bounds; }
  void reset() {
    lower.reset();
    upper.reset();
    equality.reset();
    range.reset();
    columns = 0;
    textBytes = 0;
    empty = false;
  }

  private static int keyTextBytes(SqlDescriptorSubqueryIndexPlan plan) {
    long bytes = 0;
    for (int part = 0; part < plan.key().partCount(); part++) {
      int descriptor = plan.key().typeDescriptorAt(part);
      if (io.riverdb.base.type.SqlTypeDescriptor.typeId(descriptor)
          == io.riverdb.base.type.SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        bytes += io.riverdb.base.type.SqlTypeDescriptor.parameterOne(descriptor) * 4L;
        if (bytes > Integer.MAX_VALUE) return -1;
      }
    }
    return (int) bytes;
  }
}
