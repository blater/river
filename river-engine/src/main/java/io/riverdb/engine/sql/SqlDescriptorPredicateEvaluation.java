package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.sql.SqlBooleanPredicateProgram;

/** Reusable descriptor predicate evaluator over either row representation. */
final class SqlDescriptorPredicateEvaluation {
  private final SqlDescriptorPredicateTree tree = new SqlDescriptorPredicateTree();

  void prepare(
      SqlBooleanPredicateProgram program, SqlDescriptorPredicateBindings bindings,
      SqlDescriptorSubqueryExecution subqueries) {
    tree.prepare(program, bindings, subqueries);
  }

  StatusCode evaluate(SqlDescriptorValueSource values, Match result) {
    return tree.evaluate(values, result);
  }

  static final class Match {
    private boolean matched;
    void set(boolean value) { matched = value; }
    boolean value() { return matched; }
  }
}
