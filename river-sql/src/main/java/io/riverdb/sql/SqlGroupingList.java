package io.riverdb.sql;

import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.error.StatusCode;

/** Reusable expressions and selected-output mappings forming one GROUP BY tuple. */
final class SqlGroupingList {
  private int[] projections = new int[8];
  private int[] operandProjections = new int[8];
  private SqlScalarExpression[] expressions = expressions(8);
  private int count;

  StatusCode append(int projection, SqlScalarExpression expression) {
    if (projection < -1 || expression == null || !expression.isAvailable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (count >= SqlShapeLimits.MAX_GROUP_BY_EXPRESSIONS || !grow(count + 1)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = expressions[count].copyFrom(expression);
    if (!status.isOk()) return status;
    projections[count++] = projection;
    operandProjections[count - 1] = projection;
    return StatusCode.OK;
  }

  void reset() {
    for (int index = 0; index < count; index++) {
      projections[index] = 0;
      operandProjections[index] = 0;
      expressions[index].reset();
    }
    count = 0;
  }

  boolean copyFrom(SqlGroupingList source) {
    reset();
    if (!grow(source.count)) return false;
    for (int index = 0; index < source.count; index++) {
      if (!expressions[index].copyFrom(source.expressions[index]).isOk()) {
        reset();
        return false;
      }
      projections[index] = source.projections[index];
      operandProjections[index] = source.operandProjections[index];
    }
    count = source.count;
    return true;
  }

  int count() { return count; }
  int projection(int expression) {
    return expression >= 0 && expression < count ? projections[expression] : -1;
  }
  int operandProjection(int expression) {
    return expression >= 0 && expression < count ? operandProjections[expression] : -1;
  }
  void setOperandProjection(int expression, int projection) {
    if (expression >= 0 && expression < count) operandProjections[expression] = projection;
  }
  SqlScalarExpression expression(int index) {
    return index >= 0 && index < count ? expressions[index] : null;
  }
  boolean contains(int projection) {
    for (int index = 0; index < count; index++) {
      if (projections[index] == projection) return true;
    }
    return false;
  }

  private boolean grow(int required) {
    if (required <= projections.length) return true;
    int capacity = Math.min(
        SqlShapeLimits.MAX_GROUP_BY_EXPRESSIONS, Math.max(required, projections.length * 2));
    if (capacity < required) return false;
    try {
      int[] nextProjections = new int[capacity];
      int[] nextOperandProjections = new int[capacity];
      SqlScalarExpression[] nextExpressions = expressions(capacity);
      System.arraycopy(projections, 0, nextProjections, 0, count);
      System.arraycopy(operandProjections, 0, nextOperandProjections, 0, count);
      System.arraycopy(expressions, 0, nextExpressions, 0, count);
      projections = nextProjections;
      operandProjections = nextOperandProjections;
      expressions = nextExpressions;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  private static SqlScalarExpression[] expressions(int capacity) {
    SqlScalarExpression[] values = new SqlScalarExpression[capacity];
    for (int index = 0; index < capacity; index++) values[index] = new SqlScalarExpression();
    return values;
  }
}
