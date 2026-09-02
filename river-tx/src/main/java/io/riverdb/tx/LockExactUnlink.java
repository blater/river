package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockMode;

/** Constant-time unlink operations for exact resource and transaction chains. */
final class LockExactUnlink {
  private final LockExactState state;

  LockExactUnlink(LockExactState owner) { state = owner; }

  void holding(long resource, long transaction, long holding) {
    LockExactHoldingStore.Chunk chunk = state.holdings.record(holding);
    int offset = LockTypedSlots.offset(holding);
    unlinkResourceHolding(resource, chunk, offset);
    unlinkTransactionHolding(transaction, chunk, offset);
    LockExactResourceStore.Chunk resourceChunk = state.resources.record(resource);
    int ro = LockTypedSlots.offset(resource);
    resourceChunk.ownerCounts[ro]--;
    if (chunk.modes[offset] == LockMode.SHARED.ordinal()) resourceChunk.sharedCounts[ro]--;
    else if (chunk.modes[offset] == LockMode.UPDATE.ordinal()) resourceChunk.updateCounts[ro]--;
  }

  void request(long resource, long transaction, long request, boolean resourceLinked) {
    LockExactRequestStore.Chunk chunk = state.requests.record(request);
    int offset = LockTypedSlots.offset(request);
    if (resourceLinked) unlinkResourceRequest(resource, request, chunk, offset);
    unlinkTransactionRequest(transaction, chunk, offset);
  }

  void resourceRequest(long resource, long request) {
    LockExactRequestStore.Chunk chunk = state.requests.record(request);
    unlinkResourceRequest(resource, request, chunk, LockTypedSlots.offset(request));
  }

  void transactionRequest(long transaction, long request) {
    LockExactRequestStore.Chunk chunk = state.requests.record(request);
    unlinkTransactionRequest(transaction, chunk, LockTypedSlots.offset(request));
  }

  private void unlinkResourceHolding(
      long resource, LockExactHoldingStore.Chunk chunk, int offset) {
    long prior = LockTypedSlots.decode(chunk.previousResource[offset]);
    long next = LockTypedSlots.decode(chunk.nextResource[offset]);
    if (prior < 0) state.resources.record(resource).ownerHeads[LockTypedSlots.offset(resource)] =
        LockTypedSlots.encode(next);
    else state.holdings.record(prior).nextResource[LockTypedSlots.offset(prior)] =
        LockTypedSlots.encode(next);
    if (next >= 0) state.holdings.record(next).previousResource[LockTypedSlots.offset(next)] =
        LockTypedSlots.encode(prior);
  }

  private void unlinkTransactionHolding(
      long transaction, LockExactHoldingStore.Chunk chunk, int offset) {
    long prior = LockTypedSlots.decode(chunk.previousTransaction[offset]);
    long next = LockTypedSlots.decode(chunk.nextTransaction[offset]);
    if (prior < 0) state.transactions.record(transaction)
        .holdingHeads[LockTypedSlots.offset(transaction)] = LockTypedSlots.encode(next);
    else state.holdings.record(prior).nextTransaction[LockTypedSlots.offset(prior)] =
        LockTypedSlots.encode(next);
    if (next >= 0) state.holdings.record(next).previousTransaction[LockTypedSlots.offset(next)] =
        LockTypedSlots.encode(prior);
  }

  private void unlinkResourceRequest(
      long resource, long request, LockExactRequestStore.Chunk chunk, int offset) {
    if (state.requests.conversion(request)) {
      unlinkConversion(resource, chunk, offset);
      return;
    }
    long prior = LockTypedSlots.decode(chunk.previousResource[offset]);
    long next = LockTypedSlots.decode(chunk.nextResource[offset]);
    LockExactResourceStore.Chunk resourceChunk = state.resources.record(resource);
    int ro = LockTypedSlots.offset(resource);
    if (prior < 0) resourceChunk.waitHeads[ro] = LockTypedSlots.encode(next);
    else state.requests.record(prior).nextResource[LockTypedSlots.offset(prior)] =
        LockTypedSlots.encode(next);
    if (next < 0) resourceChunk.waitTails[ro] = LockTypedSlots.encode(prior);
    else state.requests.record(next).previousResource[LockTypedSlots.offset(next)] =
        LockTypedSlots.encode(prior);
    int modeOffset = LockExactResourceStore.modeOffset(chunk.modes[offset], ro);
    long priorMode = LockTypedSlots.decode(chunk.previousMode[offset]);
    long nextMode = LockTypedSlots.decode(chunk.nextMode[offset]);
    if (priorMode < 0) resourceChunk.modeWaitHeads[modeOffset] =
        LockTypedSlots.encode(nextMode);
    else state.requests.record(priorMode).nextMode[LockTypedSlots.offset(priorMode)] =
        LockTypedSlots.encode(nextMode);
    if (nextMode < 0) resourceChunk.modeWaitTails[modeOffset] =
        LockTypedSlots.encode(priorMode);
    else state.requests.record(nextMode).previousMode[LockTypedSlots.offset(nextMode)] =
        LockTypedSlots.encode(priorMode);
    chunk.nextResource[offset] = chunk.previousResource[offset] = 0;
    chunk.nextMode[offset] = chunk.previousMode[offset] = 0;
  }

  private void unlinkConversion(
      long resource, LockExactRequestStore.Chunk chunk, int offset) {
    long prior = LockTypedSlots.decode(chunk.previousConversion[offset]);
    long next = LockTypedSlots.decode(chunk.nextConversion[offset]);
    LockExactResourceStore.Chunk resourceChunk = state.resources.record(resource);
    int ro = LockTypedSlots.offset(resource);
    if (prior < 0) resourceChunk.conversionHeads[ro] = LockTypedSlots.encode(next);
    else state.requests.record(prior).nextConversion[LockTypedSlots.offset(prior)] =
        LockTypedSlots.encode(next);
    if (next < 0) resourceChunk.conversionTails[ro] = LockTypedSlots.encode(prior);
    else state.requests.record(next).previousConversion[LockTypedSlots.offset(next)] =
        LockTypedSlots.encode(prior);
    chunk.nextConversion[offset] = chunk.previousConversion[offset] = 0;
  }

  private void unlinkTransactionRequest(
      long transaction, LockExactRequestStore.Chunk chunk, int offset) {
    long prior = LockTypedSlots.decode(chunk.previousTransaction[offset]);
    long next = LockTypedSlots.decode(chunk.nextTransaction[offset]);
    if (prior < 0) state.transactions.record(transaction)
        .requestHeads[LockTypedSlots.offset(transaction)] = LockTypedSlots.encode(next);
    else state.requests.record(prior).nextTransaction[LockTypedSlots.offset(prior)] =
        LockTypedSlots.encode(next);
    if (next >= 0) state.requests.record(next).previousTransaction[LockTypedSlots.offset(next)] =
        LockTypedSlots.encode(prior);
    chunk.nextTransaction[offset] = chunk.previousTransaction[offset] = 0;
  }
}
