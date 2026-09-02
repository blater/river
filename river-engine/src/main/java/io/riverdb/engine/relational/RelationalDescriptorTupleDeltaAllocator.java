package io.riverdb.engine.relational;

import io.riverdb.engine.schema.KeyDescriptor;
import java.nio.ByteBuffer;

/** Injectable allocation boundary for retained descriptor tuple-delta planning storage. */
class RelationalDescriptorTupleDeltaAllocator {
  static final RelationalDescriptorTupleDeltaAllocator STANDARD =
      new RelationalDescriptorTupleDeltaAllocator();

  KeyDescriptor[] keys(int capacity) { return new KeyDescriptor[capacity]; }
  int[] integers(int capacity) { return new int[capacity]; }
  byte[] bytes(int capacity) { return new byte[capacity]; }
  ByteBuffer view(byte[] bytes) { return ByteBuffer.wrap(bytes); }
}
