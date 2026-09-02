package io.riverdb.sql;

/** Atomic geometric storage growth for join roles and stages. */
final class SqlJoinCapacity {
  private SqlJoinCapacity() { }

  static boolean ensureStage(SqlJoinChain chain, int roles) {
    if (!ensure(chain, roles)) return false;
    int stage = roles - 2;
    if (chain.onPrograms[stage] != null) return true;
    try {
      SqlBooleanPredicateProgram program = chain.allocator.predicate();
      chain.onPrograms[stage] = program;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  static boolean ensure(SqlJoinChain chain, int roles) {
    if (roles <= chain.tableNames.length) return true;
    int capacity = chain.tableNames.length;
    while (capacity < roles) capacity = Math.min(SqlJoinChain.MAXIMUM_JOIN_ROLES,
        capacity * 2);
    try {
      SqlIdentifier[] tables = identifiers(chain, chain.tableNames, capacity);
      SqlIdentifier[] aliases = identifiers(chain, chain.aliases, capacity);
      int[] sources = chain.allocator.integers(capacity);
      int stages = capacity - 1;
      int[] right = chain.allocator.integers(stages);
      int[] kinds = chain.allocator.integers(stages);
      SqlBooleanPredicateProgram[] programs = chain.allocator.predicates(stages);
      System.arraycopy(chain.sourceKinds, 0, sources, 0, chain.sourceKinds.length);
      System.arraycopy(chain.rightRoles, 0, right, 0, chain.rightRoles.length);
      System.arraycopy(chain.joinKinds, 0, kinds, 0, chain.joinKinds.length);
      System.arraycopy(chain.onPrograms, 0, programs, 0, chain.onPrograms.length);
      chain.tableNames = tables;
      chain.aliases = aliases;
      chain.sourceKinds = sources;
      chain.rightRoles = right;
      chain.joinKinds = kinds;
      chain.onPrograms = programs;
      return true;
    } catch (OutOfMemoryError error) {
      return false;
    }
  }

  private static SqlIdentifier[] identifiers(
      SqlJoinChain chain, SqlIdentifier[] source, int capacity) {
    SqlIdentifier[] grown = chain.allocator.identifiers(capacity);
    System.arraycopy(source, 0, grown, 0, source.length);
    for (int index = source.length; index < capacity; index++) {
      grown[index] = chain.allocator.identifier();
    }
    return grown;
  }
}
