package io.riverdb.engine.sql;

/** Reusable sequential operand state shared by JOIN ON and WHERE evaluators. */
final class SqlBooleanPredicateWorkspace {
  final SqlExpressionEvaluator columns;
  final SqlPredicateOperandEvaluator expressions;
  final SqlPredicateOperand left = new SqlPredicateOperand();
  final SqlPredicateOperand right = new SqlPredicateOperand();
  final SqlPredicateOperand lower = new SqlPredicateOperand();
  final SqlPredicateOperand upper = new SqlPredicateOperand();

  SqlBooleanPredicateWorkspace(
      SqlExpressionEvaluator columns, SqlTemporalContext temporal) {
    this.columns = columns;
    expressions = new SqlPredicateOperandEvaluator(columns, temporal);
  }

  void clearOperands() {
    left.clear();
    right.clear();
    lower.clear();
    upper.clear();
  }

  void reset() {
    clearOperands();
    expressions.reset();
  }
}
