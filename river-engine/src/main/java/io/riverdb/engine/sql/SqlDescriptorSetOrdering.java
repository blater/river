package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlGroupExpressions;

/** Resolves requested output order followed by unmentioned grouping tuple lanes. */
final class SqlDescriptorSetOrdering {
  private SqlDescriptorSetOrdering() { }

  static StatusCode configure(
      SqlCommand command, SqlDescriptorSetStorage storage, int keyCount) {
    if (!command.isOrdered()) {
      for (int key = 0; key < keyCount; key++) storage.sort[key] = key;
      java.util.Arrays.fill(storage.descending, 0, keyCount, false);
      return StatusCode.OK;
    }
    int ordered = command.orderExpressionCount();
    if (ordered > keyCount) return StatusCode.FEATURE_NOT_SUPPORTED;
    for (int part = 0; part < ordered; part++) {
      int key = orderKey(command, part);
      if (key < 0 || contains(storage.sort, part, key)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      storage.sort[part] = key;
      storage.descending[part] = command.isDescendingOrder(part);
    }
    int part = ordered;
    for (int key = 0; key < keyCount; key++) {
      if (!contains(storage.sort, ordered, key)) {
        storage.sort[part] = key;
        storage.descending[part++] = false;
      }
    }
    return StatusCode.OK;
  }

  static int groupKey(SqlCommand command, int output) {
    return SqlGroupExpressions.groupKey(command, output);
  }

  private static int orderKey(SqlCommand command, int order) {
    if (command.groupExpressionCount() == 0) return projectionKey(command, order);
    int outputs = command.columnCount() - command.aggregateOutputCount();
    for (int output = 0; output < outputs; output++) {
      if (same(command.orderColumnName(order), command.columnOutputName(output))) {
        return groupKey(command, output);
      }
    }
    for (int key = 0; key < command.groupExpressionCount(); key++) {
      if (SqlDescriptorSetColumns.named(
          command, command.groupExpression(key), command.orderColumnName(order))) return key;
    }
    return -1;
  }

  private static int projectionKey(SqlCommand command, int order) {
    for (int output = 0; output < command.columnCount(); output++) {
      if (same(command.orderColumnName(order), command.columnOutputName(output))) return output;
    }
    return -1;
  }

  private static boolean same(CharSequence left, CharSequence right) {
    return SqlDescriptorPrimaryPredicate.same(left, right);
  }

  private static boolean contains(int[] values, int count, int candidate) {
    for (int index = 0; index < count; index++) {
      if (values[index] == candidate) return true;
    }
    return false;
  }
}
