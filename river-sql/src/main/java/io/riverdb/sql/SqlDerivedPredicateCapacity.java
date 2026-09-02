package io.riverdb.sql;

import java.util.Arrays;

/** Geometric scratch maps for derived-predicate relocation. */
final class SqlDerivedPredicateCapacity {
  private SqlDerivedPredicateCapacity() {
  }

  static boolean ensure(
      SqlDerivedPredicateCompiler compiler, int nodes, int leaves) {
    int nodeCapacity = capacity(
        compiler.booleanMap.length, nodes,
        SqlBooleanPredicateProgram.MAXIMUM_BOOLEAN_NODES);
    int leafCapacity = capacity(
        compiler.leafMap.length, leaves,
        SqlBooleanPredicateProgram.MAXIMUM_LEAVES);
    if (nodeCapacity < 0 || leafCapacity < 0) return false;
    try {
      int[] nodeMap = nodeCapacity == compiler.booleanMap.length
          ? compiler.booleanMap : Arrays.copyOf(compiler.booleanMap, nodeCapacity);
      int[] leafMap = leafCapacity == compiler.leafMap.length
          ? compiler.leafMap : Arrays.copyOf(compiler.leafMap, leafCapacity);
      compiler.booleanMap = nodeMap;
      compiler.leafMap = leafMap;
      return true;
    } catch (OutOfMemoryError exhausted) {
      return false;
    }
  }

  private static int capacity(int current, int required, int maximum) {
    if (required > maximum) return -1;
    while (current < required) current = Math.min(maximum, current * 2);
    return current;
  }
}
