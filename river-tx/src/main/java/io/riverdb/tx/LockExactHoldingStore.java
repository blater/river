package io.riverdb.tx;

/** Narrow typed arena for canonical transaction/resource holdings. */
final class LockExactHoldingStore extends LockTypedSlots {
  static final class Chunk extends LockTypedSlots.Chunk {
    final long[] resources = new long[256];
    final long[] transactions = new long[256];
    final long[] transactionRecordGenerations = new long[256];
    final long[] nextResource = new long[256];
    final long[] previousResource = new long[256];
    final long[] nextTransaction = new long[256];
    final long[] previousTransaction = new long[256];
    final long[] references = new long[256];
    final long[] capabilities = new long[256];
    final byte[] modes = new byte[256];
    final byte[] active = new byte[256];
    final byte[] retained = new byte[256];
  }

  LockExactHoldingStore(LockSegmentArena arena) { super(arena, 25_856); }
  Chunk record(long slot) { return (Chunk) chunk(slot); }
  @Override Object newChunk(long index) { return new Chunk(); }
  @Override void clear(long slot) {
    Chunk chunk = record(slot);
    int offset = offset(slot);
    chunk.resources[offset] = chunk.transactions[offset] = 0;
    chunk.transactionRecordGenerations[offset] = 0;
    chunk.nextResource[offset] = chunk.previousResource[offset] = 0;
    chunk.nextTransaction[offset] = chunk.previousTransaction[offset] = 0;
    chunk.references[offset] = chunk.capabilities[offset] = 0;
    chunk.modes[offset] = chunk.active[offset] = chunk.retained[offset] = 0;
    chunk.free[offset] = 0;
  }
}
