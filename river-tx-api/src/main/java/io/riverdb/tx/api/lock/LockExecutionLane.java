package io.riverdb.tx.api.lock;

import io.riverdb.base.error.StatusCode;

/** Caller-owned identity for one causal execution lane. */
public final class LockExecutionLane {
  private Object providerAuthority;
  private long providerGeneration;
  private long transactionId;
  private long transactionGeneration;
  private long laneId;
  private long laneGeneration;
  private long requestGeneration;
  private long requestSlot = -1;
  private boolean pending;

  public StatusCode reset() {
    if (pending) return StatusCode.CONFLICT;
    providerAuthority = null;
    providerGeneration = transactionId = 0;
    transactionGeneration = laneId = laneGeneration = requestGeneration = 0;
    requestSlot = -1;
    return StatusCode.OK;
  }

  public StatusCode bind(
      Object authority, long providerGen,
      long transaction, long transactionGen, long id, long laneGen,
      long requestGen, long slot) {
    if (pending || authority == null || transaction <= 0 || transactionGen <= 0
        || id < 0 || laneGen <= 0
        || requestGen <= 0 || slot < 0) return StatusCode.CONFLICT;
    providerAuthority = authority;
    providerGeneration = providerGen;
    transactionId = transaction;
    transactionGeneration = transactionGen;
    laneId = id;
    laneGeneration = laneGen;
    requestGeneration = requestGen;
    requestSlot = slot;
    pending = true;
    return StatusCode.OK;
  }

  public StatusCode complete(
      Object authority, long providerGen,
      long transaction, long transactionGen, long id, long laneGen,
      long requestGen, long slot) {
    if (!matches(authority, providerGen, transaction, transactionGen,
        id, laneGen, requestGen, slot)) return StatusCode.NOT_OWNER;
    pending = false;
    requestSlot = -1;
    return StatusCode.OK;
  }

  public boolean matches(
      Object authority, long providerGen,
      long transaction, long transactionGen, long id, long laneGen,
      long requestGen, long slot) {
    return pending && providerAuthority == authority
        && providerGeneration == providerGen && transactionId == transaction
        && transactionGeneration == transactionGen && laneId == id
        && laneGeneration == laneGen && requestGeneration == requestGen
        && requestSlot == slot;
  }

  public long providerGeneration() { return providerGeneration; }
  public long transactionId() { return transactionId; }
  public long transactionGeneration() { return transactionGeneration; }
  public long laneId() { return laneId; }
  public long laneGeneration() { return laneGeneration; }
  public long requestGeneration() { return requestGeneration; }
  public long requestSlot() { return requestSlot; }
  public boolean isPending() { return pending; }
}
