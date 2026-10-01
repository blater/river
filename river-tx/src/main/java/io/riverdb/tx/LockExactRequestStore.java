package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockWaitHandle;

/** Narrow typed arena for execution-lane requests and reserved grants. */
final class LockExactRequestStore extends LockTypedSlots {
  static final class Chunk extends LockTypedSlots.Chunk {
    final long[] resources = new long[256];
    final long[] transactions = new long[256];
    final long[] transactionRecordGenerations = new long[256];
    final long[] laneIds = new long[256];
    final long[] laneGenerations = new long[256];
    final long[] requestGenerations = new long[256];
    final long[] referenceGenerations = new long[256];
    final long[] deadlines = new long[256];
    final long[] blockedAtNanos = new long[256];
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
    final byte[] actuallyBlocked = new byte[256];
    final long[] conversions = new long[4];
  }

  LockExactRequestStore(LockSegmentArena arena) { super(arena, 45_440); }
  Chunk record(long slot) { return (Chunk) chunk(slot); }
  @Override Object newChunk(long index) { return new Chunk(); }
  @Override void clear(long slot) {
    Chunk chunk = record(slot);
    int offset = offset(slot);
    chunk.resources[offset] = chunk.transactions[offset] = 0;
    chunk.transactionRecordGenerations[offset] = 0;
    chunk.laneIds[offset] = chunk.laneGenerations[offset] = 0;
    chunk.requestGenerations[offset] = chunk.referenceGenerations[offset] = 0;
    chunk.deadlines[offset] = chunk.blockedAtNanos[offset] = chunk.holdings[offset] = 0;
    chunk.deadlinePresence[offset >>> 6] &= ~(1L << offset);
    chunk.conversions[offset >>> 6] &= ~(1L << offset);
    chunk.nextResource[offset] = chunk.previousResource[offset] = 0;
    chunk.nextConversion[offset] = chunk.previousConversion[offset] = 0;
    chunk.nextMode[offset] = chunk.previousMode[offset] = 0;
    chunk.nextTransaction[offset] = chunk.previousTransaction[offset] = 0;
    chunk.handles[offset] = null;
    chunk.modes[offset] = chunk.states[offset] = chunk.actuallyBlocked[offset] = 0;
    chunk.free[offset] = 0;
  }

  boolean conversion(long slot) {
    int offset = offset(slot);
    return (record(slot).conversions[offset >>> 6] & (1L << offset)) != 0;
  }
}
