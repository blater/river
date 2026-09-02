package io.riverdb.tx;

/** Narrow typed arena for canonical transaction/resource holdings. */
final class LockExactHoldingStore extends LockTypedSlots {
  static final class Chunk {
    final long[] generations = new long[256];
    final long[] free = new long[256];
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
    final long[] occupied = new long[4];
    int used;
  }

  LockExactHoldingStore(LockSegmentArena arena) { super(arena, 25_856); }
  Chunk record(long slot) { return (Chunk) chunk(slot); }
  @Override Object newChunk(long index) { return new Chunk(); }
  @Override long generation(long slot) { return record(slot).generations[offset(slot)]; }
  @Override void generation(long slot, long value) { record(slot).generations[offset(slot)] = value; }
  @Override long freeLink(long slot) { return record(slot).free[offset(slot)]; }
  @Override void freeLink(long slot, long value) { record(slot).free[offset(slot)] = value; }
  @Override void used(long slot, int delta) { record(slot).used += delta; }
  @Override void occupied(long slot, boolean value) {
    Chunk chunk = record(slot);
    int offset = offset(slot);
    if (value) chunk.occupied[offset >>> 6] |= 1L << offset;
    else chunk.occupied[offset >>> 6] &= ~(1L << offset);
  }
  @Override boolean occupied(long slot) {
    int offset = offset(slot);
    return (record(slot).occupied[offset >>> 6] & (1L << offset)) != 0;
  }
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
