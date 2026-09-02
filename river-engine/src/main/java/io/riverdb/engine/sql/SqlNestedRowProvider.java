package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Provides owned active ancestor rows to scoped nested scalar programs. */
interface SqlNestedRowProvider {
  int BLOCK_STRIDE = io.riverdb.sql.SqlQuery.MAXIMUM_QUERY_BLOCKS;

  static int scope(int block, int role) {
    return role * BLOCK_STRIDE + block;
  }

  static int block(int scope) {
    return scope % BLOCK_STRIDE;
  }

  static int role(int scope) {
    return scope / BLOCK_STRIDE;
  }

  long key(int block, int role);
  HeapRowResult row(int block, int role);
  TableDefinition table(int block, int role);
  default SqlBlockRow blockRow(int block, int role) { return null; }
}
