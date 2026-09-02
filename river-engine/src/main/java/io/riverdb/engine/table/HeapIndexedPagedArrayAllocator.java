package io.riverdb.engine.table;

/** Production sparse page-metadata chunk allocator. */
final class HeapIndexedPagedArrayAllocator implements IndexedPagedArrayAllocator {
  static final HeapIndexedPagedArrayAllocator INSTANCE = new HeapIndexedPagedArrayAllocator();

  private HeapIndexedPagedArrayAllocator() {}

  @Override public byte[] allocateBytes(int size) { return new byte[size]; }
  @Override public int[] allocateInts(int size) { return new int[size]; }
  @Override public long[] allocateLongs(int size) { return new long[size]; }
}
