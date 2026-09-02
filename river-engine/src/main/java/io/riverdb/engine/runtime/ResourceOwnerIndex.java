package io.riverdb.engine.runtime;

/** Fixed primitive open-address index for one live or waiting lease per owner generation. */
final class ResourceOwnerIndex {
  private static final int MAXIMUM_TABLE_LENGTH = 1 << 29;

  private final long[] ownerIds;
  private final long[] ownerGenerations;
  private final byte[] states;
  private final int mask;
  private int size;

  ResourceOwnerIndex(int maximumOwners) {
    int length = tableLength(maximumOwners);
    ownerIds = new long[length];
    ownerGenerations = new long[length];
    states = new byte[length];
    mask = length - 1;
  }

  static boolean supports(int maximumOwners) {
    return maximumOwners > 0 && maximumOwners <= MAXIMUM_TABLE_LENGTH / 2;
  }

  boolean contains(long ownerId, long ownerGeneration) {
    int slot = slot(ownerId, ownerGeneration);
    while (states[slot] != 0) {
      if (ownerIds[slot] == ownerId
          && ownerGenerations[slot] == ownerGeneration) return true;
      slot = (slot + 1) & mask;
    }
    return false;
  }

  boolean add(long ownerId, long ownerGeneration) {
    int slot = slot(ownerId, ownerGeneration);
    while (states[slot] != 0) {
      if (ownerIds[slot] == ownerId
          && ownerGenerations[slot] == ownerGeneration) return false;
      slot = (slot + 1) & mask;
    }
    ownerIds[slot] = ownerId;
    ownerGenerations[slot] = ownerGeneration;
    states[slot] = 1;
    size++;
    return true;
  }

  boolean remove(long ownerId, long ownerGeneration) {
    int slot = slot(ownerId, ownerGeneration);
    while (states[slot] != 0) {
      if (ownerIds[slot] == ownerId
          && ownerGenerations[slot] == ownerGeneration) {
        clear(slot);
        size--;
        int displaced = (slot + 1) & mask;
        while (states[displaced] != 0) {
          long displacedOwner = ownerIds[displaced];
          long displacedGeneration = ownerGenerations[displaced];
          clear(displaced);
          size--;
          add(displacedOwner, displacedGeneration);
          displaced = (displaced + 1) & mask;
        }
        return true;
      }
      slot = (slot + 1) & mask;
    }
    return false;
  }

  int size() { return size; }

  private void clear(int slot) {
    states[slot] = 0;
    ownerIds[slot] = ownerGenerations[slot] = 0;
  }

  private int slot(long ownerId, long ownerGeneration) {
    long value = ownerId ^ Long.rotateLeft(ownerGeneration, 29);
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdl;
    value ^= value >>> 33;
    return (int) value & mask;
  }

  private static int tableLength(int maximumOwners) {
    int required = maximumOwners << 1;
    int highest = Integer.highestOneBit(required - 1);
    return highest >= MAXIMUM_TABLE_LENGTH ? MAXIMUM_TABLE_LENGTH : highest << 1;
  }
}
