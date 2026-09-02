package io.riverdb.tx;

/** Named compound ticket for the exact-lock admission protocol. */
final class LockExactAdmission {
  final LockSlotReservation resource = new LockSlotReservation();
  final LockSlotReservation transaction = new LockSlotReservation();
  final LockSlotReservation holding = new LockSlotReservation();
  final LockSlotReservation request = new LockSlotReservation();
  long resourceSlot;
  long transactionSlot;
  long holdingSlot;
  long requestSlot;
  boolean newResource;
  boolean newTransaction;
  boolean newHolding;
  int indexGrowth;

  void reset() {
    resource.reset();
    transaction.reset();
    holding.reset();
    request.reset();
    resourceSlot = transactionSlot = holdingSlot = requestSlot = -1;
    newResource = newTransaction = newHolding = false;
    indexGrowth = 0;
  }
}
