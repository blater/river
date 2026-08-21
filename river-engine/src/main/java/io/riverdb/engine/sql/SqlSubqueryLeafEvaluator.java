package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Lazy callback for canonical predicate-subquery leaves. */
interface SqlSubqueryLeafEvaluator {
  StatusCode evaluate(int edge, SqlPredicateOperand operand, Truth result);

  final class Truth {
    private int value;
    int value() { return value; }
    void set(int truth) { value = truth; }
  }
}
