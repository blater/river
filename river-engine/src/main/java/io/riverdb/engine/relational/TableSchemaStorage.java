package io.riverdb.engine.relational;

import io.riverdb.base.collection.BoundedArrayGrowth;
import io.riverdb.base.column.ColumnBitSet;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Retained actual-count primitive storage for one reusable table schema. */
final class TableSchemaStorage {
  private static final int INITIAL_COLUMNS = 8;
  private static final int INITIAL_CHECK_NODES = 8;
  private static final int INITIAL_TEXT_BYTES = 64;

  TableSchema.ColumnName[] columns = new TableSchema.ColumnName[0];
  long[] defaultValues = new long[0];
  byte[] defaultKinds = new byte[0];
  int[] typeDescriptors = new int[0];
  long[] checkValues = new long[0];
  int[] checkComparisons = new int[0];
  int[] checkTypeDescriptors = new int[0];
  int[] checkNodeCounts = new int[0];
  int[] referenceTableIds = new int[0];
  byte[] checkOperators = new byte[0];
  long[] checkOperands = new long[0];
  int[] checkNodeDescriptors = new int[0];
  int[] checkValidationStack = new int[0];
  byte[] defaultTextBytes = new byte[0];
  final ColumnBitSet notNullColumns = new ColumnBitSet();
  final ColumnBitSet defaultColumns = new ColumnBitSet();
  final ColumnBitSet checkColumns = new ColumnBitSet();
  final ColumnBitSet referenceColumns = new ColumnBitSet();

  StatusCode reserveColumns(int required, int used) {
    if (required <= columns.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        columns.length, required, SqlShapeLimits.MAX_TABLE_COLUMNS, INITIAL_COLUMNS);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      TableSchema.ColumnName[] names = new TableSchema.ColumnName[capacity];
      long[] defaults = new long[capacity];
      byte[] kinds = new byte[capacity];
      int[] types = new int[capacity];
      long[] checkLiterals = new long[capacity];
      int[] comparisons = new int[capacity];
      int[] checkTypes = new int[capacity];
      int[] checkCounts = new int[capacity];
      int[] references = new int[capacity];
      System.arraycopy(columns, 0, names, 0, used);
      System.arraycopy(defaultValues, 0, defaults, 0, used);
      System.arraycopy(defaultKinds, 0, kinds, 0, used);
      System.arraycopy(typeDescriptors, 0, types, 0, used);
      System.arraycopy(checkValues, 0, checkLiterals, 0, used);
      System.arraycopy(checkComparisons, 0, comparisons, 0, used);
      System.arraycopy(checkTypeDescriptors, 0, checkTypes, 0, used);
      System.arraycopy(checkNodeCounts, 0, checkCounts, 0, used);
      System.arraycopy(referenceTableIds, 0, references, 0, used);
      StatusCode status = reserveBits(capacity);
      if (!status.isOk()) return status;
      columns = names;
      defaultValues = defaults;
      defaultKinds = kinds;
      typeDescriptors = types;
      checkValues = checkLiterals;
      checkComparisons = comparisons;
      checkTypeDescriptors = checkTypes;
      checkNodeCounts = checkCounts;
      referenceTableIds = references;
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode reserveChecks(int required, int used) {
    if (required <= checkOperators.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        checkOperators.length, required,
        TableSchema.MAXIMUM_CHECK_PROGRAM_NODES, INITIAL_CHECK_NODES);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      byte[] operators = new byte[capacity];
      long[] operands = new long[capacity];
      int[] descriptors = new int[capacity];
      int[] stack = new int[capacity];
      System.arraycopy(checkOperators, 0, operators, 0, used);
      System.arraycopy(checkOperands, 0, operands, 0, used);
      System.arraycopy(checkNodeDescriptors, 0, descriptors, 0, used);
      checkOperators = operators;
      checkOperands = operands;
      checkNodeDescriptors = descriptors;
      checkValidationStack = stack;
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode reserveColumnName(int column) {
    if (columns[column] != null) return StatusCode.OK;
    try {
      columns[column] = new TableSchema.ColumnName();
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void publishColumn(int column, CharSequence name, boolean nullable, int descriptor) {
    columns[column].set(name);
    if (!nullable) notNullColumns.set(column);
    typeDescriptors[column] = descriptor;
  }

  int find(CharSequence name, int count) {
    for (int column = 0; column < count; column++) {
      if (columns[column].matches(name)) return column;
    }
    return -1;
  }

  boolean isVarchar(int column, int count) {
    return column > 0 && column < count
        && SqlTypeDescriptor.typeId(typeDescriptors[column])
            == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  StatusCode reserveText(int required, int used) {
    if (required < 0 || required > TableSchema.MAXIMUM_ROW_BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (required <= defaultTextBytes.length) return StatusCode.OK;
    int capacity = BoundedArrayGrowth.capacity(
        defaultTextBytes.length, required, TableSchema.MAXIMUM_ROW_BYTES, INITIAL_TEXT_BYTES);
    if (capacity < 0) return StatusCode.RESOURCE_EXHAUSTED;
    try {
      byte[] text = new byte[capacity];
      System.arraycopy(defaultTextBytes, 0, text, 0, used);
      defaultTextBytes = text;
      return StatusCode.OK;
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  void clearBits() {
    notNullColumns.clearForSize(notNullColumns.bitCount());
    defaultColumns.clearForSize(defaultColumns.bitCount());
    checkColumns.clearForSize(checkColumns.bitCount());
    referenceColumns.clearForSize(referenceColumns.bitCount());
  }

  int maximumRowBytes(int count) {
    int bytes = 32 + (count + Byte.SIZE - 1) / Byte.SIZE;
    for (int column = 0; column < count; column++) {
      bytes += addedValueBytes(typeDescriptors[column]);
    }
    return bytes;
  }

  int addedRowBytes(int descriptor, int existingColumns) {
    return addedValueBytes(descriptor)
        + (existingColumns + 1 + Byte.SIZE - 1) / Byte.SIZE
        - (existingColumns + Byte.SIZE - 1) / Byte.SIZE;
  }

  private static int addedValueBytes(int descriptor) {
    int typeId = SqlTypeDescriptor.typeId(descriptor);
    int bytes = SqlTypeDescriptor.isWideDecimal(descriptor) ? Long.BYTES * 2 : Long.BYTES;
    return typeId == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? bytes + SqlTypeDescriptor.parameterOne(descriptor) * 4 : bytes;
  }

  private StatusCode reserveBits(int capacity) {
    StatusCode status = growBits(notNullColumns, capacity);
    if (status.isOk()) status = growBits(defaultColumns, capacity);
    if (status.isOk()) status = growBits(checkColumns, capacity);
    return status.isOk() ? growBits(referenceColumns, capacity) : status;
  }

  private static StatusCode growBits(ColumnBitSet bits, int capacity) {
    long[] retained;
    try {
      retained = new long[bits.wordCount()];
    } catch (OutOfMemoryError ignored) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    bits.copyWords(retained);
    StatusCode status = bits.reserve(capacity, SqlShapeLimits.MAX_TABLE_COLUMNS);
    if (status.isOk()) status = bits.clearForSize(capacity);
    for (int index = 0; status.isOk() && index < retained.length; index++) {
      if (!bits.setWord(index, retained[index])) status = StatusCode.CORRUPTION;
    }
    return status;
  }
}
