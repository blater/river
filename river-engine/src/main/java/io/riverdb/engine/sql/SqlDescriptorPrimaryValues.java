package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.sql.SqlCommand;
import java.nio.ByteBuffer;

/** Reusable typed value storage for a primary-key point lookup. */
final class SqlDescriptorPrimaryValues {
  private static final int LANE_BYTES = 2 * Long.BYTES + 3 * Integer.BYTES;
  private static final ByteBuffer EMPTY_TEXT = ByteBuffer.allocate(0);
  private final SqlValueBuffer values = new SqlValueBuffer();
  /* Runtime value storage is bounded by row admission, not declaration width. */
  private ByteBuffer text = EMPTY_TEXT;
  private final SqlSessionShapeBudget budget;
  private final ExactDecimal.LongValue converted = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch wide = new ExactDecimal.WideScratch();
  private final ExactDecimal128.Value converted128 = new ExactDecimal128.Value();
  private final ExactDecimal128.Scratch wide128 = new ExactDecimal128.Scratch();
  private SqlCommand command;

  SqlDescriptorPrimaryValues() { this(null); }

  SqlDescriptorPrimaryValues(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  SqlValueBuffer buffer() { return values; }

  StatusCode begin(int columns, int textBytes, SqlCommand source) {
    int laneCapacity = capacity(values.capacity(), columns, columns, 8);
    int valueTextCapacity = capacity(values.textCapacity(), textBytes, textBytes, 8);
    int commandTextCapacity = Math.min(textBytes, Utf8Text.MAXIMUM_BUFFER_BYTES);
    if (laneCapacity < 0 || valueTextCapacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long charged = (long) (laneCapacity - values.capacity()) * LANE_BYTES
        + (long) (words(laneCapacity) - words(values.capacity())) * Long.BYTES
        + valueTextCapacity - values.textCapacity()
        + Math.max(0, commandTextCapacity - text.capacity());
    StatusCode status = budget == null || charged == 0
        ? StatusCode.OK : budget.reserve(charged);
    if (!status.isOk()) return status;
    ByteBuffer nextText = text;
    try {
      if (commandTextCapacity > text.capacity()) {
        nextText = ByteBuffer.allocate(commandTextCapacity);
      }
    } catch (OutOfMemoryError error) {
      if (budget != null && charged > 0) budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    command = source;
    status = values.reserve(columns, columns, textBytes, textBytes);
    if (!status.isOk()) {
      if (budget != null && charged > 0) budget.rollback(charged);
      command = null;
      return status;
    }
    text = nextText;
    return values.clearForSize(columns);
  }

  StatusCode assign(
      int column, int source, int target, long high, long value) {
    int targetType = SqlTypeDescriptor.typeId(target);
    if (!SqlTypeDescriptor.canCompare(source, target)) return StatusCode.CONFLICT;
    if (targetType == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      text.clear();
      int bytes = command.copyText(value, text);
      return bytes < 0 ? StatusCode.CONFLICT
          : values.setTextBytes(column, target, text, 0, bytes);
    }
    if (SqlNumericTypeRules.isNumeric(source) && SqlNumericTypeRules.isNumeric(target)) {
      if (SqlTypeDescriptor.isWideDecimal(source)
          || SqlTypeDescriptor.isWideDecimal(target)) {
        return assignDecimal128(column, source, target, high, value);
      }
      StatusCode status = SqlNumericValue.assign(value, source, target, converted, wide);
      if (!status.isOk()
          || SqlNumericValue.compare(value, source, converted.value, target) != 0) {
        return StatusCode.CONFLICT;
      }
      return values.setFixed(column, target, converted.value);
    }
    return values.setFixed(column, target, value);
  }

  private StatusCode assignDecimal128(
      int column, int source, int target, long high, long low) {
    int sourceType = SqlTypeDescriptor.typeId(source);
    if (sourceType != SqlTypeDescriptor.TYPE_ID_DECIMAL
        && !SqlNumericTypeRules.isIntegral(source)
        || SqlTypeDescriptor.typeId(target) != SqlTypeDescriptor.TYPE_ID_DECIMAL) {
      return StatusCode.CONFLICT;
    }
    StatusCode status = sourceType == SqlTypeDescriptor.TYPE_ID_DECIMAL
        ? ExactDecimal128.quantize(
            high,
            low,
            SqlTypeDescriptor.parameterOne(source),
            SqlTypeDescriptor.parameterTwo(source),
            SqlTypeDescriptor.parameterOne(target),
            SqlTypeDescriptor.parameterTwo(target),
            ExactDecimal128.ROUND_HALF_EVEN,
            true,
            converted128,
            wide128)
        : ExactDecimal128.fromLong(
            low,
            SqlTypeDescriptor.parameterOne(target),
            SqlTypeDescriptor.parameterTwo(target),
            converted128,
            wide128);
    if (!status.isOk()) return StatusCode.CONFLICT;
    if (sourceType == SqlTypeDescriptor.TYPE_ID_DECIMAL
        && ExactDecimal128.compare(
            high,
            low,
            SqlTypeDescriptor.parameterTwo(source),
            converted128.high,
            converted128.low,
            SqlTypeDescriptor.parameterTwo(target),
            wide128) != 0) {
      return StatusCode.CONFLICT;
    }
    return SqlTypeDescriptor.isWideDecimal(target)
        ? values.setDecimal128(
            column, target, converted128.high, converted128.low)
        : values.setFixed(column, target, converted128.low);
  }

  void reset() {
    values.reset();
    command = null;
  }

  private static int capacity(int current, int required, int maximum, int initial) {
    return required <= current ? current
        : BoundedArrayGrowth.capacity(current, required, maximum, initial);
  }

  private static int words(int lanes) { return (lanes + Long.SIZE - 1) >>> 6; }
}
