package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockWaitHandle;

/** Narrow typed arena for execution-lane requests and reserved grants. */
final class LockExactRequestStore extends LockTypedSlots {
  static final class Chunk {
    final long[] generations = new long[256];
    final long[] free = new long[256];
    final long[] resources = new long[256];
    final long[] transactions = new long[256];
    final long[] transactionRecordGenerations = new long[256];
    final long[] laneIds = new long[256];
    final long[] laneGenerations = new long[256];
    final long[] requestGenerations = new long[256];
    final long[] referenceGenerations = new long[256];
    final long[] deadlines = new long[256];
    final long[] deadlinePresence = new long[4];
    final long[] holdings = new long[256];
    final long[] nextResource = new long[256];
    final long[] previousResource = new long[256];
    final long[] nextConversion = new long[256];
    final long[] previousConversion = new long[256];
    final long[] nextMode = new long[256];
    final long[] previousMode = new long[256];
    final long[] nextTransaction = new long[256];
    final long[] previousTransaction = new long[256];
    final LockWaitHandle[] handles = new LockWaitHandle[256];
    final byte[] modes = new byte[256];
    final byte[] states = new byte[256];
    final long[] occupied = new long[4];
    final long[] conversions = new long[4];
    int used;
  }

  LockExactRequestStore(LockSegmentArena arena) { super(arena, 43_136); }
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
    chunk.laneIds[offset] = chunk.laneGenerations[offset] = 0;
    chunk.requestGenerations[offset] = chunk.referenceGenerations[offset] = 0;
    chunk.deadlines[offset] = chunk.holdings[offset] = 0;
    chunk.deadlinePresence[offset >>> 6] &= ~(1L << offset);
    chunk.conversions[offset >>> 6] &= ~(1L << offset);
    chunk.nextResource[offset] = chunk.previousResource[offset] = 0;
    chunk.nextConversion[offset] = chunk.previousConversion[offset] = 0;
    chunk.nextMode[offset] = chunk.previousMode[offset] = 0;
    chunk.nextTransaction[offset] = chunk.previousTransaction[offset] = 0;
    chunk.handles[offset] = null;
    chunk.modes[offset] = chunk.states[offset] = 0;
    chunk.free[offset] = 0;
  }

  boolean conversion(long slot) {
    int offset = offset(slot);
    return (record(slot).conversions[offset >>> 6] & (1L << offset)) != 0;
  }
}
