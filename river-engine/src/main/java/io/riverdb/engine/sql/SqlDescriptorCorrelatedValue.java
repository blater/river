package io.riverdb.engine.sql;

/** Reads one literal, child, or outer operand from a bound correlation. */
final class SqlDescriptorCorrelatedValue {
  private SqlDescriptorCorrelatedValue() { }

  static boolean isNull(
      byte kind, int column,
      SqlDescriptorValueSource child, SqlDescriptorValueSource outer) {
    return kind == SqlDescriptorCorrelatedBindings.NULL
        || kind == SqlDescriptorCorrelatedBindings.CHILD && child.isNull(column)
        || kind == SqlDescriptorCorrelatedBindings.OUTER && outer.isNull(column);
  }

  static long value(
      byte kind, int column, long literal,
      SqlDescriptorValueSource child, SqlDescriptorValueSource outer) {
    return kind == SqlDescriptorCorrelatedBindings.CHILD ? child.value(column)
        : kind == SqlDescriptorCorrelatedBindings.OUTER ? outer.value(column) : literal;
  }

  static long high(
      byte kind, int column, long literal,
      SqlDescriptorValueSource child, SqlDescriptorValueSource outer) {
    return kind == SqlDescriptorCorrelatedBindings.CHILD ? child.highValue(column)
        : kind == SqlDescriptorCorrelatedBindings.OUTER
            ? outer.highValue(column) : literal;
  }
}
