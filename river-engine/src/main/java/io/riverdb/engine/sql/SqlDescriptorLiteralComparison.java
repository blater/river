package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlBooleanPredicateProgram;

/** Compares one descriptor-row column with a bound scalar literal. */
final class SqlDescriptorLiteralComparison {
  private final SqlPredicateOperand text = new SqlPredicateOperand();
  private SqlDescriptorPredicateBindings bindings;
  private final ExactDecimal128.Scratch decimalScratch = new ExactDecimal128.Scratch();
  private StatusCode status = StatusCode.OK;

  void prepare(
      SqlBooleanPredicateProgram program, SqlDescriptorPredicateBindings bound) {
    bindings = bound;
    status = StatusCode.OK;
  }

  int compare(
      int leaf,
      int column,
      SqlDescriptorValueSource values,
      long literalHigh,
      long literal,
      int descriptor) {
    status = StatusCode.OK;
    if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      status = values.text(column, bindings.columnDescriptor(leaf), text);
      if (!status.isOk()) return 0;
      int compared = SqlBooleanTextComparator.compareLiteral(
          text, bindings.command(), literal);
      if (compared == Integer.MIN_VALUE) status = StatusCode.INVALID_EXTERNAL_INPUT;
      return compared;
    }
    int columnDescriptor = bindings.columnDescriptor(leaf);
    if (SqlNumericTypeRules.isNumeric(columnDescriptor)
        && SqlNumericTypeRules.isNumeric(descriptor)) {
      return SqlNumericComparison.compare(
          values.highValue(column),
          values.value(column),
          columnDescriptor,
          literalHigh,
          literal,
          descriptor,
          decimalScratch);
    }
    return Long.compare(values.value(column), literal);
  }

  StatusCode status() { return status; }
}
