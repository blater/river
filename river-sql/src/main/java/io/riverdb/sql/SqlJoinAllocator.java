package io.riverdb.sql;

/** Injectable allocation boundary for transactionally admitted JOIN syntax. */
class SqlJoinAllocator {
  static final SqlJoinAllocator STANDARD = new SqlJoinAllocator();

  SqlJoinChain chain() { return new SqlJoinChain(this); }
  SqlIdentifier[] identifiers(int capacity) { return new SqlIdentifier[capacity]; }
  int[] integers(int capacity) { return new int[capacity]; }
  SqlBooleanPredicateProgram[] predicates(int capacity) {
    return new SqlBooleanPredicateProgram[capacity];
  }
  SqlIdentifier identifier() { return new SqlIdentifier(); }
  SqlBooleanPredicateProgram predicate() { return new SqlBooleanPredicateProgram(); }
}
