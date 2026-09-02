package io.riverdb.engine.runtime.materialized;

/** Compact bounded owner reservation and terminal-failure index. */
final class SqlMaterializedOwnerIndex {
  private final long[] owners;
  private final int[] reservations;
  private final boolean[] failed;
  private final int mask;

  SqlMaterializedOwnerIndex(int capacity) {
    owners = new long[capacity];
    reservations = new int[capacity];
    failed = new boolean[capacity];
    mask = capacity - 1;
  }

  int reservation(long owner) {
    int slot = find(owner);
    return slot < 0 ? 0 : reservations[slot];
  }

  boolean failed(long owner) {
    int slot = find(owner);
    return slot >= 0 && failed[slot];
  }

  boolean reserve(long owner, int count) {
    int slot = locate(owner);
    if (slot < 0 || count <= 0 || reservations[slot] > Integer.MAX_VALUE - count) {
      return false;
    }
    reservations[slot] += count;
    return true;
  }

  boolean release(long owner, int count) {
    int slot = find(owner);
    if (slot < 0 || count <= 0 || reservations[slot] < count) return false;
    reservations[slot] -= count;
    removeIfEmpty(slot);
    return true;
  }

  boolean fail(long owner) {
    int slot = locate(owner);
    if (slot < 0) return false;
    failed[slot] = true;
    return true;
  }

  void clear(long owner) {
    int slot = find(owner);
    if (slot < 0) return;
    reservations[slot] = 0;
    failed[slot] = false;
    remove(slot);
  }

  private int find(long owner) {
    int slot = hash(owner) & mask;
    for (int probes = 0; probes < owners.length; probes++) {
      long retained = owners[slot];
      if (retained == 0) return -1;
      if (retained == owner) return slot;
      slot = slot + 1 & mask;
    }
    return -1;
  }

  private int locate(long owner) {
    int slot = hash(owner) & mask;
    for (int probes = 0; probes < owners.length; probes++) {
      if (owners[slot] == 0 || owners[slot] == owner) {
        owners[slot] = owner;
        return slot;
      }
      slot = slot + 1 & mask;
    }
    return -1;
  }

  private void removeIfEmpty(int slot) {
    if (reservations[slot] == 0 && !failed[slot]) remove(slot);
  }

  private void remove(int slot) {
    owners[slot] = 0;
    int next = slot + 1 & mask;
    while (owners[next] != 0) {
      long displacedOwner = owners[next];
      int displacedReservation = reservations[next];
      boolean displacedFailure = failed[next];
      owners[next] = 0;
      reservations[next] = 0;
      failed[next] = false;
      int target = locate(displacedOwner);
      reservations[target] = displacedReservation;
      failed[target] = displacedFailure;
      next = next + 1 & mask;
    }
  }

  private static int hash(long owner) {
    long mixed = owner ^ owner >>> 33;
    mixed *= 0xff51afd7ed558ccdL;
    mixed ^= mixed >>> 33;
    return (int) mixed;
  }
}
