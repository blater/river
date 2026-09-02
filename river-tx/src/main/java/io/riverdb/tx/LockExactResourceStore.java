package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;

/** Narrow typed arena for canonical exact resources. */
final class LockExactResourceStore extends LockTypedSlots {
  private static final int MODE_COUNT = LockMode.EXCLUSIVE.ordinal() + 1;

  static final class Chunk {
    final long[] generations = new long[256];
    final long[] free = new long[256];
    final long[] first = new long[256];
    final long[] second = new long[256];
    final long[] third = new long[256];
    final long[] fourth = new long[256];
    final long[] hashes = new long[256];
    final long[] tupleNamespaces = new long[256];
    final int[] tupleLowerLengths = new int[256];
    final int[] tupleUpperLengths = new int[256];
    final byte[][] tupleLowerBytes = new byte[256][];
    final byte[][] tupleUpperBytes = new byte[256][];
    final long[] ownerHeads = new long[256];
    final long[] waitHeads = new long[256];
    final long[] waitTails = new long[256];
    final long[] conversionHeads = new long[256];
    final long[] conversionTails = new long[256];
    final long[] modeWaitHeads = new long[MODE_COUNT * 256];
    final long[] modeWaitTails = new long[MODE_COUNT * 256];
    final long[] schedulerWorkNext = new long[256];
    final long[] ownerCounts = new long[256];
    final long[] sharedCounts = new long[256];
    final long[] updateCounts = new long[256];
    final byte[] scopes = new byte[256];
    final byte[] tupleFlags = new byte[256];
    final byte[] scheduled = new byte[256];
    final long[] occupied = new long[4];
    int used;
  }

  private final LockTupleResourceBytes tupleStorage;

  LockExactResourceStore(LockSegmentArena owner) {
    super(owner, 55_168);
    tupleStorage = new LockTupleResourceBytes(owner);
  }
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
    tupleStorage.clear(chunk, offset);
    chunk.first[offset] = chunk.second[offset] = chunk.third[offset] = chunk.fourth[offset] = 0;
    chunk.hashes[offset] = chunk.tupleNamespaces[offset] = 0;
    chunk.tupleLowerLengths[offset] = chunk.tupleUpperLengths[offset] = 0;
    chunk.ownerHeads[offset] = chunk.waitHeads[offset] = chunk.waitTails[offset] = 0;
    chunk.conversionHeads[offset] = chunk.conversionTails[offset] = 0;
    for (int mode = 0; mode < MODE_COUNT; mode++) {
      int modeOffset = modeOffset(mode, offset);
      chunk.modeWaitHeads[modeOffset] = chunk.modeWaitTails[modeOffset] = 0;
    }
    chunk.schedulerWorkNext[offset] = 0;
    chunk.ownerCounts[offset] = chunk.sharedCounts[offset] = chunk.updateCounts[offset] = 0;
    chunk.scopes[offset] = 0;
    chunk.tupleFlags[offset] = 0;
    chunk.scheduled[offset] = 0;
    chunk.free[offset] = 0;
  }

  static int modeOffset(int mode, int resourceOffset) {
    return mode * 256 + resourceOffset;
  }

  StatusCode prepareTuple(long slot, LockRequest request) {
    return tupleStorage.prepare(record(slot), offset(slot), request);
  }

  void initializeTuple(long slot, LockRequest request) {
    tupleStorage.initialize(record(slot), offset(slot), request);
  }

  static boolean tupleScope(byte scope) {
    return LockTupleResourceBytes.tupleScope(scope);
  }

  @Override
  void releaseChunk(Object released) {
    tupleStorage.release((Chunk) released);
  }
}
