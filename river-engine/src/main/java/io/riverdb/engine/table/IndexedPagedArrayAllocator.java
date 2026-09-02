package io.riverdb.engine.table;

/** Allocation boundary for sparse page-metadata chunks. */
interface IndexedPagedArrayAllocator {
  byte[] allocateBytes(int size);
  int[] allocateInts(int size);
  long[] allocateLongs(int size);
}
