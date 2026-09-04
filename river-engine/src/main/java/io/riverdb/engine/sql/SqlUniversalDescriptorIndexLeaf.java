package io.riverdb.engine.sql;

import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;
import io.riverdb.sql.SqlScalarExpression;

/** Matches one universal predicate leaf to one role-local index part. */
final class SqlUniversalDescriptorIndexLeaf {
  private SqlUniversalDescriptorIndexLeaf() { }

  static boolean find(
      SqlBoundBooleanPredicateProgram program, SqlBoundJoinContext context,
      int queryBlock, int leaf, int role, int column, SqlComparison comparison,
      SqlBlockColumnLineage lineage, SqlUniversalDescriptorIndexBinding result) {
    if (between(
        program, context, queryBlock, leaf, role, column, comparison, lineage, result)) {
      return true;
    }
    if (program.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_COMPARISON) return false;
    int side = targetSide(program, context, queryBlock, leaf, role, column, lineage);
    if (side < 0
        || normalized(program.comparison(leaf), side) != comparison) return false;
    int valueSide = side == 0 ? 1 : 0;
    if (literal(program, leaf, valueSide)) {
      if (result != null) result.literal(program, leaf, valueSide);
      return true;
    }
    int scope = outerScope(program, context, queryBlock, leaf, valueSide, role);
    if (scope == Integer.MIN_VALUE) return false;
    if (result != null) result.outer(
        queryBlock >= 0 && SqlNestedRowProvider.block(scope) != queryBlock
            ? SqlNestedRowProvider.block(scope) : -1,
        queryBlock >= 0 ? SqlNestedRowProvider.role(scope) : scope,
        program.rawColumn(leaf, valueSide));
    return true;
  }

  private static boolean between(
      SqlBoundBooleanPredicateProgram program, SqlBoundJoinContext context,
      int queryBlock, int leaf, int role, int column, SqlComparison comparison,
      SqlBlockColumnLineage lineage, SqlUniversalDescriptorIndexBinding result) {
    if (program.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_BETWEEN
        || program.negated(leaf)
        || targetSide(program, context, queryBlock, leaf, role, column, lineage) != 0) {
      return false;
    }
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
      int queryBlock, int leaf, int role, int column, SqlBlockColumnLineage lineage) {
    for (int side = 0; side < 2; side++) {
      int raw = program.rawColumn(leaf, side);
      int mapped = lineage == null ? raw : lineage.baseColumn(raw);
      if (program.nodeCount(leaf, side) == 1 && mapped == column
          && localRole(context, queryBlock, program.scope(leaf, side, 0)) == role) return side;
    }
    return -1;
  }

  private static boolean literal(
      SqlBoundBooleanPredicateProgram program, int leaf, int side) {
    return program.nodeCount(leaf, side) == 1
        && program.operator(leaf, side, 0) == SqlScalarExpression.LITERAL;
  }

  private static int outerScope(
      SqlBoundBooleanPredicateProgram program, SqlBoundJoinContext context,
      int queryBlock, int leaf, int side, int role) {
    if (program.nodeCount(leaf, side) != 1 || program.rawColumn(leaf, side) < 0) {
      return Integer.MIN_VALUE;
    }
    int scope = program.scope(leaf, side, 0);
    if (queryBlock >= 0) {
      int block = SqlNestedRowProvider.block(scope);
      int sourceRole = SqlNestedRowProvider.role(scope);
      return block < queryBlock || block == queryBlock && sourceRole < role
          ? scope : Integer.MIN_VALUE;
    }
    if (context == null) return Integer.MIN_VALUE;
    int outer = context.localRole(scope);
    return outer >= 0 && outer < role ? outer : Integer.MIN_VALUE;
  }

  private static int localRole(
      SqlBoundJoinContext context, int queryBlock, int scope) {
    if (queryBlock >= 0) {
      return SqlNestedRowProvider.block(scope) == queryBlock
          ? SqlNestedRowProvider.role(scope) : -1;
    }
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
