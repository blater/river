package io.riverdb.engine.sql;

import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;

/** Normalizes one direct child/source comparison into an index candidate. */
final class SqlDescriptorSubqueryIndexMatch {
  private SqlDescriptorSubqueryIndexMatch() { }

  static int find(
      SqlBooleanPredicateProgram program, SqlDescriptorCorrelatedBindings bindings,
      int leaf, int column, SqlComparison wanted) {
    if (program.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_COMPARISON
        || program.leafNegated(leaf)) return -1;
    if (target(bindings, leaf, true, column)
        && bindings.comparison(leaf) == wanted && source(bindings, leaf, false)) {
      return leaf << 1;
    }
    return target(bindings, leaf, false, column)
        && reverse(bindings.comparison(leaf)) == wanted && source(bindings, leaf, true)
        ? leaf << 1 | 1 : -1;
  }

  static boolean conjunctive(SqlBooleanPredicateProgram program, int node) {
    int operator = program.booleanOperator(node);
    return operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF
        || operator == SqlBooleanPredicateProgram.BOOLEAN_AND
            && conjunctive(program, program.booleanLeft(node))
            && conjunctive(program, program.booleanRight(node));
  }

  static SqlComparison reverse(SqlComparison comparison) {
    return switch (comparison) {
      case LESS_THAN -> SqlComparison.GREATER_THAN;
      case LESS_OR_EQUAL -> SqlComparison.GREATER_OR_EQUAL;
      case GREATER_THAN -> SqlComparison.LESS_THAN;
      case GREATER_OR_EQUAL -> SqlComparison.LESS_OR_EQUAL;
      default -> comparison;
    };
  }

  private static boolean target(
      SqlDescriptorCorrelatedBindings bindings, int leaf, boolean left, int column) {
    return bindings.kind(leaf, left) == SqlDescriptorCorrelatedBindings.CHILD
        && bindings.column(leaf, left) == column;
  }

  private static boolean source(
      SqlDescriptorCorrelatedBindings bindings, int leaf, boolean left) {
    byte kind = bindings.kind(leaf, left);
    return kind == SqlDescriptorCorrelatedBindings.LITERAL
        || kind == SqlDescriptorCorrelatedBindings.OUTER;
  }
}
