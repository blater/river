package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlScalarExpression;
import io.riverdb.storage.heap.HeapRowResult;

/** Reads one bound nested column from a physical or canonical block row. */
final class SqlNestedColumnValue {
  private SqlNestedColumnValue() { }

  static StatusCode evaluate(
      SqlRowExpressionEvaluator machine,
      SqlCommand command,
      SqlTemporalZonePlan zone,
      SqlNestedRowProvider rows,
      int block,
      int role,
      int column,
      int descriptor) {
    SqlBlockRow blockRow = rows.blockRow(block, role);
    if (blockRow != null) {
      return machine.predicateBlockColumnNode(blockRow, column, descriptor);
    }
    HeapRowResult row = rows.row(block, role);
    return row == null
        ? machine.predicateNullColumnNode(descriptor)
        : machine.predicateOperandNode(
            command,
            SqlScalarExpression.COLUMN,
            column,
            descriptor,
            zone,
            rows.key(block, role),
            row,
            rows.table(block, role),
            null);
  }
}
