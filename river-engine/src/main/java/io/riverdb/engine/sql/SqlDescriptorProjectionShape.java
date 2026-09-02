package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;

/** Transactionally retained direct projection and ordering columns. */
final class SqlDescriptorProjectionShape {
  private final SqlRetainedArrayAllocator allocator;
  int[] columns = new int[0];
  int[] descriptors = new int[0];
  int[] orderColumns = new int[0];
  boolean[] descending = new boolean[0];
  int count;
  int orderCount;
  private int orderCapacity;

  SqlDescriptorProjectionShape(SqlRetainedArrayAllocator arrayAllocator) {
    allocator = arrayAllocator;
  }

  StatusCode prepare(SqlCommand command, TableDescriptor table) {
    int requested = command.isSelectAll() ? table.columnCount() : command.columnCount();
    count = 0;
    if (requested <= 0 || requested > SqlShapeLimits.MAX_RESULT_COLUMNS) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = reserve(requested);
    for (int index = 0; status.isOk() && index < requested; index++) {
      int column = command.isSelectAll() ? index : resolve(command, table, index);
      if (column < -1) return StatusCode.INVALID_EXTERNAL_INPUT;
      columns[index] = column;
      descriptors[index] = column < 0
          ? SqlTypeDescriptor.BIGINT : table.typeDescriptorAt(column);
    }
    if (status.isOk()) count = requested;
    return status.isOk() ? prepareOrder(command, table) : status;
  }

  int orderColumn(SqlCommand command, TableDescriptor table) {
    int source = table.findColumn(command.orderColumnName());
    if (source >= 0) return source;
    int projection = orderAlias(command, 0);
    return projection < 0 ? -2 : projection;
  }

  StatusCode reserveOrder(int required) {
    int next = BoundedArrayGrowth.capacity(
        orderCapacity, required, SqlShapeLimits.MAX_ORDER_BY_EXPRESSIONS, 8);
    if (next < 0) return StatusCode.RESOURCE_EXHAUSTED;
    if (next == orderCapacity) return StatusCode.OK;
    try {
      int[] nextColumns = allocator.integers(next);
      boolean[] nextDescending = allocator.booleans(next);
      orderColumns = nextColumns;
      descending = nextDescending;
      orderCapacity = next;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode reserve(int requested) {
    if (requested <= columns.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        columns.length, requested, SqlShapeLimits.MAX_RESULT_COLUMNS, 8);
    try {
      int[] nextColumns = allocator.integers(capacity);
      int[] nextDescriptors = allocator.integers(capacity);
      columns = nextColumns;
      descriptors = nextDescriptors;
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode prepareOrder(SqlCommand command, TableDescriptor table) {
    orderCount = command.orderExpressionCount();
    StatusCode status = reserveOrder(orderCount);
    for (int expression = 0; status.isOk() && expression < orderCount; expression++) {
      int source = table.findColumn(command.orderColumnName(expression));
      if (source < 0) source = orderAlias(command, expression);
      if (source < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
      orderColumns[expression] = source;
      descending[expression] = command.isDescendingOrder(expression);
    }
    return status;
  }

  private int orderAlias(SqlCommand command, int expression) {
    int found = -1;
    for (int projection = 0; projection < count; projection++) {
      if (!SqlDescriptorPrimaryPredicate.same(
          command.columnOutputName(projection), command.orderColumnName(expression))) continue;
      if (found >= 0 || columns[projection] < 0) return -1;
      found = columns[projection];
    }
    return found;
  }

  private static int resolve(
      SqlCommand command, TableDescriptor table, int projection) {
    if (command.isNullProjection(projection)) return -1;
    int symbol = command.directProjectionSymbol(projection);
    if (symbol < 0) return -2;
    CharSequence qualifier = command.projectionSymbolTable(symbol);
    if (qualifier.length() != 0
        && !SqlDescriptorPrimaryPredicate.same(qualifier, command.tableName())
        && !(command.tableAlias().length() > 0
            && SqlDescriptorPrimaryPredicate.same(qualifier, command.tableAlias()))) return -2;
    int column = table.findColumn(command.projectionSymbolName(symbol));
    return column < 0 ? -2 : column;
  }
}
