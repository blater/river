package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.storage.heap.HeapRowResult;

/** Stable physical-row reader facade over dynamic block-row admission. */
final class SqlBlockPhysicalRowReader {
  private final SqlBlockPhysicalRowDecoding decoding;

  SqlBlockPhysicalRowReader() { this(SqlRetainedArrayAllocator.STANDARD); }

  SqlBlockPhysicalRowReader(SqlRetainedArrayAllocator allocator) {
    decoding = new SqlBlockPhysicalRowDecoding(allocator);
  }

  StatusCode prepare(TableDefinition table, SqlBlockRow destination) {
    return decoding.prepare(table, destination);
  }

  StatusCode read(
      long primaryKey,
      HeapRowResult source,
      TableDefinition table,
      SqlBlockRow destination) {
    return decoding.read(primaryKey, source, table, destination);
  }

  void reset() { decoding.reset(); }
}
