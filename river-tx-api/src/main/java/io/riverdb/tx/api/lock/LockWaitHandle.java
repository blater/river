package io.riverdb.tx.api.lock;

import io.riverdb.base.error.StatusCode;
import java.util.concurrent.locks.LockSupport;

/** Caller-owned wait result and exact targeted-wakeup carrier. */
public final class LockWaitHandle {
  private Object providerAuthority;
  private long providerGeneration;
  private long transactionId;
  private long transactionGeneration;
  private long laneId;
  private long laneGeneration;
  private long requestGeneration;
  private long requestSlot = -1;
  private volatile LockWaitState state = LockWaitState.IDLE;
  private volatile StatusCode status = StatusCode.OK;
  private Thread waiter;
  private boolean acknowledged = true;

  public StatusCode reset() {
    if (!acknowledged || state == LockWaitState.QUEUED) {
      return StatusCode.CONFLICT;
    }
    providerAuthority = null;
    providerGeneration = transactionId = 0;
    transactionGeneration = laneId = laneGeneration = requestGeneration = 0;
    requestSlot = -1;
    waiter = null;
    status = StatusCode.OK;
    state = LockWaitState.IDLE;
    return StatusCode.OK;
  }

  public StatusCode bind(
      Object authority, long providerGen,
      long transaction, long transactionGen, long id, long laneGen,
      long requestGen, long slot) {
    if (state != LockWaitState.IDLE || !acknowledged || authority == null
        || transaction <= 0 || transactionGen <= 0 || id < 0 || laneGen <= 0
        || requestGen <= 0 || slot < 0) return StatusCode.CONFLICT;
    providerAuthority = authority;
    providerGeneration = providerGen;
    transactionId = transaction;
    transactionGeneration = transactionGen;
    laneId = id;
    laneGeneration = laneGen;
    requestGeneration = requestGen;
    requestSlot = slot;
    waiter = null;
    acknowledged = false;
    status = StatusCode.RETRY;
    state = LockWaitState.QUEUED;
    return StatusCode.OK;
  }

  public StatusCode transition(
      Object authority, long providerGen,
      long transaction, long transactionGen, long id, long laneGen,
      long requestGen, long slot, LockWaitState expected,
      LockWaitState next, StatusCode outcome) {
    if (state != expected || !matches(authority, providerGen,
        transaction, transactionGen, id, laneGen, requestGen, slot)) {
      return StatusCode.NOT_OWNER;
    }
    status = outcome;
    state = next;
    return StatusCode.OK;
  }

  public boolean matches(
      Object authority, long providerGen,
      long transaction, long transactionGen, long id, long laneGen,
      long requestGen, long slot) {
    return providerAuthority == authority
        && providerGeneration == providerGen && transactionId == transaction
        && transactionGeneration == transactionGen && laneId == id
        && laneGeneration == laneGen && requestGeneration == requestGen
        && requestSlot == slot;
  }

  public boolean matchesIdentity(
      Object authority, long providerGen,
      long transaction, long transactionGen, long id, long laneGen,
      long requestGen) {
    return providerAuthority == authority
        && providerGeneration == providerGen && transactionId == transaction
        && transactionGeneration == transactionGen && laneId == id
        && laneGeneration == laneGen && requestGeneration == requestGen;
  }

  public StatusCode detach(
      Object authority, long providerGen,
      long transaction, long transactionGen, long id, long laneGen,
      long requestGen, long slot) {
    if (!matches(authority, providerGen, transaction, transactionGen,
        id, laneGen, requestGen, slot)) return StatusCode.NOT_OWNER;
    requestSlot = -1;
    waiter = null;
    return StatusCode.OK;
  }

  public StatusCode unpark(
      Object authority, long providerGen,
      long transaction, long transactionGen, long id, long laneGen,
      long requestGen, long slot) {
    if (!matches(authority, providerGen, transaction, transactionGen,
        id, laneGen, requestGen, slot)) return StatusCode.NOT_OWNER;
    Thread waitingThread = waiter;
    if (waitingThread != null) LockSupport.unpark(waitingThread);
    return StatusCode.OK;
  }

  public StatusCode arm(
      Object authority, long providerGen,
      long transaction, long transactionGen, long id, long laneGen,
      long requestGen, long slot, Thread waitingThread) {
    if (state != LockWaitState.QUEUED || waitingThread == null
        || !matches(authority, providerGen, transaction, transactionGen,
            id, laneGen, requestGen, slot)) return StatusCode.NOT_OWNER;
    if (waiter != null && waiter != waitingThread) return StatusCode.CONFLICT;
    waiter = waitingThread;
    return StatusCode.OK;
  }

  public StatusCode acknowledge(
      Object authority, long providerGen,
      long transaction, long transactionGen, long id, long laneGen,
      long requestGen) {
    if (acknowledged || requestSlot >= 0 || state == LockWaitState.QUEUED
        || state == LockWaitState.GRANTED
        || !matchesIdentity(authority, providerGen, transaction, transactionGen,
            id, laneGen, requestGen)) return StatusCode.NOT_OWNER;
    acknowledged = true;
    return StatusCode.OK;
  }

  public StatusCode completeGrant(
      Object authority, long providerGen,
      long transaction, long transactionGen, long id, long laneGen,
      long requestGen, long slot) {
    if (state != LockWaitState.GRANTED || acknowledged
        || !matches(authority, providerGen, transaction, transactionGen,
            id, laneGen, requestGen, slot)) return StatusCode.NOT_OWNER;
    requestSlot = -1;
    waiter = null;
    acknowledged = true;
    return StatusCode.OK;
  }

  public long providerGeneration() { return providerGeneration; }
  public long transactionId() { return transactionId; }
  public long transactionGeneration() { return transactionGeneration; }
  public long laneId() { return laneId; }
  public long laneGeneration() { return laneGeneration; }
  public long requestGeneration() { return requestGeneration; }
  public long requestSlot() { return requestSlot; }
  public LockWaitState state() { return state; }
  public StatusCode status() { return status; }
}
