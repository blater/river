package io.riverdb.engine.sql;

import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Provides owned active ancestor rows to scoped nested scalar programs. */
interface SqlNestedRowProvider {
  long key(int block);
  HeapRowResult row(int block);
  TableDefinition table(int block);
}
