package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockToken;

/** Core bounded lock acquisition scan, kept separate from the service boundary. */
final class LockAcquisition {
  private LockAcquisition() {
  }

  static StatusCode acquire(
      LockManager manager,
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
    if (transactionId <= 0 || scope == null || mode == null || token == null
        || !LockResourceOverlap.isValid(scope, lowerSpace, lowerKey, upperSpace, upperKey)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (manager.isDeadlockVictim(transactionId)) {
      manager.removeWait(transactionId);
      return StatusCode.CONFLICT;
    }
    if (token.isActive()) return StatusCode.CONFLICT;
    int freeSlot = -1;
    boolean blocked = false;
    for (int slot = 0; slot < manager.occupied.length; slot++) {
      if (!manager.occupied[slot]) {
        if (freeSlot < 0) freeSlot = slot;
        continue;
      }
      if (!LockResourceOverlap.overlaps((byte) scope.ordinal(), lowerSpace, lowerKey,
          upperSpace, upperKey, manager.scopes[slot], manager.lowerSpaces[slot],
          manager.lowerKeys[slot], manager.upperSpaces[slot], manager.upperKeys[slot])) {
        continue;
      }
      if (manager.transactionIds[slot] == transactionId) {
        if (LockResourceOverlap.same((byte) scope.ordinal(), lowerSpace, lowerKey,
            upperSpace, upperKey, manager.scopes[slot], manager.lowerSpaces[slot],
            manager.lowerKeys[slot], manager.upperSpaces[slot], manager.upperKeys[slot])) {
          return StatusCode.CONFLICT;
        }
        continue;
      }
      if (LockManager.conflictingModes((byte) mode.ordinal(), manager.modes[slot])) {
        blocked = true;
      }
    }
    if (!blocked) blocked = manager.hasEarlierWaiter(
        transactionId, scope, lowerSpace, lowerKey, upperSpace, upperKey);
    if (blocked) {
      if (deadlineNanos > 0 && nowNanos >= deadlineNanos) {
        manager.removeWait(transactionId);
        return StatusCode.TIMEOUT;
      }
      StatusCode status = manager.registerWait(transactionId, scope, lowerSpace, lowerKey,
          upperSpace, upperKey, mode, deadlineNanos);
      if (!status.isOk()) return status;
      long victim = manager.cycleVictim(transactionId);
      if (victim > 0) {
        manager.markDeadlockVictim(victim);
        manager.removeWait(victim);
        if (victim == transactionId) return StatusCode.CONFLICT;
      }
      return StatusCode.RETRY;
    }
    if (freeSlot < 0 || manager.nextCapabilityToken <= 0) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    manager.removeWait(transactionId);
    long capabilityToken = manager.nextCapabilityToken++;
    StatusCode status = token.claim(LockManager.OWNER_HIGH, manager.ownerLow, 1,
        capabilityToken, transactionId, freeSlot);
    if (!status.isOk()) return status;
    manager.occupied[freeSlot] = true;
    manager.transactionIds[freeSlot] = transactionId;
    manager.lowerSpaces[freeSlot] = lowerSpace;
    manager.lowerKeys[freeSlot] = lowerKey;
    manager.upperSpaces[freeSlot] = upperSpace;
    manager.upperKeys[freeSlot] = upperKey;
    manager.capabilityTokens[freeSlot] = capabilityToken;
    manager.scopes[freeSlot] = (byte) scope.ordinal();
    manager.modes[freeSlot] = (byte) mode.ordinal();
    manager.activeLockCount++;
    return StatusCode.OK;
  }
}
