package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockToken;

/** Canonical exact-holding ownership, references, upgrades, and recycling. */
final class LockExactHoldingLifecycle {
  private final LockExactTable table;

  LockExactHoldingLifecycle(LockExactTable owner) { table = owner; }

  StatusCode acquire(long resource, long holding, LockMode requested, LockToken token) {
    LockExactHoldingStore.Chunk hc = table.state.holdings.record(holding);
    int ho = LockTypedSlots.offset(holding);
    if (hc.active[ho] == 0) return StatusCode.RETRY;
    if (hc.references[ho] == Long.MAX_VALUE || table.nextReference == Long.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long transaction = hc.transactions[ho];
    if (hc.modes[ho] < requested.ordinal()) {
      LockExactResourceStore.Chunk rc = table.state.resources.record(resource);
      int ro = LockTypedSlots.offset(resource);
      if (interval(rc.scopes[ro])) {
        if (table.conflicts.activeBlocked(
            resource, transaction, requested.ordinal())) return StatusCode.RETRY;
      } else if (!LockExactCompatibility.upgradeable(
          requested.ordinal(), rc.ownerCounts[ro],
          rc.sharedCounts[ro], rc.updateCounts[ro])) return StatusCode.RETRY;
      changeMode(rc, ro, hc.modes[ho], requested.ordinal());
      hc.modes[ho] = (byte) requested.ordinal();
      table.scheduler.schedule(resource);
      StatusCode blocked = table.lifecycle.blockedStatus(transaction);
      if (!blocked.isOk()) return blocked;
    }
    return issueToken(holding, table.nextReference++, token, true);
  }

  StatusCode issueToken(
      long holding, long reference, LockToken token, boolean increment) {
    if (reference <= 0) return StatusCode.RESOURCE_EXHAUSTED;
    LockExactHoldingStore.Chunk hc = table.state.holdings.record(holding);
    int ho = LockTypedSlots.offset(holding);
    long transaction = hc.transactions[ho];
    StatusCode status = token.claim(table.authority, LockExactTable.PROVIDER_GENERATION,
        hc.capabilities[ho], table.state.holdings.generation(holding),
        table.lifecycle.transactionId(transaction), table.lifecycle.transactionGeneration(transaction),
        reference, holding);
    if (status.isOk() && increment) hc.references[ho]++;
    return status;
  }

  StatusCode release(LockToken token) {
    if (!validToken(token)) {
      if (token != null && token.isActive() && token.isOwnedBy(table.authority)
          && token.providerGeneration() == LockExactTable.PROVIDER_GENERATION) {
        token.complete(table.authority);
      }
      return StatusCode.NOT_OWNER;
    }
    if (table.lifecycle.frozen(transaction(token))) return StatusCode.CONFLICT;
    StatusCode status = token.complete(table.authority);
    if (!status.isOk()) return status;
    releaseReference(token.slot(), true);
    return StatusCode.OK;
  }

  StatusCode retain(LockToken token) {
    if (!validToken(token)) return StatusCode.NOT_OWNER;
    long holding = token.slot();
    if (table.lifecycle.frozen(transaction(token))) return StatusCode.CONFLICT;
    StatusCode status = token.complete(table.authority);
    if (!status.isOk()) return status;
    LockExactHoldingStore.Chunk hc = table.state.holdings.record(holding);
    int ho = LockTypedSlots.offset(holding);
    if (hc.retained[ho] == 0) {
      hc.retained[ho] = 1;
    } else {
      releaseReference(holding, true);
    }
    return StatusCode.OK;
  }

  StatusCode holds(long holding, LockMode mode) {
    if (holding < 0 || mode == null || !table.state.holdings.occupied(holding)) {
      return StatusCode.NOT_OWNER;
    }
    LockExactHoldingStore.Chunk hc = table.state.holdings.record(holding);
    int ho = LockTypedSlots.offset(holding);
    return hc.active[ho] != 0 && hc.retained[ho] != 0 && hc.modes[ho] >= mode.ordinal()
        ? StatusCode.OK : StatusCode.NOT_OWNER;
  }

  StatusCode acknowledge(LockToken token) {
    if (token == null || !token.isActive() || !token.isOwnedBy(table.authority)
        || token.providerGeneration() != LockExactTable.PROVIDER_GENERATION) {
      return StatusCode.NOT_OWNER;
    }
    if (validToken(token)) return StatusCode.CONFLICT;
    StatusCode status = token.complete(table.authority);
    return status.isOk() ? StatusCode.NOT_OWNER : status;
  }

  StatusCode upgrade(LockToken token, LockMode mode) {
    if (mode == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!validToken(token)) return StatusCode.NOT_OWNER;
    if (table.lifecycle.frozen(transaction(token))) return StatusCode.CONFLICT;
    long holding = token.slot();
    LockExactHoldingStore.Chunk hc = table.state.holdings.record(holding);
    int ho = LockTypedSlots.offset(holding);
    long transaction = hc.transactions[ho];
    if (hc.modes[ho] >= mode.ordinal()) return StatusCode.OK;
    long resource = hc.resources[ho];
    LockExactResourceStore.Chunk rc = table.state.resources.record(resource);
    int ro = LockTypedSlots.offset(resource);
    int requested = mode.ordinal();
    if (interval(rc.scopes[ro])) {
      if (table.conflicts.activeBlocked(resource, transaction, requested)) {
        return StatusCode.RETRY;
      }
    } else {
      boolean compatible = LockExactCompatibility.upgradeable(
          requested, rc.ownerCounts[ro], rc.sharedCounts[ro], rc.updateCounts[ro]);
      if (!compatible) return StatusCode.RETRY;
    }
    changeMode(rc, ro, hc.modes[ho], requested);
    hc.modes[ho] = (byte) requested;
    table.scheduler.schedule(resource);
    return table.lifecycle.blockedStatus(transaction);
  }

  void grant(long resource, long holding, byte requested) {
    LockExactHoldingStore.Chunk hc = table.state.holdings.record(holding);
    int offset = LockTypedSlots.offset(holding);
    if (hc.active[offset] == 0) {
      table.state.activateHolding(holding, LockExactTable.LOCK_MODES[requested]);
      table.holdingCount++;
      return;
    }
    if (hc.modes[offset] >= requested) return;
    LockExactResourceStore.Chunk rc = table.state.resources.record(resource);
    int ro = LockTypedSlots.offset(resource);
    changeMode(rc, ro, hc.modes[offset], requested);
    hc.modes[offset] = requested;
  }

  void releaseReference(long holding, boolean recycleNow) {
    if (!table.state.holdings.occupied(holding)) return;
    LockExactHoldingStore.Chunk hc = table.state.holdings.record(holding);
    int ho = LockTypedSlots.offset(holding);
    if (--hc.references[ho] > 0) return;
    long resource = hc.resources[ho];
    long transaction = hc.transactions[ho];
    boolean active = hc.active[ho] != 0;
    if (active) table.unlink.holding(resource, transaction, holding);
    table.state.directory.holdingIndex.remove(holding, LockExactDirectory.holdingHash(
        resource, table.lifecycle.transactionId(transaction),
        table.lifecycle.transactionGeneration(transaction)));
    table.state.holdings.free(holding);
    if (active) {
      table.holdingCount--;
      table.scheduler.schedule(resource);
    }
    if (recycleNow) table.lifecycle.recycle(resource, transaction);
  }

  void releaseAll(long transaction) {
    LockExactTransactionStore.Chunk tc = table.state.transactions.record(transaction);
    int to = LockTypedSlots.offset(transaction);
    long holding = LockTypedSlots.decode(tc.holdingHeads[to]);
    while (holding >= 0) {
      LockExactHoldingStore.Chunk hc = table.state.holdings.record(holding);
      int ho = LockTypedSlots.offset(holding);
      long next = LockTypedSlots.decode(hc.nextTransaction[ho]);
      long resource = hc.resources[ho];
      table.unlink.holding(resource, transaction, holding);
      table.state.directory.holdingIndex.remove(holding, LockExactDirectory.holdingHash(
          resource, table.lifecycle.transactionId(transaction),
          table.lifecycle.transactionGeneration(transaction)));
      table.state.holdings.free(holding);
      table.holdingCount--;
      table.scheduler.schedule(resource);
      table.lifecycle.recycleResource(resource);
      holding = next;
    }
  }

  private boolean validToken(LockToken token) {
    if (token == null || !token.isActive() || !token.isOwnedBy(table.authority)
        || token.providerGeneration() != LockExactTable.PROVIDER_GENERATION || token.slot() < 0
        || !table.state.holdings.occupied(token.slot())
        || table.state.holdings.generation(token.slot()) != token.holdingGeneration()) return false;
    LockExactHoldingStore.Chunk hc = table.state.holdings.record(token.slot());
    int ho = LockTypedSlots.offset(token.slot());
    long transaction = hc.transactions[ho];
    return table.state.transactions.occupied(transaction)
        && table.state.transactions.generation(transaction) == hc.transactionRecordGenerations[ho]
        && table.lifecycle.transactionId(transaction) == token.transactionId()
        && table.lifecycle.transactionGeneration(transaction) == token.transactionGeneration()
        && hc.capabilities[ho] == token.capabilityToken();
  }

  private long transaction(LockToken token) {
    return table.state.holdings.record(token.slot())
        .transactions[LockTypedSlots.offset(token.slot())];
  }

  private static void changeMode(
      LockExactResourceStore.Chunk chunk, int offset, int current, int requested) {
    if (current == LockMode.SHARED.ordinal()) chunk.sharedCounts[offset]--;
    else if (current == LockMode.UPDATE.ordinal()) chunk.updateCounts[offset]--;
    if (requested == LockMode.SHARED.ordinal()) chunk.sharedCounts[offset]++;
    else if (requested == LockMode.UPDATE.ordinal()) chunk.updateCounts[offset]++;
  }

  private static boolean interval(byte scope) {
    return LockIntervalIndex.intervalScope(scope);
  }
}
