package io.riverdb.engine.table;

/** Package test seam for bounded pending-row chunk pressure. */
@FunctionalInterface
interface PendingRowChunkAllocator {
  PendingRowChunkAllocator HEAP = HeapPendingRowChunkAllocator.INSTANCE;

  byte[] allocate(int bytes);
}
