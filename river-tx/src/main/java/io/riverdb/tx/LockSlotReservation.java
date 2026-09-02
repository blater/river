package io.riverdb.tx;

/** Reusable provenance for one typed-slot admission. */
final class LockSlotReservation {
  LockTypedSlots owner;
  long slot = -1;
  long freeHead;
  long nextUnused;
  long chunks;
  long generation;
  long poppedNext;
  boolean exhausted;
  boolean popped;
  boolean admitted;

  void reset() {
    owner = null;
    slot = -1;
    admitted = false;
    popped = false;
  }
}
