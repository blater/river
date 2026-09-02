package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableStatistics;

/** Injectable allocation boundary for retained bound JOIN context state. */
class SqlJoinContextAllocator {
  static final SqlJoinContextAllocator STANDARD = new SqlJoinContextAllocator();

  TableDefinition[] tables(int capacity) { return new TableDefinition[capacity]; }
  TableStatistics[] statistics(int capacity) { return new TableStatistics[capacity]; }
  SqlBoundBooleanPredicateProgram[] predicates(int capacity) {
    return new SqlBoundBooleanPredicateProgram[capacity];
  }
  boolean[] booleans(int capacity) { return new boolean[capacity]; }
  byte[] bytes(int capacity) { return new byte[capacity]; }
  int[] integers(int capacity) { return new int[capacity]; }
  long[] longs(int capacity) { return new long[capacity]; }
  TableDefinition table() { return new TableDefinition(); }
  TableStatistics statistic() { return new TableStatistics(); }
  SqlBoundBooleanPredicateProgram predicate() {
    return new SqlBoundBooleanPredicateProgram();
  }
}
