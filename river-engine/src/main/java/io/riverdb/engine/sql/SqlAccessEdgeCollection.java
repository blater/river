package io.riverdb.engine.sql;

import io.riverdb.sql.SqlBooleanPredicateProgram;
import io.riverdb.sql.SqlComparison;

/** Collects root-scope Boolean leaves into bounded access-edge arrays. */
final class SqlAccessEdgeCollection {
  private SqlAccessEdgeCollection() { }

  static void collect(SqlAccessEdgeSelector target, SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram programs, int node) {
    int operator = programs.booleanOperator(node);
    if (operator == SqlBooleanPredicateProgram.BOOLEAN_AND) {
      collect(target, source, programs, programs.booleanLeft(node));
      collect(target, source, programs, programs.booleanRight(node));
    } else if (operator == SqlBooleanPredicateProgram.BOOLEAN_LEAF) {
      collectLeaf(target, source, programs, programs.booleanLeft(node));
    }
  }

  static void collectLeaf(SqlAccessEdgeSelector target, SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram programs, int leaf) {
    if (target.count >= SqlAccessEdgeSelector.MAXIMUM_EDGES) return;
    if (source.leafTest(leaf) == SqlBooleanPredicateProgram.TEST_BETWEEN) {
      collectBetween(target, source, programs, leaf); return;
    }
    if (source.leafTest(leaf) != SqlBooleanPredicateProgram.TEST_COMPARISON) return;
    int left = SqlBooleanPredicateProgram.PROGRAM_LEFT, right = SqlBooleanPredicateProgram.PROGRAM_RIGHT;
    int column = programs.rawColumn(leaf, left), literalProgram = right;
    SqlComparison comparison = source.comparison(leaf);
    if (column < 0) { column = programs.rawColumn(leaf, right); literalProgram = left; comparison = SqlAccessEdgeSelector.reverse(comparison); }
    if (column >= 0 && !target.rootScope(programs.scope(leaf, literalProgram == right ? left : right, 0))) return;
    if (column < 0 || !target.literal(source, leaf, literalProgram)) return;
    int index = target.count++;
    target.columns[index] = column; target.leaves[index] = leaf; target.comparisons[index] = comparison;
    target.values[index] = source.programOperand(leaf, literalProgram, 0);
    target.descriptors[index] = programs.resultDescriptor(leaf, literalProgram);
  }

  private static void collectBetween(SqlAccessEdgeSelector target, SqlBooleanPredicateProgram source,
      SqlBoundBooleanPredicateProgram programs, int leaf) {
    int left = SqlBooleanPredicateProgram.PROGRAM_LEFT, lower = SqlBooleanPredicateProgram.PROGRAM_LOWER;
    int upper = SqlBooleanPredicateProgram.PROGRAM_UPPER, column = programs.rawColumn(leaf, left);
    if (source.leafNegated(leaf) || column < 0 || !target.rootScope(programs.scope(leaf, left, 0))
        || !target.literal(source, leaf, lower) || !target.literal(source, leaf, upper)) return;
    int index = target.count++;
    target.columns[index] = column; target.leaves[index] = leaf;
    target.comparisons[index] = SqlComparison.HALF_OPEN_RANGE;
    target.values[index] = source.programOperand(leaf, lower, 0);
    target.descriptors[index] = programs.resultDescriptor(leaf, lower);
    target.upperValues[index] = source.programOperand(leaf, upper, 0);
    target.upperDescriptors[index] = programs.resultDescriptor(leaf, upper);
  }
}
