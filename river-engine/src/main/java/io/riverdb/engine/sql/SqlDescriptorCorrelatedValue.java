package io.riverdb.engine.sql;

import io.riverdb.engine.relational.SqlValueAccess;

/** Reads one literal, child, or outer operand from a bound correlation. */
final class SqlDescriptorCorrelatedValue {
  private SqlDescriptorCorrelatedValue() { }

  static boolean isNull(
      byte kind, int column,
      SqlValueAccess child, SqlValueAccess outer) {
    return kind == SqlDescriptorCorrelatedBindings.NULL
        || kind == SqlDescriptorCorrelatedBindings.CHILD && child.isNull(column)
        || kind == SqlDescriptorCorrelatedBindings.OUTER && outer.isNull(column);
  }

  static long value(
      byte kind, int column, long literal,
      SqlValueAccess child, SqlValueAccess outer) {
    return kind == SqlDescriptorCorrelatedBindings.CHILD ? child.valueAt(column)
        : kind == SqlDescriptorCorrelatedBindings.OUTER ? outer.valueAt(column) : literal;
  }

  static long high(
      byte kind, int column, long literal,
      SqlValueAccess child, SqlValueAccess outer) {
    return kind == SqlDescriptorCorrelatedBindings.CHILD ? child.highValueAt(column)
        : kind == SqlDescriptorCorrelatedBindings.OUTER
            ? outer.highValueAt(column) : literal;
  }
}
