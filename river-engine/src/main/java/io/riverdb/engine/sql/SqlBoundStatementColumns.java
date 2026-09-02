package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Budgeted geometric primitive columns retained by one SQL session. */
final class SqlBoundStatementColumns {
  private static final int INSERT = 0;
  private final SqlSessionShapeBudget budget;
  private int[] insert = new int[0];
  private int[] mutationColumns = new int[0];
  private int[] mutationTypes = new int[0];
  private int[] projectionColumns = new int[0];
  private int[] projectionTypes = new int[0];

  SqlBoundStatementColumns(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  StatusCode reserveInsert(int count) {
    return reserve(count, SqlShapeLimits.MAX_TABLE_COLUMNS, INSERT);
  }

  StatusCode reserveMutation(int count) {
    return reserve(count, SqlShapeLimits.MAX_UPDATE_ASSIGNMENTS, 1);
  }

  StatusCode reserveProjection(int count) {
    return reserve(count, SqlShapeLimits.MAX_RESULT_COLUMNS, 2);
  }

  int[] insert() { return insert; }
  int[] mutationColumns() { return mutationColumns; }
  int[] mutationTypes() { return mutationTypes; }
  int[] projectionColumns() { return projectionColumns; }
  int[] projectionTypes() { return projectionTypes; }

  private StatusCode reserve(int required, int maximum, int kind) {
    if (required < 0 || required > maximum) return StatusCode.RESOURCE_EXHAUSTED;
    int current = kind == INSERT ? insert.length
        : kind == 1 ? mutationColumns.length : projectionColumns.length;
    if (required <= current) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(current, required, maximum, 8);
    int arrayCount = kind == INSERT ? 1 : 2;
    long charged = (long) (capacity - current) * Integer.BYTES * arrayCount;
    StatusCode admitted = budget.reserve(charged);
    if (!admitted.isOk()) return admitted;
    try {
      grow(kind, capacity);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private void grow(int kind, int capacity) {
    if (kind == INSERT) {
      insert = copy(insert, capacity);
    } else if (kind == 1) {
      int[] nextColumns = copy(mutationColumns, capacity);
      int[] nextTypes = copy(mutationTypes, capacity);
      mutationColumns = nextColumns;
      mutationTypes = nextTypes;
    } else {
      int[] nextColumns = copy(projectionColumns, capacity);
      int[] nextTypes = copy(projectionTypes, capacity);
      projectionColumns = nextColumns;
      projectionTypes = nextTypes;
    }
  }

  private static int[] copy(int[] source, int capacity) {
    int[] result = new int[capacity];
    System.arraycopy(source, 0, result, 0, source.length);
    return result;
  }
}
