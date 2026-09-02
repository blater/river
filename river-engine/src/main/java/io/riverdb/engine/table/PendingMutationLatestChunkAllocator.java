package io.riverdb.engine.table;

/** Injectable cold-path allocator for deterministic resource-pressure testing. */
@FunctionalInterface
interface PendingMutationLatestChunkAllocator {
  PendingMutationLatestChunkAllocator HEAP = PendingMutationLatestIndexChunk::new;

  PendingMutationLatestIndexChunk allocate(int entries);
}
