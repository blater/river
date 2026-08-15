package io.riverdb.engine.sql;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlIdentifier;
import io.riverdb.sql.SqlCommand;

/** Compact typed output schema for one cardinality-changing query block. */
final class SqlBlockSchema {
  private final Name[] names = new Name[TableSchema.MAXIMUM_COLUMNS];
  private final int[] descriptors = new int[TableSchema.MAXIMUM_COLUMNS];
  private int count;
  private long nullableMask;

  SqlBlockSchema() {
    for (int index = 0; index < names.length; index++) names[index] = new Name();
  }

  void reset() {
    for (int index = 0; index < count; index++) {
      names[index].reset();
      descriptors[index] = 0;
    }
    count = 0;
    nullableMask = 0;
  }

  void set(int columns) {
    reset();
    count = columns;
  }

  void setColumn(int column, CharSequence name, int descriptor, boolean nullable) {
    names[column].set(name);
    descriptors[column] = descriptor;
    if (nullable) nullableMask |= 1L << column;
  }

  void setOutputNames(SqlCommand command) {
    for (int column = 0; column < count; column++) {
      names[column].set(command.columnOutputName(column));
    }
  }

  void copyFrom(SqlBlockSchema source) {
    set(source.count);
    for (int column = 0; column < count; column++) {
      setColumn(
          column, source.name(column), source.descriptor(column), source.nullable(column));
    }
  }

  int count() { return count; }
  int descriptor(int column) { return descriptors[column]; }
  boolean nullable(int column) { return (nullableMask & 1L << column) != 0; }
  boolean varchar(int column) {
    return SqlTypeDescriptor.typeId(descriptor(column))
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }
  CharSequence name(int column) { return names[column]; }

  int find(CharSequence name) {
    int found = -1;
    for (int column = 0; column < count; column++) {
      if (!SqlBindingNames.same(names[column], name)) continue;
      if (found >= 0) return -2;
      found = column;
    }
    return found;
  }

  private static final class Name implements CharSequence {
    private final char[] value = new char[SqlIdentifier.MAXIMUM_LENGTH];
    private int length;

    void reset() { length = 0; }
    void set(CharSequence source) {
      length = source.length();
      for (int index = 0; index < length; index++) value[index] = source.charAt(index);
    }
    @Override public int length() { return length; }
    @Override public char charAt(int index) { return value[index]; }
    @Override public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }
}
