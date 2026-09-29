package io.riverdb.engine.sql;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.BoundedByteSource;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.ExactDecimal;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlNumericValue;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.SqlMutationValues;
import io.riverdb.engine.relational.SqlValueAccess;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.sql.SqlCommand;
import java.nio.ByteBuffer;

/** Reusable typed value storage for a primary-key point lookup. */
final class SqlDescriptorPrimaryValues implements SqlValueAccess {
  private static final int LANE_BYTES = 2 * Long.BYTES + 3 * Integer.BYTES
      + Integer.BYTES + 1;
  private static final ByteBuffer EMPTY_TEXT = ByteBuffer.allocate(0);
  private final SqlMutationValues values = new SqlMutationValues();
  private SqlBlockRow[] borrowed = new SqlBlockRow[0];
  private int[] borrowedColumns = new int[0];
  private int[] usedColumns = new int[0];
  private int usedCount;
  /* Runtime value storage is bounded by row admission, not declaration width. */
  private ByteBuffer text = EMPTY_TEXT;
  private final SqlSessionShapeBudget budget;
  private final ExactDecimal.LongValue converted = new ExactDecimal.LongValue();
  private final ExactDecimal.WideScratch wide = new ExactDecimal.WideScratch();
  private final ExactDecimal128.Value converted128 = new ExactDecimal128.Value();
  private final ExactDecimal128.Scratch wide128 = new ExactDecimal128.Scratch();
  private TableDescriptor table;
  private SqlCommand command;

  SqlDescriptorPrimaryValues() { this(null); }

  SqlDescriptorPrimaryValues(SqlSessionShapeBudget shapeBudget) {
    budget = shapeBudget;
  }

  SqlValueAccess buffer() { return this; }

  StatusCode begin(TableDescriptor table, int textBytes, SqlCommand source) {
    int columns = table.columnCount();
    int laneCapacity = capacity(values.capacity(), columns, columns, 8);
    int borrowCapacity = capacity(borrowed.length, columns, columns, 8);
    int valueTextCapacity = capacity(values.textCapacity(), textBytes, textBytes, 8);
    int commandTextCapacity = textBytes;
    if (laneCapacity < 0 || borrowCapacity < 0 || valueTextCapacity < 0
        || commandTextCapacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long charged = (long) (laneCapacity - values.capacity()) * LANE_BYTES
        + (long) (borrowCapacity - borrowed.length) * (Long.BYTES + 2 * Integer.BYTES)
        + valueTextCapacity - values.textCapacity()
        + Math.max(0, commandTextCapacity - text.capacity());
    StatusCode status = budget == null || charged == 0
        ? StatusCode.OK : budget.reserve(charged);
    if (!status.isOk()) return status;
    ByteBuffer nextText = text;
    SqlBlockRow[] nextBorrowed = borrowed;
    int[] nextBorrowedColumns = borrowedColumns;
    int[] nextUsedColumns = usedColumns;
    try {
      if (commandTextCapacity > text.capacity()) {
        nextText = ByteBuffer.allocate(commandTextCapacity);
      }
      if (borrowCapacity > borrowed.length) {
        nextBorrowed = new SqlBlockRow[borrowCapacity];
        nextBorrowedColumns = new int[borrowCapacity];
        nextUsedColumns = new int[borrowCapacity];
      }
    } catch (OutOfMemoryError error) {
      if (budget != null && charged > 0) budget.rollback(charged);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    clearBorrowed();
    values.reset();
    this.table = null;
    command = source;
    status = values.reserve(table, textBytes);
    if (!status.isOk()) {
      if (budget != null && charged > 0) budget.rollback(charged);
      command = null;
      return status;
    }
    text = nextText;
    borrowed = nextBorrowed;
    borrowedColumns = nextBorrowedColumns;
    usedColumns = nextUsedColumns;
    status = values.begin(table, null);
    if (status.isOk()) this.table = table;
    return status;
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
    StatusCode status = values.setFixed(column, target, value);
    return status == StatusCode.INVALID_EXTERNAL_INPUT
        ? StatusCode.CONFLICT : status;
  }

  StatusCode setText(
      int column, int descriptor, char[] chars, int offset, int length) {
    return values.setText(column, descriptor, chars, offset, length);
  }

  /** The row remains borrowed only until the scan cursor encodes its bounds. */
  StatusCode borrowAdmittedText(
      int column, int sourceDescriptor, int descriptor,
      SqlBlockRow source, int sourceColumn) {
    if (table == null || source == null || column < 0 || column >= table.columnCount()
        || borrowed[column] != null || values.descriptorAt(column) != 0
        || sourceColumn < 0 || sourceColumn >= source.count()
        || source.nullValue(sourceColumn) || !source.hasUtf8(sourceColumn)
        || table.typeDescriptorAt(column) != descriptor
        || SqlTypeDescriptor.typeId(sourceDescriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR
        || SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (SqlTypeDescriptor.parameterOne(sourceDescriptor)
            > SqlTypeDescriptor.parameterOne(descriptor)
        && Utf8Text.trustedScalarCount(source.utf8Slice(sourceColumn),
            0, source.utf8Length(sourceColumn))
            > SqlTypeDescriptor.parameterOne(descriptor)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    borrowed[column] = source;
    borrowedColumns[column] = sourceColumn;
    usedColumns[usedCount++] = column;
    return StatusCode.OK;
  }

  @Override public int count() { return values.count(); }
  @Override public int descriptorAt(int column) {
    return borrowedAt(column) ? table.typeDescriptorAt(column) : values.descriptorAt(column);
  }
  @Override public boolean isNull(int column) {
    return borrowedAt(column) ? false : values.isNull(column);
  }
  @Override public long valueAt(int column) {
    return borrowedAt(column) ? 0 : values.valueAt(column);
  }
  @Override public long highValueAt(int column) {
    return borrowedAt(column) ? 0 : values.highValueAt(column);
  }
  @Override public int textByteLengthAt(int column) {
    return borrowedAt(column) ? borrowed[column].utf8Length(borrowedColumns[column])
        : values.textByteLengthAt(column);
  }
  @Override public int textByteOffsetAt(int column) {
    return borrowedAt(column) ? 0 : values.textByteOffsetAt(column);
  }
  @Override public BoundedByteSource textSource(int column) {
    return borrowedAt(column) ? borrowed[column].utf8Slice(borrowedColumns[column])
        : values.textSource(column);
  }
  @Override public int copyTextChars(
      int column, char[] destination, int destinationOffset) {
    if (!borrowedAt(column)) return values.copyTextChars(column, destination, destinationOffset);
    SqlBlockRow source = borrowed[column];
    int sourceColumn = borrowedColumns[column];
    int length = source.textLength(sourceColumn);
    if (destination == null || destinationOffset < 0
        || destinationOffset > destination.length - length) return -1;
    char[] chars = source.text(sourceColumn);
    if (chars == null) return -1;
    System.arraycopy(chars, 0, destination, destinationOffset, length);
    return length;
  }

  private boolean borrowedAt(int column) {
    return column >= 0 && column < borrowed.length && borrowed[column] != null;
  }

  private void clearBorrowed() {
    for (int index = 0; index < usedCount; index++) borrowed[usedColumns[index]] = null;
    usedCount = 0;
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
    clearBorrowed();
    values.reset();
    table = null;
    command = null;
  }

  private static int capacity(int current, int required, int maximum, int initial) {
    return required <= current ? current
        : BoundedArrayGrowth.capacity(current, required, maximum, initial);
  }

}
