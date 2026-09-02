package io.riverdb.engine.sql;

import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;

/** Finds mandatory direct values for one role/key part in a bound predicate. */
final class SqlUniversalDescriptorIndexMatcher {
  private SqlUniversalDescriptorIndexMatcher() { }

  static boolean find(
      SqlBoundBooleanPredicateProgram program, SqlBoundJoinContext context,
      int role, int column, SqlComparison comparison,
      SqlBlockColumnLineage lineage,
      SqlUniversalDescriptorIndexBinding result) {
    if (program == null || !program.available()
        || !SqlBoundPredicateConjunction.only(program, program.root())) {
      return false;
    }
    for (int leaf = 0; leaf < program.leafCount(); leaf++) {
      if (SqlUniversalDescriptorIndexLeaf.find(
          program, context, leaf, role, column, comparison, lineage, result)) return true;
    }
    return false;
  }

}
