package io.riverdb.engine.sql;

import io.riverdb.engine.schema.TableDescriptor;

/** Reusable descriptor column name view. */
final class SqlDescriptorColumnName implements CharSequence {
  private final char[] chars = new char[io.riverdb.sql.SqlIdentifier.MAXIMUM_LENGTH];
  private int length;

  SqlDescriptorColumnName load(TableDescriptor table, int column) {
    length = table.columns().copyNameChars(column, chars, 0);
    return this;
  }

  @Override public int length() { return length; }
  @Override public char charAt(int index) { return chars[index]; }
  @Override public CharSequence subSequence(int start, int end) {
    return new String(chars, start, end - start);
  }
}
