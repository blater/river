package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;

/** Atomic typed-slot reservation before a lock operation publishes state. */
final class LockTypedSlotAdmission {
  private LockTypedSlotAdmission() {}

  static StatusCode reserve(
      LockTypedSlots owner,
      LockTypedSlotLifecycle lifecycle,
      LockTypedSlotChunks chunks,
      LockSlotReservation reservation) {
    if (reservation == null || reservation.owner != null
        || lifecycle.reservationOutstanding) return StatusCode.CONFLICT;
    long slot = lifecycle.freeHead >= 0
        ? lifecycle.freeHead : lifecycle.exhausted ? -1 : lifecycle.nextUnused;
    if (slot < 0) return StatusCode.RESOURCE_EXHAUSTED;
    save(lifecycle, chunks, reservation);
    StatusCode status = chunks.reserve(LockTypedSlotChunks.index(slot), owner);
    if (!status.isOk()) return status;
    reservation.slot = slot;
    reservation.generation = owner.generation(slot);
    reservation.popped = lifecycle.freeHead >= 0;
    reservation.poppedNext = reservation.popped ? owner.freeLink(slot) : 0;
    long nextGeneration = reservation.generation + 1;
    if (nextGeneration <= 0) {
      chunks.rollbackTo(reservation.chunks, owner);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    advance(lifecycle, reservation);
    owner.clear(slot);
    owner.generation(slot, nextGeneration);
    owner.occupied(slot, true);
    owner.used(slot, 1);
    reservation.admitted = true;
    reservation.owner = owner;
    lifecycle.reservationOutstanding = true;
    return StatusCode.OK;
  }

  private static void save(
      LockTypedSlotLifecycle lifecycle,
      LockTypedSlotChunks chunks,
      LockSlotReservation reservation) {
    reservation.freeHead = lifecycle.freeHead;
    reservation.nextUnused = lifecycle.nextUnused;
    reservation.chunks = chunks.allocated();
    reservation.exhausted = lifecycle.exhausted;
  }

  private static void advance(
      LockTypedSlotLifecycle lifecycle, LockSlotReservation reservation) {
    if (reservation.popped) {
      lifecycle.freeHead = LockTypedSlots.decode(reservation.poppedNext);
    } else if (lifecycle.nextUnused == Long.MAX_VALUE) {
      lifecycle.exhausted = true;
    } else {
      lifecycle.nextUnused++;
    }
  }
}
