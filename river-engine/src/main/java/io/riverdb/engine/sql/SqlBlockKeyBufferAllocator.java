package io.riverdb.engine.sql;

import java.nio.ByteBuffer;

/** Allocation boundary for retained block sort-key storage. */
class SqlBlockKeyBufferAllocator {
  static final SqlBlockKeyBufferAllocator DIRECT = new SqlBlockKeyBufferAllocator();

  ByteBuffer allocate(int capacity) {
    return ByteBuffer.allocateDirect(capacity);
  }
}
