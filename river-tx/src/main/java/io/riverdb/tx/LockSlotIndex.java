package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Collision-safe primitive linear hash with one bounded bucket split per admission. */
final class LockSlotIndex {
  private static final long MAXIMUM_LOAD = 2;
  private final LockLongStore heads;
  private final LockLongStore next;
  private final LockLongStore previous;
  private final LockLongStore hashes;
  private final long seed;
  private long base = 2;
  private long split;
  private long entries;
  private long reservedSlot;
  private long reservedBucket;
  private long reservedSplitBucket;
  private int growth;
  private boolean splitPending;

  LockSlotIndex(LockSegmentArena arena, long providerSeed) {
    heads = new LockLongStore(arena);
    next = new LockLongStore(arena);
    previous = new LockLongStore(arena);
    hashes = new LockLongStore(arena);
    seed = mix(providerSeed ^ 0x9e3779b97f4a7c15L);
  }

  StatusCode reserve(long slot, long hash) {
    if (slot < 0 || entries == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
    growth = 0;
    splitPending = false;
    reservedSlot = slot;
    reservedBucket = bucket(hash);
    StatusCode status = reserveStore(next, slot, 1);
    if (status.isOk()) status = reserveStore(previous, slot, 2);
    if (status.isOk()) status = reserveStore(hashes, slot, 4);
    if (status.isOk()) status = reserveStore(heads, reservedBucket, 8);
    if (!status.isOk()) {
      rollbackReservation();
      return status;
    }
    long buckets;
    long loadLimit;
    try {
      buckets = Math.addExact(base, split);
      loadLimit = Math.multiplyExact(buckets, MAXIMUM_LOAD);
    } catch (ArithmeticException overflow) {
      rollbackReservation();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (entries < loadLimit) return StatusCode.OK;
    if (base > Long.MAX_VALUE >>> 1) {
      rollbackReservation();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    reservedSplitBucket = buckets;
    status = reserveStore(heads, reservedSplitBucket, 16);
    if (!status.isOk()) {
      rollbackReservation();
      return status;
    }
    splitPending = true;
    return StatusCode.OK;
  }

  void add(long slot, long hash) {
    commitReservation();
    long bucket = bucket(hash);
    long first = heads.get(bucket);
    next.set(slot, first);
    previous.set(slot, 0);
    hashes.set(slot, hash);
    if (first != 0) previous.set(decode(first), encode(slot));
    heads.set(bucket, encode(slot));
    entries++;
  }

  void remove(long slot, long hash) {
    long prior = previous.get(slot);
    long following = next.get(slot);
    if (prior == 0) heads.set(bucket(hash), following);
    else next.set(decode(prior), following);
    if (following != 0) previous.set(decode(following), prior);
    next.set(slot, 0);
    previous.set(slot, 0);
    hashes.set(slot, 0);
    entries--;
  }

  long first(long hash) { return decode(heads.get(bucket(hash))); }
  long next(long slot) { return decode(next.get(slot)); }
  long bucketForTest(long hash) { return bucket(hash); }

  static long hash(long first, long second, long third, long fourth, long fifth) {
    long value = mix(first) ^ Long.rotateLeft(mix(second), 11);
    value ^= Long.rotateLeft(mix(third), 23) ^ Long.rotateLeft(mix(fourth), 37);
    return value ^ Long.rotateLeft(mix(fifth), 49);
  }
  static long hash(long value) { return mix(value); }

  void rollbackReservation() {
    if ((growth & 16) != 0) heads.rollback(reservedSplitBucket);
    if ((growth & 8) != 0) heads.rollback(reservedBucket);
    if ((growth & 4) != 0) hashes.rollback(reservedSlot);
    if ((growth & 2) != 0) previous.rollback(reservedSlot);
    if ((growth & 1) != 0) next.rollback(reservedSlot);
    growth = 0;
    splitPending = false;
  }

  void commitReservation() {
    if (splitPending) splitBucket();
    growth = 0;
    splitPending = false;
  }

  private StatusCode reserveStore(LockLongStore store, long ordinal, int bit) {
    boolean allocated = store.allocated(ordinal);
    StatusCode status = store.reserve(ordinal);
    if (status.isOk() && !allocated) growth |= bit;
    return status;
  }

  private long bucket(long hash) {
    hash = mix(hash ^ seed);
    long bucket = hash & (base - 1);
    return bucket < split ? hash & ((base << 1) - 1) : bucket;
  }

  private void splitBucket() {
    long oldBucket = split;
    long newBucket = base + split;
    long encoded = heads.get(oldBucket);
    heads.set(oldBucket, 0);
    heads.set(newBucket, 0);
    while (encoded != 0) {
      long slot = decode(encoded);
      long following = next.get(slot);
      long bucket = mix(hashes.get(slot) ^ seed) & ((base << 1) - 1);
      relinkAtHead(slot, bucket);
      encoded = following;
    }
    split++;
    if (split == base) {
      base <<= 1;
      split = 0;
    }
  }

  private void relinkAtHead(long slot, long bucket) {
    long first = heads.get(bucket);
    next.set(slot, first);
    previous.set(slot, 0);
    if (first != 0) previous.set(decode(first), encode(slot));
    heads.set(bucket, encode(slot));
  }

  private static long encode(long slot) { return slot ^ Long.MIN_VALUE; }
  private static long decode(long value) { return value == 0 ? -1 : value ^ Long.MIN_VALUE; }

  private static long mix(long value) {
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdL;
    value ^= value >>> 33;
    value *= 0xc4ceb9fe1a85ec53L;
    return value ^ value >>> 33;
  }
}
