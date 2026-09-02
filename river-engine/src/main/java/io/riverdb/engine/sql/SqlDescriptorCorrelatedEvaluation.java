package io.riverdb.engine.sql;

import io.riverdb.base.type.ExactDecimal128;
import io.riverdb.base.type.SqlNumericTypeRules;
import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;

/** Evaluates one already-bound descriptor correlation without retaining row state. */
final class SqlDescriptorCorrelatedEvaluation {
  private final ExactDecimal128.Scratch decimal = new ExactDecimal128.Scratch();
  private SqlBooleanPredicateProgram program;
  private SqlDescriptorCorrelatedBindings bindings;

  int evaluate(
      SqlBooleanPredicateProgram source,
      SqlDescriptorCorrelatedBindings sourceBindings,
      SqlDescriptorValueSource child,
      SqlDescriptorValueSource outer) {
    program = source;
    bindings = sourceBindings;
    return program.isAvailable() ? node(program.root(), child, outer) : 1;
  }

  private int node(
      int node, SqlDescriptorValueSource child, SqlDescriptorValueSource outer) {
    int operator = program.booleanOperator(node);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF) {
      return leaf(program.booleanLeft(node), child, outer);
    }
    int left = node(program.booleanLeft(node), child, outer);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_NOT) return left < 0 ? -1 : 1 - left;
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_AND && left == 0) return 0;
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_OR && left == 1) return 1;
    int right = node(program.booleanRight(node), child, outer);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_AND) {
      return right == 0 ? 0 : left < 0 || right < 0 ? -1 : 1;
    }
    return right == 1 ? 1 : left < 0 || right < 0 ? -1 : 0;
  }

  private int leaf(
      int leaf, SqlDescriptorValueSource child, SqlDescriptorValueSource outer) {
    byte leftKind = bindings.leftKind(leaf);
    int leftColumn = bindings.leftColumn(leaf);
    if (program.leafTest(leaf) == SqlBooleanPredicateProgram.TEST_NULL) {
      boolean nullValue = SqlDescriptorCorrelatedValue.isNull(
          leftKind, leftColumn, child, outer);
      return nullValue != program.leafNegated(leaf) ? 1 : 0;
    }
    if (program.leafTest(leaf) == SqlBooleanPredicateProgram.TEST_MEMBERSHIP) {
      return membership(leaf, leftKind, leftColumn, child, outer);
    }
    return comparison(leaf, leftKind, leftColumn, child, outer);
  }

  private int membership(
      int leaf, byte kind, int column,
      SqlDescriptorValueSource child, SqlDescriptorValueSource outer) {
    if (SqlDescriptorCorrelatedValue.isNull(kind, column, child, outer)) return -1;
    return SqlDescriptorCorrelatedMembership.evaluate(
        program, leaf,
        SqlDescriptorCorrelatedValue.high(
            kind, column, bindings.leftHigh(leaf), child, outer),
        SqlDescriptorCorrelatedValue.value(
            kind, column, bindings.leftValue(leaf), child, outer),
        bindings.leftDescriptor(leaf), decimal);
  }

  private int comparison(
      int leaf, byte leftKind, int leftColumn,
      SqlDescriptorValueSource child, SqlDescriptorValueSource outer) {
    byte rightKind = bindings.rightKind(leaf);
    int rightColumn = bindings.rightColumn(leaf);
    if (SqlDescriptorCorrelatedValue.isNull(leftKind, leftColumn, child, outer)
        || SqlDescriptorCorrelatedValue.isNull(rightKind, rightColumn, child, outer)) {
      return -1;
    }
    int compared = compareValues(
        leaf, leftKind, leftColumn, rightKind, rightColumn, child, outer);
    return SqlDescriptorComparison.matches(compared, bindings.comparison(leaf)) ? 1 : 0;
  }

  private int compareValues(
      int leaf, byte leftKind, int leftColumn, byte rightKind, int rightColumn,
      SqlDescriptorValueSource child, SqlDescriptorValueSource outer) {
    long left = SqlDescriptorCorrelatedValue.value(
        leftKind, leftColumn, bindings.leftValue(leaf), child, outer);
    long right = SqlDescriptorCorrelatedValue.value(
        rightKind, rightColumn, bindings.rightValue(leaf), child, outer);
    int leftType = bindings.leftDescriptor(leaf);
    int rightType = bindings.rightDescriptor(leaf);
    if (!SqlNumericTypeRules.isNumeric(leftType)
        || !SqlNumericTypeRules.isNumeric(rightType)) return Long.compare(left, right);
    long leftHigh = SqlDescriptorCorrelatedValue.high(
        leftKind, leftColumn, bindings.leftHigh(leaf), child, outer);
    long rightHigh = SqlDescriptorCorrelatedValue.high(
        rightKind, rightColumn, bindings.rightHigh(leaf), child, outer);
    return SqlNumericComparison.compare(
        leftHigh, left, leftType, rightHigh, right, rightType, decimal);
  }
}
