package io.riverdb.engine.table;

/** Production heap provider for retained pending-row chunks. */
final class HeapPendingRowChunkAllocator implements PendingRowChunkAllocator {
  static final HeapPendingRowChunkAllocator INSTANCE = new HeapPendingRowChunkAllocator();

  private HeapPendingRowChunkAllocator() {}

  @Override
  public byte[] allocate(int bytes) {
    return new byte[bytes];
  }
}
