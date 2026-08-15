package io.riverdb.sql;

/** Fixed-capacity projection programs and their unresolved column symbols. */
final class SqlProjectionList {
  static final int MAXIMUM_SYMBOLS = SqlCommand.MAXIMUM_COLUMNS * 2;

  private final SqlScalarExpression[] expressions =
      new SqlScalarExpression[SqlCommand.MAXIMUM_COLUMNS];
  private final SqlIdentifier[] symbolTables = new SqlIdentifier[MAXIMUM_SYMBOLS];
  private final SqlIdentifier[] symbolNames = new SqlIdentifier[MAXIMUM_SYMBOLS];
  private int symbolCount;

  SqlProjectionList() {
    for (int index = 0; index < expressions.length; index++) {
      expressions[index] = new SqlScalarExpression();
    }
    for (int index = 0; index < symbolNames.length; index++) {
      symbolTables[index] = new SqlIdentifier();
      symbolNames[index] = new SqlIdentifier();
    }
  }

  void reset() {
    for (SqlScalarExpression expression : expressions) {
      expression.reset();
    }
    for (int index = 0; index < symbolCount; index++) {
      symbolTables[index].reset();
      symbolNames[index].reset();
    }
    symbolCount = 0;
  }

  void copyFrom(SqlProjectionList source) {
    reset();
    for (int index = 0; index < source.symbolCount; index++) {
      symbolTables[index].copyFrom(source.symbolTables[index]);
      symbolNames[index].copyFrom(source.symbolNames[index]);
    }
    symbolCount = source.symbolCount;
    for (int index = 0; index < expressions.length; index++) {
      expressions[index].copyFrom(source.expressions[index]);
    }
  }

  SqlScalarExpression expression(int index) {
    return index >= 0 && index < expressions.length ? expressions[index] : null;
  }

  int registerSymbol(CharSequence table, CharSequence name) {
    if (name == null || name.length() == 0
        || name.length() > SqlIdentifier.MAXIMUM_LENGTH
        || table == null || table.length() > SqlIdentifier.MAXIMUM_LENGTH) {
      return -1;
    }
    for (int index = 0; index < symbolCount; index++) {
      if (same(symbolTables[index], table) && same(symbolNames[index], name)) {
        return index;
      }
    }
    if (symbolCount >= symbolNames.length) {
      return -1;
    }
    symbolTables[symbolCount].copyFrom(table);
    symbolNames[symbolCount].copyFrom(name);
    return symbolCount++;
  }

  int symbolCount() {
    return symbolCount;
  }

  SqlIdentifier symbolTable(int index) {
    return index >= 0 && index < symbolCount ? symbolTables[index] : null;
  }

  SqlIdentifier symbolName(int index) {
    return index >= 0 && index < symbolCount ? symbolNames[index] : null;
  }

  private static boolean same(CharSequence left, CharSequence right) {
    if (left.length() != right.length()) {
      return false;
    }
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) {
        return false;
      }
    }
    return true;
  }
}
