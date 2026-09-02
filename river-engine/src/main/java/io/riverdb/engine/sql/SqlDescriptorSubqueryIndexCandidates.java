package io.riverdb.engine.sql;

import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;

/** Exposes mandatory child-column predicates to the shared key selector. */
final class SqlDescriptorSubqueryIndexCandidates
    implements SqlDescriptorIndexCandidateSource {
  private SqlBooleanPredicateProgram program;
  private SqlDescriptorCorrelatedBindings bindings;

  void prepare(
      SqlBooleanPredicateProgram source, SqlDescriptorCorrelatedBindings bound) {
    program = source;
    bindings = bound;
  }

  boolean available() {
    return program.isAvailable()
        && SqlDescriptorSubqueryIndexMatch.conjunctive(program, program.root());
  }

  @Override public int find(int column, SqlComparison wanted) {
    if (!available()) return -1;
    for (int leaf = 0; leaf < program.leafCount(); leaf++) {
      int encoded = SqlDescriptorSubqueryIndexMatch.find(
          program, bindings, leaf, column, wanted);
      if (encoded >= 0) return encoded;
    }
    return -1;
  }

  SqlComparison comparison(int encoded) {
    SqlComparison result = program.comparison(encoded >>> 1);
    return (encoded & 1) == 0
        ? result : SqlDescriptorSubqueryIndexMatch.reverse(result);
  }
}
