package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlBooleanPredicateProgram;

/** Short-circuiting tree walk for SQL three-valued predicate evaluation. */
final class SqlDescriptorPredicateTree {
  private final SqlDescriptorPredicateLeaf leaf = new SqlDescriptorPredicateLeaf();
  private SqlBooleanPredicateProgram program;

  void prepare(
      SqlBooleanPredicateProgram source, SqlDescriptorPredicateBindings bindings,
      SqlDescriptorSubqueryExecution subqueries) {
    program = source;
    leaf.prepare(source, bindings, subqueries);
  }

  StatusCode evaluate(SqlDescriptorValueSource values, SqlDescriptorPredicateEvaluation.Match match) {
    leaf.begin();
    int result = program.isAvailable() ? evaluateNode(program.root(), values) : 1;
    match.set(result == 1);
    return leaf.status();
  }

  private int evaluateNode(int node, SqlDescriptorValueSource values) {
    int operator = program.booleanOperator(node);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF) {
      return leaf.evaluate(program.booleanLeft(node), values);
    }
    int left = evaluateNode(program.booleanLeft(node), values);
    if (!leaf.status().isOk()) return -1;
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_NOT) return left < 0 ? -1 : 1 - left;
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_AND && left == 0) return 0;
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_OR && left == 1) return 1;
    int right = evaluateNode(program.booleanRight(node), values);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_AND) {
      return right == 0 ? 0 : left < 0 || right < 0 ? -1 : 1;
    }
    return right == 1 ? 1 : left < 0 || right < 0 ? -1 : 0;
  }
}
