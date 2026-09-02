package io.riverdb.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;

/** Fixed-capacity projection programs and their unresolved column symbols. */
final class SqlProjectionList {
  static final int MAXIMUM_SYMBOLS = SqlShapeLimits.MAX_EXPRESSION_NODES;

  SqlScalarExpression[] expressions = new SqlScalarExpression[8];
  SqlIdentifier[] symbolTables = new SqlIdentifier[16];
  SqlIdentifier[] symbolNames = new SqlIdentifier[16];
  private int expressionCount;
  private int symbolCount;

  SqlProjectionList() {
    SqlProjectionCapacity.initialize(expressions, symbolTables, symbolNames, 0);
  }

  void reset() {
    for (int index = 0; index < expressionCount; index++) expressions[index].reset();
    for (int index = 0; index < symbolCount; index++) {
      symbolTables[index].reset();
      symbolNames[index].reset();
    }
    expressionCount = 0;
    symbolCount = 0;
  }

  StatusCode copyFrom(SqlProjectionList source, int expressionCount) {
    reset();
    if (source == null || expressionCount < 0
        || expressionCount > SqlCommand.MAXIMUM_PROJECTIONS) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!SqlProjectionCapacity.ensureExpressions(source, expressionCount)
        || !SqlProjectionCapacity.ensureExpressions(this, expressionCount)
        || !SqlProjectionCapacity.ensureSymbols(this, source.symbolCount)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    for (int index = 0; index < source.symbolCount; index++) {
      symbolTables[index].copyFrom(source.symbolTables[index]);
      symbolNames[index].copyFrom(source.symbolNames[index]);
    }
    symbolCount = source.symbolCount;
    for (int index = 0; index < expressionCount; index++) {
      StatusCode status = expressions[index].copyFrom(source.expressions[index]);
      if (!status.isOk()) {
        reset();
        return status;
      }
    }
    this.expressionCount = expressionCount;
    return StatusCode.OK;
  }

  SqlScalarExpression expression(int index) {
    SqlScalarExpression expression = SqlProjectionCapacity.expression(this, index);
    if (expression != null && index >= expressionCount) expressionCount = index + 1;
    return expression;
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
    if (!SqlProjectionCapacity.ensureSymbolSlot(this, symbolCount)) {
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
