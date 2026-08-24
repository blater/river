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
  static final long OWNER_HIGH = 0x52495645524c4f43L; // RIVERLOC
  static final LockScope[] LOCK_SCOPES = LockScope.values();

  final long ownerLow = PROVIDER_IDENTITIES.getAndIncrement();
  final long[] transactionIds;
  final int[] lowerSpaces;
  final long[] lowerKeys;
  final int[] upperSpaces;
  final long[] upperKeys;
  final long[] capabilityTokens;
  final byte[] scopes;
  final byte[] modes;
  final boolean[] occupied;
  private final long[] waitingTransactionIds;
  private final int[] waitingLowerSpaces;
  private final long[] waitingLowerKeys;
  private final int[] waitingUpperSpaces;
  private final long[] waitingUpperKeys;
  private final long[] waitingOrders;
  private final long[] waitingDeadlines;
  private final byte[] waitingScopes;
  private final byte[] waitingModes;
  private final boolean[] waiting;
  private final long[] deadlockVictims;
  private final long[] cyclePath;
  long nextCapabilityToken = 1;
  private long nextWaitOrder = 1;
  int activeLockCount;
  private int waitingCount;
  private long deadlockVictimSelections;

  public LockManager(int maximumLocks) {
    transactionIds = new long[maximumLocks];
    lowerSpaces = new int[maximumLocks];
    lowerKeys = new long[maximumLocks];
    upperSpaces = new int[maximumLocks];
    upperKeys = new long[maximumLocks];
    capabilityTokens = new long[maximumLocks];
    scopes = new byte[maximumLocks];
    modes = new byte[maximumLocks];
    occupied = new boolean[maximumLocks];
    waitingTransactionIds = new long[maximumLocks];
    waitingLowerSpaces = new int[maximumLocks];
    waitingLowerKeys = new long[maximumLocks];
    waitingUpperSpaces = new int[maximumLocks];
    waitingUpperKeys = new long[maximumLocks];
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
        request.lowerSpace(),
        request.lowerKey(),
        request.upperSpace(),
        request.upperKey(),
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
      int lowerSpace,
      long lowerKey,
      int upperSpace,
      long upperKey,
      LockMode mode,
      long deadlineNanos,
      long nowNanos,
      LockToken token) {
    return LockAcquisition.acquire(this, transactionId, scope, lowerSpace, lowerKey,
        upperSpace, upperKey, mode, deadlineNanos, nowNanos, token);
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
    lowerSpaces[slot] = 0;
    lowerKeys[slot] = 0;
    upperSpaces[slot] = 0;
    upperKeys[slot] = 0;
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
    return LockUpgrade.apply(this, token, requestedMode, deadlineNanos, nowNanos);
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

  StatusCode registerWait(
      long transactionId,
      LockScope scope,
      int lowerSpace,
      long lowerKey,
      int upperSpace,
      long upperKey,
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
          || waitingLowerSpaces[slot] != lowerSpace
          || waitingLowerKeys[slot] != lowerKey
          || waitingUpperSpaces[slot] != upperSpace
          || waitingUpperKeys[slot] != upperKey) {
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
    waitingLowerSpaces[freeSlot] = lowerSpace;
    waitingLowerKeys[freeSlot] = lowerKey;
    waitingUpperSpaces[freeSlot] = upperSpace;
    waitingUpperKeys[freeSlot] = upperKey;
    waitingOrders[freeSlot] = nextWaitOrder++;
    waitingDeadlines[freeSlot] = deadlineNanos;
    waitingScopes[freeSlot] = (byte) scope.ordinal();
    waitingModes[freeSlot] = (byte) mode.ordinal();
    waitingCount++;
    return StatusCode.OK;
  }

  boolean hasEarlierWaiter(
      long transactionId,
      LockScope scope,
      int lowerSpace,
      long lowerKey,
      int upperSpace,
      long upperKey) {
    int ownSlot = findWait(transactionId);
    long ownOrder = ownSlot < 0 ? Long.MAX_VALUE : waitingOrders[ownSlot];
    for (int slot = 0; slot < waiting.length; slot++) {
      if (waiting[slot]
          && waitingTransactionIds[slot] != transactionId
          && LockResourceOverlap.overlaps(
              (byte) scope.ordinal(),
              lowerSpace,
              lowerKey,
              upperSpace,
              upperKey,
              waitingScopes[slot],
              waitingLowerSpaces[slot],
              waitingLowerKeys[slot],
              waitingUpperSpaces[slot],
              waitingUpperKeys[slot])
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

  void removeWait(long transactionId) {
    int slot = findWait(transactionId);
    if (slot < 0) {
      return;
    }
    waiting[slot] = false;
    waitingTransactionIds[slot] = 0;
    waitingLowerSpaces[slot] = 0;
    waitingLowerKeys[slot] = 0;
    waitingUpperSpaces[slot] = 0;
    waitingUpperKeys[slot] = 0;
    waitingOrders[slot] = 0;
    waitingDeadlines[slot] = 0;
    waitingScopes[slot] = 0;
    waitingModes[slot] = 0;
    waitingCount--;
  }

  long cycleVictim(long transactionId) {
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
              waitSlot,
              scopes[slot],
              lowerSpaces[slot],
              lowerKeys[slot],
              upperSpaces[slot],
              upperKeys[slot])
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
              waitingLowerSpaces[slot],
              waitingLowerKeys[slot],
              waitingUpperSpaces[slot],
              waitingUpperKeys[slot])) {
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
      int lowerSpace,
      long lowerKey,
      int upperSpace,
      long upperKey) {
    return LockResourceOverlap.overlaps(
        waitingScopes[waitSlot],
        waitingLowerSpaces[waitSlot],
        waitingLowerKeys[waitSlot],
        waitingUpperSpaces[waitSlot],
        waitingUpperKeys[waitSlot],
        scope,
        lowerSpace,
        lowerKey,
        upperSpace,
        upperKey);
  }

  static boolean conflictingModes(byte left, byte right) {
    return conflicts(left, right) || conflicts(right, left);
  }

  void markDeadlockVictim(long transactionId) {
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

  boolean validToken(LockToken token, int slot) {
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
