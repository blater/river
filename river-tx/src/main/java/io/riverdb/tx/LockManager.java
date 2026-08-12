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
  private static final LockScope[] LOCK_SCOPES = LockScope.values();

  private final long ownerLow = PROVIDER_IDENTITIES.getAndIncrement();
  private final long[] transactionIds;
  private final long[] resourceHighs;
  private final long[] resourceLows;
  private final long[] capabilityTokens;
  private final byte[] scopes;
  private final byte[] modes;
  private final boolean[] occupied;
  private final long[] waitingTransactionIds;
  private final long[] waitingResourceHighs;
  private final long[] waitingResourceLows;
  private final long[] waitingOrders;
  private final long[] waitingDeadlines;
  private final byte[] waitingScopes;
  private final byte[] waitingModes;
  private final boolean[] waiting;
  private final long[] deadlockVictims;
  private final long[] cyclePath;
  private long nextCapabilityToken = 1;
  private long nextWaitOrder = 1;
  private int activeLockCount;
  private int waitingCount;
  private long deadlockVictimSelections;

  public LockManager(int maximumLocks) {
    transactionIds = new long[maximumLocks];
    resourceHighs = new long[maximumLocks];
    resourceLows = new long[maximumLocks];
    capabilityTokens = new long[maximumLocks];
    scopes = new byte[maximumLocks];
    modes = new byte[maximumLocks];
    occupied = new boolean[maximumLocks];
    waitingTransactionIds = new long[maximumLocks];
    waitingResourceHighs = new long[maximumLocks];
    waitingResourceLows = new long[maximumLocks];
    waitingOrders = new long[maximumLocks];
    waitingDeadlines = new long[maximumLocks];
    waitingScopes = new byte[maximumLocks];
    waitingModes = new byte[maximumLocks];
    waiting = new boolean[maximumLocks];
    deadlockVictims = new long[maximumLocks];
    cyclePath = new long[maximumLocks];
  }

  public synchronized int activeLockCount() {
    return activeLockCount;
  }

  public synchronized int waitingCount() {
    return waitingCount;
  }

  public synchronized long deadlockVictimSelections() {
    return deadlockVictimSelections;
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
      removeWait(context.transactionId());
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
    if (scope == LockScope.RANGE && resourceHigh >= resourceLow) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (isDeadlockVictim(transactionId)) {
      removeWait(transactionId);
      return StatusCode.CONFLICT;
    }
    if (token.isActive()) {
      return StatusCode.CONFLICT;
    }
    int freeSlot = -1;
    boolean blocked = false;
    for (int slot = 0; slot < occupied.length; slot++) {
      if (!occupied[slot]) {
        if (freeSlot < 0) {
          freeSlot = slot;
        }
        continue;
      }
      if (!resourcesOverlap(
          (byte) scope.ordinal(),
          resourceHigh,
          resourceLow,
          scopes[slot],
          resourceHighs[slot],
          resourceLows[slot])) {
        continue;
      }
      if (transactionIds[slot] == transactionId) {
        if (sameResource(
            (byte) scope.ordinal(),
            resourceHigh,
            resourceLow,
            scopes[slot],
            resourceHighs[slot],
            resourceLows[slot])) {
          return StatusCode.CONFLICT;
        }
        continue;
      }
      int requestedMode = mode.ordinal();
      int heldMode = modes[slot];
      if (conflicts(requestedMode, heldMode) || conflicts(heldMode, requestedMode)) {
        blocked = true;
      }
    }
    if (!blocked) {
      blocked = hasEarlierWaiter(
          transactionId, scope, resourceHigh, resourceLow);
    }
    if (blocked) {
      if (deadlineNanos > 0 && nowNanos >= deadlineNanos) {
        removeWait(transactionId);
        return StatusCode.TIMEOUT;
      }
      StatusCode status = registerWait(
          transactionId,
          scope,
          resourceHigh,
          resourceLow,
          mode,
          deadlineNanos);
      if (!status.isOk()) {
        return status;
      }
      long victim = cycleVictim(transactionId);
      if (victim > 0) {
        markDeadlockVictim(victim);
        removeWait(victim);
        if (victim == transactionId) {
          return StatusCode.CONFLICT;
        }
      }
      return StatusCode.RETRY;
    }
    if (freeSlot < 0 || nextCapabilityToken <= 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    removeWait(transactionId);
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
    if (isDeadlockVictim(token.transactionId())) {
      removeWait(token.transactionId());
      return StatusCode.CONFLICT;
    }
    int slot = token.slot();
    if (!validToken(token, slot)) {
      return StatusCode.NOT_OWNER;
    }
    int requested = requestedMode.ordinal();
    int held = modes[slot];
    if (held >= requested) {
      removeWait(token.transactionId());
      return StatusCode.OK;
    }
    boolean blocked = false;
    for (int other = 0; other < occupied.length; other++) {
      if (other == slot
          || !occupied[other]
          || transactionIds[other] == token.transactionId()
          || !resourcesOverlap(
              scopes[slot],
              resourceHighs[slot],
              resourceLows[slot],
              scopes[other],
              resourceHighs[other],
              resourceLows[other])) {
        continue;
      }
      if (conflicts(requested, modes[other]) || conflicts(modes[other], requested)) {
        blocked = true;
      }
    }
    LockScope scope = LOCK_SCOPES[scopes[slot]];
    if (!blocked) {
      blocked = hasEarlierWaiter(
          token.transactionId(), scope, resourceHighs[slot], resourceLows[slot]);
    }
    if (blocked) {
      if (deadlineNanos > 0 && nowNanos >= deadlineNanos) {
        removeWait(token.transactionId());
        return StatusCode.TIMEOUT;
      }
      StatusCode status = registerWait(
          token.transactionId(),
          scope,
          resourceHighs[slot],
          resourceLows[slot],
          requestedMode,
          deadlineNanos);
      if (!status.isOk()) {
        return status;
      }
      long victim = cycleVictim(token.transactionId());
      if (victim > 0) {
        markDeadlockVictim(victim);
        removeWait(victim);
        if (victim == token.transactionId()) {
          return StatusCode.CONFLICT;
        }
      }
      return StatusCode.RETRY;
    }
    removeWait(token.transactionId());
    modes[slot] = (byte) requested;
    return StatusCode.OK;
  }

  synchronized boolean isDeadlockVictim(long transactionId) {
    for (long victim : deadlockVictims) {
      if (victim == transactionId) {
        return true;
      }
    }
    return false;
  }

  synchronized boolean isWaiting(long transactionId) {
    return findWait(transactionId) >= 0;
  }

  synchronized void cancelWait(long transactionId) {
    removeWait(transactionId);
  }

  synchronized void transactionCompleted(long transactionId) {
    removeWait(transactionId);
    for (int slot = 0; slot < deadlockVictims.length; slot++) {
      if (deadlockVictims[slot] == transactionId) {
        deadlockVictims[slot] = 0;
        return;
      }
    }
  }

  private StatusCode registerWait(
      long transactionId,
      LockScope scope,
      long resourceHigh,
      long resourceLow,
      LockMode mode,
      long deadlineNanos) {
    int freeSlot = -1;
    for (int slot = 0; slot < waiting.length; slot++) {
      if (!waiting[slot]) {
        if (freeSlot < 0) {
          freeSlot = slot;
        }
        continue;
      }
      if (waitingTransactionIds[slot] != transactionId) {
        continue;
      }
      if (waitingScopes[slot] != (byte) scope.ordinal()
          || waitingResourceHighs[slot] != resourceHigh
          || waitingResourceLows[slot] != resourceLow) {
        return StatusCode.CONFLICT;
      }
      waitingModes[slot] = (byte) mode.ordinal();
      waitingDeadlines[slot] = deadlineNanos;
      return StatusCode.OK;
    }
    if (freeSlot < 0 || nextWaitOrder <= 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    waiting[freeSlot] = true;
    waitingTransactionIds[freeSlot] = transactionId;
    waitingResourceHighs[freeSlot] = resourceHigh;
    waitingResourceLows[freeSlot] = resourceLow;
    waitingOrders[freeSlot] = nextWaitOrder++;
    waitingDeadlines[freeSlot] = deadlineNanos;
    waitingScopes[freeSlot] = (byte) scope.ordinal();
    waitingModes[freeSlot] = (byte) mode.ordinal();
    waitingCount++;
    return StatusCode.OK;
  }

  private boolean hasEarlierWaiter(
      long transactionId,
      LockScope scope,
      long resourceHigh,
      long resourceLow) {
    int ownSlot = findWait(transactionId);
    long ownOrder = ownSlot < 0 ? Long.MAX_VALUE : waitingOrders[ownSlot];
    for (int slot = 0; slot < waiting.length; slot++) {
      if (waiting[slot]
          && waitingTransactionIds[slot] != transactionId
          && resourcesOverlap(
              (byte) scope.ordinal(),
              resourceHigh,
              resourceLow,
              waitingScopes[slot],
              waitingResourceHighs[slot],
              waitingResourceLows[slot])
          && waitingOrders[slot] < ownOrder) {
        return true;
      }
    }
    return false;
  }

  private int findWait(long transactionId) {
    for (int slot = 0; slot < waiting.length; slot++) {
      if (waiting[slot] && waitingTransactionIds[slot] == transactionId) {
        return slot;
      }
    }
    return -1;
  }

  private void removeWait(long transactionId) {
    int slot = findWait(transactionId);
    if (slot < 0) {
      return;
    }
    waiting[slot] = false;
    waitingTransactionIds[slot] = 0;
    waitingResourceHighs[slot] = 0;
    waitingResourceLows[slot] = 0;
    waitingOrders[slot] = 0;
    waitingDeadlines[slot] = 0;
    waitingScopes[slot] = 0;
    waitingModes[slot] = 0;
    waitingCount--;
  }

  private long cycleVictim(long transactionId) {
    return findCycleVictim(transactionId, transactionId, 0);
  }

  private long findCycleVictim(
      long transactionId,
      long cycleStart,
      int depth) {
    if (depth >= cyclePath.length || findWait(transactionId) < 0) {
      return 0;
    }
    for (int index = 0; index < depth; index++) {
      if (cyclePath[index] == transactionId) {
        return 0;
      }
    }
    cyclePath[depth] = transactionId;
    int waitSlot = findWait(transactionId);
    long victim = 0;
    for (int slot = 0; slot < occupied.length; slot++) {
      if (!occupied[slot]
          || transactionIds[slot] == transactionId
          || !overlapsWait(
              waitSlot, scopes[slot], resourceHighs[slot], resourceLows[slot])
          || !conflictingModes(waitingModes[waitSlot], modes[slot])) {
        continue;
      }
      victim = Math.max(
          victim,
          dependencyCycleVictim(
              transactionIds[slot], cycleStart, depth));
    }
    for (int slot = 0; slot < waiting.length; slot++) {
      if (!waiting[slot]
          || waitingTransactionIds[slot] == transactionId
          || waitingOrders[slot] >= waitingOrders[waitSlot]
          || !overlapsWait(
              waitSlot,
              waitingScopes[slot],
              waitingResourceHighs[slot],
              waitingResourceLows[slot])) {
        continue;
      }
      victim = Math.max(
          victim,
          dependencyCycleVictim(
              waitingTransactionIds[slot], cycleStart, depth));
    }
    cyclePath[depth] = 0;
    return victim;
  }

  private long dependencyCycleVictim(
      long dependency,
      long cycleStart,
      int depth) {
    if (dependency == cycleStart) {
      long victim = cycleStart;
      for (int index = 0; index <= depth; index++) {
        victim = Math.max(victim, cyclePath[index]);
      }
      return victim;
    }
    return findCycleVictim(dependency, cycleStart, depth + 1);
  }

  private boolean overlapsWait(
      int waitSlot,
      byte scope,
      long resourceHigh,
      long resourceLow) {
    return resourcesOverlap(
        waitingScopes[waitSlot],
        waitingResourceHighs[waitSlot],
        waitingResourceLows[waitSlot],
        scope,
        resourceHigh,
        resourceLow);
  }

  private static boolean sameResource(
      byte leftScope,
      long leftHigh,
      long leftLow,
      byte rightScope,
      long rightHigh,
      long rightLow) {
    return leftScope == rightScope && leftHigh == rightHigh && leftLow == rightLow;
  }

  private static boolean resourcesOverlap(
      byte leftScope,
      long leftHigh,
      long leftLow,
      byte rightScope,
      long rightHigh,
      long rightLow) {
    int range = LockScope.RANGE.ordinal();
    int key = LockScope.KEY.ordinal();
    if (leftScope == range && rightScope == range) {
      return leftHigh < rightLow && rightHigh < leftLow;
    }
    if (leftScope == range && rightScope == key) {
      return rightLow >= leftHigh && rightLow < leftLow;
    }
    if (leftScope == key && rightScope == range) {
      return leftLow >= rightHigh && leftLow < rightLow;
    }
    return sameResource(
        leftScope, leftHigh, leftLow, rightScope, rightHigh, rightLow);
  }

  private static boolean conflictingModes(byte left, byte right) {
    return conflicts(left, right) || conflicts(right, left);
  }

  private void markDeadlockVictim(long transactionId) {
    if (isDeadlockVictim(transactionId)) {
      return;
    }
    for (int slot = 0; slot < deadlockVictims.length; slot++) {
      if (deadlockVictims[slot] == 0) {
        deadlockVictims[slot] = transactionId;
        deadlockVictimSelections++;
        return;
      }
    }
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
