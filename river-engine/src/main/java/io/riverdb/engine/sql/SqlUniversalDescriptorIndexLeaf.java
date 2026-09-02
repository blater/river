package io.riverdb.engine.sql;

import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;

/** Matches one universal predicate leaf to one role-local index part. */
final class SqlUniversalDescriptorIndexLeaf {
  private SqlUniversalDescriptorIndexLeaf() { }

  static boolean find(
      SqlBoundBooleanPredicateProgram program, SqlBoundJoinContext context,
      int leaf, int role, int column, SqlComparison comparison,
      SqlBlockColumnLineage lineage, SqlUniversalDescriptorIndexBinding result) {
    if (between(program, context, leaf, role, column, comparison, lineage, result)) {
      return true;
    }
    if (program.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_COMPARISON) return false;
    int side = targetSide(program, context, leaf, role, column, lineage);
    if (side < 0
        || normalized(program.comparison(leaf), side) != comparison) return false;
    int valueSide = side == 0 ? 1 : 0;
    if (literal(program, leaf, valueSide)) {
      if (result != null) result.literal(program, leaf, valueSide);
      return true;
    }
    int outer = outerRole(program, context, leaf, valueSide, role);
    if (outer < 0) return false;
    if (result != null) result.outer(outer, program.rawColumn(leaf, valueSide));
    return true;
  }

  private static boolean between(
      SqlBoundBooleanPredicateProgram program, SqlBoundJoinContext context,
      int leaf, int role, int column, SqlComparison comparison,
      SqlBlockColumnLineage lineage, SqlUniversalDescriptorIndexBinding result) {
    if (program.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_BETWEEN
        || program.negated(leaf)
        || targetSide(program, context, leaf, role, column, lineage) != 0) return false;
    int valueSide = comparison == SqlComparison.GREATER_OR_EQUAL
        ? SqlBooleanPredicateProgram.PROGRAM_LOWER
        : comparison == SqlComparison.LESS_OR_EQUAL
            ? SqlBooleanPredicateProgram.PROGRAM_UPPER : -1;
    if (valueSide < 0 || !literal(program, leaf, valueSide)) return false;
    if (result != null) result.literal(program, leaf, valueSide);
    return true;
  }

  private static int targetSide(
      SqlBoundBooleanPredicateProgram program, SqlBoundJoinContext context,
      int leaf, int role, int column, SqlBlockColumnLineage lineage) {
    for (int side = 0; side < 2; side++) {
      int raw = program.rawColumn(leaf, side);
      int mapped = lineage == null ? raw : lineage.baseColumn(raw);
      if (program.nodeCount(leaf, side) == 1 && mapped == column
          && localRole(context, program.scope(leaf, side, 0)) == role) return side;
    }
    return -1;
  }

  private static boolean literal(
      SqlBoundBooleanPredicateProgram program, int leaf, int side) {
    return program.nodeCount(leaf, side) == 1
        && program.operator(leaf, side, 0) == SqlScalarExpression.LITERAL;
  }

  private static int outerRole(
      SqlBoundBooleanPredicateProgram program, SqlBoundJoinContext context,
      int leaf, int side, int role) {
    if (context == null || program.nodeCount(leaf, side) != 1
        || program.rawColumn(leaf, side) < 0) return -1;
    int outer = context.localRole(program.scope(leaf, side, 0));
    return outer >= 0 && outer < role ? outer : -1;
  }

  private static int localRole(SqlBoundJoinContext context, int scope) {
    return context == null ? 0 : context.localRole(scope);
  }

  private static SqlComparison normalized(SqlComparison comparison, int targetSide) {
    if (targetSide == 0) return comparison;
    return switch (comparison) {
      case LESS_THAN -> SqlComparison.GREATER_THAN;
      case LESS_OR_EQUAL -> SqlComparison.GREATER_OR_EQUAL;
      case GREATER_THAN -> SqlComparison.LESS_THAN;
      case GREATER_OR_EQUAL -> SqlComparison.LESS_OR_EQUAL;
      default -> comparison;
    };
  }
}
