package io.riverdb.engine.relational;

/** Injectable primitive allocation boundary for startup name-map validation. */
interface RelationalDescriptorNameArrayAllocator {
  RelationalDescriptorNameArrayAllocator STANDARD = size -> new long[size];

  long[] longs(int size);
}
