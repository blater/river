package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockScope;
import io.riverdb.tx.api.lock.LockToken;

/** Upgrade scan for an already-held logical lock. */
final class LockUpgrade {
  private LockUpgrade() {
  }

  static StatusCode apply(
      LockManager manager, LockToken token, LockMode requestedMode,
      long deadlineNanos, long nowNanos) {
    if (token == null || requestedMode == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (manager.isDeadlockVictim(token.transactionId())) {
      manager.removeWait(token.transactionId());
      return StatusCode.CONFLICT;
    }
    int slot = token.slot();
    if (!manager.validToken(token, slot)) return StatusCode.NOT_OWNER;
    int requested = requestedMode.ordinal();
    int held = manager.modes[slot];
    if (held >= requested) {
      manager.removeWait(token.transactionId());
      return StatusCode.OK;
    }
    boolean blocked = false;
    for (int other = 0; other < manager.occupied.length; other++) {
      if (other == slot || !manager.occupied[other]
          || manager.transactionIds[other] == token.transactionId()
          || !LockResourceOverlap.overlaps(manager.scopes[slot], manager.lowerSpaces[slot],
              manager.lowerKeys[slot], manager.upperSpaces[slot], manager.upperKeys[slot],
              manager.scopes[other], manager.lowerSpaces[other], manager.lowerKeys[other],
              manager.upperSpaces[other], manager.upperKeys[other])) continue;
      if (LockManager.conflictingModes((byte) requested, manager.modes[other])) blocked = true;
    }
    LockScope scope = LockManager.LOCK_SCOPES[manager.scopes[slot]];
    if (!blocked) {
      blocked = manager.hasEarlierWaiter(token.transactionId(), scope,
          manager.lowerSpaces[slot], manager.lowerKeys[slot],
          manager.upperSpaces[slot], manager.upperKeys[slot]);
    }
    if (blocked) {
      if (deadlineNanos > 0 && nowNanos >= deadlineNanos) {
        manager.removeWait(token.transactionId());
        return StatusCode.TIMEOUT;
      }
      StatusCode status = manager.registerWait(token.transactionId(), scope,
          manager.lowerSpaces[slot], manager.lowerKeys[slot],
          manager.upperSpaces[slot], manager.upperKeys[slot], requestedMode, deadlineNanos);
      if (!status.isOk()) return status;
      long victim = manager.cycleVictim(token.transactionId());
      if (victim > 0) {
        manager.markDeadlockVictim(victim);
        manager.removeWait(victim);
        if (victim == token.transactionId()) return StatusCode.CONFLICT;
      }
      return StatusCode.RETRY;
    }
    manager.removeWait(token.transactionId());
    manager.modes[slot] = (byte) requested;
    return StatusCode.OK;
  }
}
