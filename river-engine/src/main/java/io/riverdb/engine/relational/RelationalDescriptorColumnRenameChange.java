package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.ColumnConstraintDescriptorSet;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.TableDescriptor;

/** Copies one descriptor column set while replacing exactly one name. */
final class RelationalDescriptorColumnRenameChange {
  private final ColumnConstraintDescriptorSet.Result constraints =
      new ColumnConstraintDescriptorSet.Result();
  private final ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
  private final RelationalDescriptorKeyCopy keys = new RelationalDescriptorKeyCopy();

  StatusCode build(
      TableDescriptor current,
      CharSequence currentName,
      CharSequence renamedName,
      TableDescriptor.Result result,
      StatusDetail detail) {
    if (result != null) result.reset();
    if (detail != null) detail.reset();
    if (current == null || currentName == null || renamedName == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int renamed = current.findColumn(currentName);
    if (renamed < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (current.findColumn(renamedName) >= 0) return StatusCode.CONFLICT;
    StatusCode status = copyColumns(current, renamed, renamedName);
    return status.isOk()
        ? keys.table(current, columns.value(), result, detail) : status;
  }

  private StatusCode copyColumns(
      TableDescriptor current, int renamed, CharSequence renamedName) {
    int count = current.columnCount();
    ColumnArrays arrays;
    try {
      arrays = new ColumnArrays(count);
      for (int column = 0; column < count; column++) {
        arrays.copy(current, column, column == renamed ? renamedName : null);
      }
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = ColumnConstraintDescriptorSet.create(
        arrays.types, arrays.defaultKinds, arrays.defaultHighs, arrays.defaultValues,
        arrays.checkComparisons, arrays.checkTypes, arrays.checkHighs, arrays.checkValues,
        count, constraints);
    return status.isOk() ? ColumnDescriptorSet.createConstrained(
        arrays.types, arrays.names, arrays.nullable,
        constraints.value(), columns, null) : status;
  }

  private static final class ColumnArrays {
    final int[] types;
    final CharSequence[] names;
    final boolean[] nullable;
    final byte[] defaultKinds;
    final long[] defaultHighs;
    final long[] defaultValues;
    final byte[] checkComparisons;
    final int[] checkTypes;
    final long[] checkHighs;
    final long[] checkValues;

    ColumnArrays(int count) {
      types = new int[count];
      names = new CharSequence[count];
      nullable = new boolean[count];
      defaultKinds = new byte[count];
      defaultHighs = new long[count];
      defaultValues = new long[count];
      checkComparisons = new byte[count];
      checkTypes = new int[count];
      checkHighs = new long[count];
      checkValues = new long[count];
    }

    void copy(TableDescriptor source, int column, CharSequence replacement) {
      types[column] = source.typeDescriptorAt(column);
      names[column] = replacement == null
          ? name(source.columns(), column) : name(replacement);
      nullable[column] = source.isNullable(column);
      defaultKinds[column] = (byte) source.columns().defaultKindAt(column);
      defaultHighs[column] = source.columns().defaultHighAt(column);
      defaultValues[column] = source.columns().defaultValueAt(column);
      checkComparisons[column] = (byte) source.columns().checkComparisonAt(column);
      checkTypes[column] = source.columns().checkTypeAt(column);
      checkHighs[column] = source.columns().checkHighAt(column);
      checkValues[column] = source.columns().checkValueAt(column);
    }

    private static String name(ColumnDescriptorSet source, int column) {
      char[] chars = new char[source.nameByteLength(column)];
      int length = source.copyNameChars(column, chars, 0);
      return new String(chars, 0, length);
    }

    private static String name(CharSequence source) {
      char[] chars = new char[source.length()];
      for (int index = 0; index < chars.length; index++) chars[index] = source.charAt(index);
      return new String(chars);
    }
  }
}
