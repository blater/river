package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.tx.api.TransactionContext;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockService;
import io.riverdb.tx.api.lock.LockToken;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded in-memory logical lock table with authenticated caller-owned tokens. */
public final class LockManager implements LockService {
  private static final AtomicLong PROVIDER_IDENTITIES = new AtomicLong(1);
  private static final long OWNER_HIGH = 0x52495645524c4f43L; // RIVERLOC

  private final long ownerLow = PROVIDER_IDENTITIES.getAndIncrement();
  private final long[] transactionIds;
  private final long[] resourceHighs;
  private final long[] resourceLows;
  private final long[] capabilityTokens;
  private final byte[] scopes;
  private final byte[] modes;
  private final boolean[] occupied;
  private long nextCapabilityToken = 1;
  private int activeLockCount;

  public LockManager(int maximumLocks) {
    transactionIds = new long[maximumLocks];
    resourceHighs = new long[maximumLocks];
    resourceLows = new long[maximumLocks];
    capabilityTokens = new long[maximumLocks];
    scopes = new byte[maximumLocks];
    modes = new byte[maximumLocks];
    occupied = new boolean[maximumLocks];
  }

  public synchronized int activeLockCount() {
    return activeLockCount;
  }

  @Override
  public synchronized StatusCode tryAcquire(
      TransactionContext context,
      LockRequest request,
      long nowNanos,
      LockToken token,
      StatusDetail detail) {
    if (context == null || request == null || token == null || detail == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    detail.reset();
    if (context.cancellation().isCancellationRequested()) {
      detail.set(StatusCode.CANCELLED);
      return StatusCode.CANCELLED;
    }
    StatusCode status = tryAcquire(
        context.transactionId(),
        request.scope(),
        request.resourceHigh(),
        request.resourceLow(),
        request.mode(),
        request.deadlineNanos(),
        nowNanos,
        token);
    detail.set(status);
    return status;
  }

  public synchronized StatusCode tryAcquire(
      long transactionId,
      LockScope scope,
      long resourceHigh,
      long resourceLow,
      LockMode mode,
      long deadlineNanos,
      long nowNanos,
      LockToken token) {
    if (transactionId <= 0 || scope == null || mode == null || token == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (token.isActive()) {
      return StatusCode.CONFLICT;
    }
    int freeSlot = -1;
    for (int slot = 0; slot < occupied.length; slot++) {
      if (!occupied[slot]) {
        if (freeSlot < 0) {
          freeSlot = slot;
        }
        continue;
      }
      if (scopes[slot] != (byte) scope.ordinal()
          || resourceHighs[slot] != resourceHigh
          || resourceLows[slot] != resourceLow) {
        continue;
      }
      if (transactionIds[slot] == transactionId) {
        return StatusCode.CONFLICT;
      }
      int requestedMode = mode.ordinal();
      int heldMode = modes[slot];
      if (conflicts(requestedMode, heldMode) || conflicts(heldMode, requestedMode)) {
        return deadlineNanos > 0 && nowNanos >= deadlineNanos
            ? StatusCode.TIMEOUT : StatusCode.RETRY;
      }
    }
    if (freeSlot < 0 || nextCapabilityToken <= 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long capabilityToken = nextCapabilityToken++;
    StatusCode status = token.claim(
        OWNER_HIGH, ownerLow, 1, capabilityToken, transactionId, freeSlot);
    if (!status.isOk()) {
      return status;
    }
    occupied[freeSlot] = true;
    transactionIds[freeSlot] = transactionId;
    resourceHighs[freeSlot] = resourceHigh;
    resourceLows[freeSlot] = resourceLow;
    capabilityTokens[freeSlot] = capabilityToken;
    scopes[freeSlot] = (byte) scope.ordinal();
    modes[freeSlot] = (byte) mode.ordinal();
    activeLockCount++;
    return StatusCode.OK;
  }

  @Override
  public synchronized StatusCode release(LockToken token, StatusDetail detail) {
    StatusCode status = release(token);
    if (detail != null) {
      detail.set(status);
    }
    return status;
  }

  public synchronized StatusCode release(LockToken token) {
    if (token == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int slot = token.slot();
    if (!validToken(token, slot)) {
      return StatusCode.NOT_OWNER;
    }
    StatusCode status = token.complete(OWNER_HIGH, ownerLow);
    if (!status.isOk()) {
      return status;
    }
    occupied[slot] = false;
    transactionIds[slot] = 0;
    resourceHighs[slot] = 0;
    resourceLows[slot] = 0;
    capabilityTokens[slot] = 0;
    scopes[slot] = 0;
    modes[slot] = 0;
    activeLockCount--;
    return StatusCode.OK;
  }

  public synchronized StatusCode upgrade(
      LockToken token,
      LockMode requestedMode,
      long deadlineNanos,
      long nowNanos) {
    if (token == null || requestedMode == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int slot = token.slot();
    if (!validToken(token, slot)) {
      return StatusCode.NOT_OWNER;
    }
    int requested = requestedMode.ordinal();
    int held = modes[slot];
    if (held >= requested) {
      return StatusCode.OK;
    }
    for (int other = 0; other < occupied.length; other++) {
      if (other == slot
          || !occupied[other]
          || scopes[other] != scopes[slot]
          || resourceHighs[other] != resourceHighs[slot]
          || resourceLows[other] != resourceLows[slot]) {
        continue;
      }
      if (conflicts(requested, modes[other]) || conflicts(modes[other], requested)) {
        return deadlineNanos > 0 && nowNanos >= deadlineNanos
            ? StatusCode.TIMEOUT : StatusCode.RETRY;
      }
    }
    modes[slot] = (byte) requested;
    return StatusCode.OK;
  }

  private boolean validToken(LockToken token, int slot) {
    return token.isActive()
        && token.isOwnedBy(OWNER_HIGH, ownerLow)
        && token.providerGeneration() == 1
        && slot >= 0
        && slot < occupied.length
        && occupied[slot]
        && capabilityTokens[slot] == token.capabilityToken()
        && transactionIds[slot] == token.transactionId();
  }

  private static boolean conflicts(int left, int right) {
    if (left == LockMode.SHARED.ordinal()) {
      return right == LockMode.EXCLUSIVE.ordinal();
    }
    if (left == LockMode.UPDATE.ordinal()) {
      return right != LockMode.SHARED.ordinal();
    }
    return true;
  }
}
