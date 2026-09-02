package io.riverdb.engine.relational;

import java.nio.ByteBuffer;

/** Injectable allocation boundary for retained descriptor INSERT receipt storage. */
class RelationalDescriptorBatchAllocator {
  static final RelationalDescriptorBatchAllocator STANDARD =
      new RelationalDescriptorBatchAllocator();

  byte[] bytes(int capacity) { return new byte[capacity]; }
  int[] integers(int capacity) { return new int[capacity]; }
  long[] longs(int capacity) { return new long[capacity]; }
  ByteBuffer view(byte[] bytes) { return ByteBuffer.wrap(bytes); }
}
