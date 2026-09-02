package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.sql.SqlCommand;
import io.riverdb.sql.SqlGroupExpressions;

/** Retained resolved key lanes and directions for one legacy tuple sort. */
final class SqlLegacySortTupleLayout {
  private int[] lanes = new int[0];
  private boolean[] descending = new boolean[0];

  StatusCode configure(
      SqlCommand command, int keys, int projections, boolean grouped) {
    int capacity = BoundedArrayGrowth.capacity(
        lanes.length, keys, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      if (capacity != lanes.length) {
        lanes = new int[capacity];
        descending = new boolean[capacity];
      }
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    return grouped
        ? configureGroups(command, keys)
        : configureOutputs(command, keys, projections);
  }

  int lane(int part) { return lanes[part]; }

  boolean descending(int part) { return descending[part]; }

  private StatusCode configureGroups(SqlCommand command, int keys) {
    int part = 0;
    for (; part < command.orderExpressionCount(); part++) {
      int key = groupOrderKey(command, part);
      if (key < 0 || contains(part, key)) return StatusCode.INVALID_EXTERNAL_INPUT;
      lanes[part] = key;
      descending[part] = command.isDescendingOrder(part);
    }
    for (int key = 0; key < keys; key++) {
      if (!contains(part, key)) {
        lanes[part] = key;
        descending[part++] = false;
      }
    }
    return part == keys ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode configureOutputs(
      SqlCommand command, int keys, int projections) {
    for (int part = 0; part < keys; part++) {
      int lane = command.isOrdered() ? outputOrderKey(command, part) : part;
      if (lane < 0 || lane >= projections || contains(part, lane)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      lanes[part] = lane;
      descending[part] = command.isOrdered() && command.isDescendingOrder(part);
    }
    return StatusCode.OK;
  }

  private int groupOrderKey(SqlCommand command, int order) {
    int outputs = command.columnCount() - command.aggregateOutputCount();
    for (int output = 0; output < outputs; output++) {
      if (same(command.orderColumnName(order), command.columnOutputName(output))) {
        return SqlGroupExpressions.groupKey(command, output);
      }
    }
    return SqlGroupExpressions.namedGroupKey(command, command.orderColumnName(order));
  }

  private static int outputOrderKey(SqlCommand command, int order) {
    for (int output = 0; output < command.columnCount(); output++) {
      if (same(command.orderColumnName(order), command.columnOutputName(output))
          || same(command.orderColumnName(order), command.columnName(output))) return output;
    }
    return -1;
  }

  private boolean contains(int count, int candidate) {
    for (int index = 0; index < count; index++) {
      if (lanes[index] == candidate) return true;
    }
    return false;
  }

  private static boolean same(CharSequence left, CharSequence right) {
    if (left == null || right == null || left.length() != right.length()) return false;
    for (int index = 0; index < left.length(); index++) {
      char first = left.charAt(index);
      char second = right.charAt(index);
      if (first != second && Character.toUpperCase(first) != Character.toUpperCase(second)) {
        return false;
      }
    }
    return true;
  }
}
