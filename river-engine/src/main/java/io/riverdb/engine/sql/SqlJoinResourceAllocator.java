package io.riverdb.engine.sql;

import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.ValueIndexLookupResult;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Injectable allocation boundary for retained JOIN role resources. */
class SqlJoinResourceAllocator {
  static final SqlJoinResourceAllocator STANDARD = new SqlJoinResourceAllocator();

  RelationalScanCursor cursor() { return new RelationalScanCursor(); }
  RelationalScanResult scan() { return new RelationalScanResult(); }
  ValueIndexLookupResult lookup() { return new ValueIndexLookupResult(); }
  HeapRowResult heapRow() { return new HeapRowResult(); }
  SqlJoinOuterRow outerRow() { return new SqlJoinOuterRow(this); }
  ByteBuffer rowBytes(int capacity) { return ByteBuffer.allocateDirect(capacity); }
  RelationalScanCursor[] cursors(int capacity) { return new RelationalScanCursor[capacity]; }
  RelationalScanResult[] scans(int capacity) { return new RelationalScanResult[capacity]; }
  ValueIndexLookupResult[] lookups(int capacity) {
    return new ValueIndexLookupResult[capacity];
  }
  HeapRowResult[] heapRows(int capacity) { return new HeapRowResult[capacity]; }
  SqlJoinOuterRow[] outerRows(int capacity) { return new SqlJoinOuterRow[capacity]; }
  long[] longs(int capacity) { return new long[capacity]; }
  boolean[] booleans(int capacity) { return new boolean[capacity]; }
}
