package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.engine.schema.TableDescriptor;

/** Budgeted retained row buffer for one descriptor-subquery child. */
final class SqlDescriptorSubqueryRowValues {
  private static final int LANE_BYTES = 2 * Long.BYTES + 3 * Integer.BYTES;
  private final SqlSessionShapeBudget budget;
  private final SqlValueBuffer values = new SqlValueBuffer();

  SqlDescriptorSubqueryRowValues(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  StatusCode reserve(TableDescriptor table) {
    int lanes = table.columnCount();
    int text = maximumTextBytes(table);
    if (text < 0) return StatusCode.RESOURCE_EXHAUSTED;
    int laneCapacity = capacity(values.capacity(), lanes, lanes, 8);
    int textCapacity = capacity(values.textCapacity(), text, text, 8);
    if (laneCapacity < 0 || textCapacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long bytes = (long) (laneCapacity - values.capacity()) * LANE_BYTES
        + (long) (words(laneCapacity) - words(values.capacity())) * Long.BYTES
        + textCapacity - values.textCapacity();
    StatusCode status = bytes == 0 ? StatusCode.OK : budget.reserve(bytes);
    if (status.isOk()) status = values.reserve(lanes, lanes, text, text);
    if (!status.isOk() && bytes > 0) budget.rollback(bytes);
    return status;
  }

  SqlValueBuffer values() { return values; }
  void reset() { values.reset(); }

  private static int capacity(int current, int required, int maximum, int initial) {
    return required <= current ? current
        : BoundedArrayGrowth.capacity(current, required, maximum, initial);
  }

  private static int words(int lanes) { return (lanes + Long.SIZE - 1) >>> 6; }

  private static int maximumTextBytes(TableDescriptor table) {
    long bytes = 0;
    for (int column = 0; column < table.columnCount(); column++) {
      int descriptor = table.typeDescriptorAt(column);
      if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        bytes += SqlTypeDescriptor.parameterOne(descriptor) * 4L;
        if (bytes > TableSchema.MAXIMUM_ROW_BYTES) return -1;
      }
    }
    return (int) bytes;
  }
}
