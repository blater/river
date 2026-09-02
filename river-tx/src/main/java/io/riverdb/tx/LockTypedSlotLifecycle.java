package io.riverdb.tx;

/** Free-list and reversible reservation state for one typed slot store. */
final class LockTypedSlotLifecycle {
  long freeHead = -1;
  long nextUnused;
  boolean exhausted;
  boolean reservationOutstanding;

  void rollback(
      LockTypedSlots owner, LockTypedSlotChunks chunks, LockSlotReservation reservation) {
    if (!reservation.admitted) return;
    requireReservation(owner, reservation);
    owner.clear(reservation.slot);
    owner.generation(reservation.slot, reservation.generation);
    owner.occupied(reservation.slot, false);
    if (reservation.popped) owner.freeLink(reservation.slot, reservation.poppedNext);
    owner.used(reservation.slot, -1);
    freeHead = reservation.freeHead;
    nextUnused = reservation.nextUnused;
    exhausted = reservation.exhausted;
    chunks.rollbackTo(reservation.chunks, owner);
    reservationOutstanding = false;
    reservation.reset();
  }

  void commit(LockTypedSlots owner, LockSlotReservation reservation) {
    if (!reservation.admitted) return;
    requireReservation(owner, reservation);
    reservationOutstanding = false;
    reservation.reset();
  }

  void free(LockTypedSlots owner, long slot) {
    long retainedGeneration = owner.generation(slot);
    owner.clear(slot);
    owner.generation(slot, retainedGeneration);
    owner.occupied(slot, false);
    owner.freeLink(slot, LockTypedSlots.encode(freeHead));
    freeHead = slot;
    owner.used(slot, -1);
  }

  private void requireReservation(LockTypedSlots owner, LockSlotReservation reservation) {
    if (!reservationOutstanding || reservation.owner != owner) {
      throw new IllegalStateException("typed-slot reservations must close on their owning store");
    }
  }
}
