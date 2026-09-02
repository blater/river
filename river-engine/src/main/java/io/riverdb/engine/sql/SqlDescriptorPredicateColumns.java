package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlScalarExpression;

/** Retained unique descriptor columns read by one commonly bound predicate. */
final class SqlDescriptorPredicateColumns {
  private final SqlSessionShapeBudget budget;
  private int[] columns = new int[0];
  private int count;

  SqlDescriptorPredicateColumns(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  StatusCode prepare(SqlBoundBooleanPredicateProgram predicate, int tableColumns) {
    count = 0;
    for (int leaf = 0; leaf < predicate.leafCount(); leaf++) {
      for (int program = SqlBooleanPredicateProgram.PROGRAM_LEFT;
          program <= SqlBooleanPredicateProgram.PROGRAM_UPPER; program++) {
        for (int node = 0; node < predicate.nodeCount(leaf, program); node++) {
          if (predicate.operator(leaf, program, node) != SqlScalarExpression.COLUMN) continue;
          int column = (int) predicate.operand(leaf, program, node);
          if (column < 0 || column >= tableColumns) return StatusCode.INVARIANT_BROKEN;
          StatusCode status = append(column);
          if (!status.isOk()) return status;
        }
      }
    }
    return StatusCode.OK;
  }

  int count() { return count; }
  int column(int index) { return columns[index]; }
  void reset() { count = 0; }

  private StatusCode append(int column) {
    for (int index = 0; index < count; index++) {
      if (columns[index] == column) return StatusCode.OK;
    }
    if (count == columns.length) {
      int capacity = BoundedArrayGrowth.capacity(
          columns.length, count + 1, SqlShapeLimits.MAX_TABLE_COLUMNS, 8);
      if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
      long charged = (long) (capacity - columns.length) * Integer.BYTES;
      StatusCode status = budget == null ? StatusCode.OK : budget.reserve(charged);
      if (!status.isOk()) return status;
      try {
        int[] next = new int[capacity];
        System.arraycopy(columns, 0, next, 0, count);
        columns = next;
      } catch (OutOfMemoryError error) {
        if (budget != null) budget.rollback(charged);
        return StatusCode.RESOURCE_EXHAUSTED;
      }
    }
    columns[count++] = column;
    return StatusCode.OK;
  }
}
