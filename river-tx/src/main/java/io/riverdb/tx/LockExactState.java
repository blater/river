package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockRequest;
import io.riverdb.tx.api.lock.LockWaitHandle;
import io.riverdb.tx.api.lock.LockWaitState;

/** Typed exact-lock records and intrusive resource/transaction chains. */
final class LockExactState {
  final LockExactResourceStore resources;
  final LockExactTransactionStore transactions;
  final LockExactHoldingStore holdings;
  final LockExactRequestStore requests;
  final LockExactDirectory directory;
  final LockIntervalIndex intervals;

  LockExactState(LockSegmentArena arena, long seed) {
    resources = new LockExactResourceStore(arena);
    transactions = new LockExactTransactionStore(arena);
    holdings = new LockExactHoldingStore(arena);
    requests = new LockExactRequestStore(arena);
    directory = new LockExactDirectory(resources, transactions, holdings, requests, arena, seed);
    intervals = new LockIntervalIndex(resources, arena);
  }

  void initializeResource(long slot, LockRequest request) {
    LockExactResourceStore.Chunk chunk = resources.record(slot);
    int offset = LockTypedSlots.offset(slot);
    chunk.scopes[offset] = (byte) request.scope().ordinal();
    chunk.first[offset] = request.lowerSpace();
    chunk.second[offset] = request.lowerKey();
    chunk.third[offset] = request.upperSpace();
    chunk.fourth[offset] = request.upperKey();
    chunk.hashes[offset] = LockExactDirectory.resourceHash(request);
    if (LockTupleRequest.tuple(request)) resources.initializeTuple(slot, request);
  }

  void initializeTransaction(long slot, long id, long generation) {
    LockExactTransactionStore.Chunk chunk = transactions.record(slot);
    int offset = LockTypedSlots.offset(slot);
    chunk.transactionIds[offset] = id;
    chunk.transactionGenerations[offset] = generation;
  }

  void initializeHolding(
      long slot, long resource, long transaction,
      LockMode mode, long capability, long references) {
    LockExactHoldingStore.Chunk chunk = holdings.record(slot);
    int offset = LockTypedSlots.offset(slot);
    chunk.resources[offset] = resource;
    chunk.transactions[offset] = transaction;
    chunk.transactionRecordGenerations[offset] = transactions.generation(transaction);
    chunk.modes[offset] = (byte) mode.ordinal();
    chunk.capabilities[offset] = capability;
    chunk.references[offset] = references;
    chunk.active[offset] = 1;
  }

  void initializeReservedHolding(
      long slot, long resource, long transaction,
      LockMode mode, long capability, long references) {
    LockExactHoldingStore.Chunk chunk = holdings.record(slot);
    int offset = LockTypedSlots.offset(slot);
    chunk.resources[offset] = resource;
    chunk.transactions[offset] = transaction;
    chunk.transactionRecordGenerations[offset] = transactions.generation(transaction);
    chunk.modes[offset] = (byte) mode.ordinal();
    chunk.capabilities[offset] = capability;
    chunk.references[offset] = references;
    chunk.active[offset] = 0;
  }

  void activateHolding(long slot, LockMode mode) {
    LockExactHoldingStore.Chunk chunk = holdings.record(slot);
    int offset = LockTypedSlots.offset(slot);
    chunk.modes[offset] = (byte) mode.ordinal();
    chunk.active[offset] = 1;
    linkHolding(chunk.resources[offset], chunk.transactions[offset], slot);
  }

  void initializeRequest(
      long slot, long resource, long transaction, long holding,
      long laneId, long laneGeneration,
      long requestGeneration, long referenceGeneration,
      LockRequest request, LockWaitHandle handle, boolean conversion) {
    LockExactRequestStore.Chunk chunk = requests.record(slot);
    int offset = LockTypedSlots.offset(slot);
    chunk.resources[offset] = resource;
    chunk.transactions[offset] = transaction;
    chunk.holdings[offset] = holding;
    chunk.transactionRecordGenerations[offset] = transactions.generation(transaction);
    chunk.laneIds[offset] = laneId;
    chunk.laneGenerations[offset] = laneGeneration;
    chunk.requestGenerations[offset] = requestGeneration;
    chunk.referenceGenerations[offset] = referenceGeneration;
    chunk.deadlines[offset] = request.deadlineNanos();
    if (request.hasDeadline()) chunk.deadlinePresence[offset >>> 6] |= 1L << offset;
    else chunk.deadlinePresence[offset >>> 6] &= ~(1L << offset);
    if (conversion) chunk.conversions[offset >>> 6] |= 1L << offset;
    else chunk.conversions[offset >>> 6] &= ~(1L << offset);
    chunk.modes[offset] = (byte) request.mode().ordinal();
    chunk.states[offset] = (byte) LockWaitState.QUEUED.ordinal();
    chunk.handles[offset] = handle;
  }

  void linkHolding(long resource, long transaction, long holding) {
    LockExactResourceStore.Chunk resourceChunk = resources.record(resource);
    LockExactTransactionStore.Chunk transactionChunk = transactions.record(transaction);
    LockExactHoldingStore.Chunk holdingChunk = holdings.record(holding);
    int ro = LockTypedSlots.offset(resource);
    int to = LockTypedSlots.offset(transaction);
    int ho = LockTypedSlots.offset(holding);
    long firstResource = LockTypedSlots.decode(resourceChunk.ownerHeads[ro]);
    long firstTransaction = LockTypedSlots.decode(transactionChunk.holdingHeads[to]);
    holdingChunk.nextResource[ho] = LockTypedSlots.encode(firstResource);
    holdingChunk.nextTransaction[ho] = LockTypedSlots.encode(firstTransaction);
    if (firstResource >= 0) holdings.record(firstResource)
        .previousResource[LockTypedSlots.offset(firstResource)] = LockTypedSlots.encode(holding);
    if (firstTransaction >= 0) holdings.record(firstTransaction)
        .previousTransaction[LockTypedSlots.offset(firstTransaction)] = LockTypedSlots.encode(holding);
    resourceChunk.ownerHeads[ro] = LockTypedSlots.encode(holding);
    transactionChunk.holdingHeads[to] = LockTypedSlots.encode(holding);
    resourceChunk.ownerCounts[ro]++;
    if (holdingChunk.modes[ho] == LockMode.SHARED.ordinal()) resourceChunk.sharedCounts[ro]++;
    else if (holdingChunk.modes[ho] == LockMode.UPDATE.ordinal()) resourceChunk.updateCounts[ro]++;
  }

  void linkRequest(long resource, long transaction, long request) {
    LockExactResourceStore.Chunk resourceChunk = resources.record(resource);
    LockExactTransactionStore.Chunk transactionChunk = transactions.record(transaction);
    LockExactRequestStore.Chunk requestChunk = requests.record(request);
    int ro = LockTypedSlots.offset(resource);
    int to = LockTypedSlots.offset(transaction);
    int qo = LockTypedSlots.offset(request);
    if (requests.conversion(request)) {
      linkConversion(resourceChunk, requestChunk, ro, qo, request);
    } else {
      linkOrdinaryRequest(resourceChunk, requestChunk, ro, qo, request);
    }
    long first = LockTypedSlots.decode(transactionChunk.requestHeads[to]);
    requestChunk.nextTransaction[qo] = LockTypedSlots.encode(first);
    if (first >= 0) requests.record(first).previousTransaction[LockTypedSlots.offset(first)] =
        LockTypedSlots.encode(request);
    transactionChunk.requestHeads[to] = LockTypedSlots.encode(request);
  }

  private void linkConversion(
      LockExactResourceStore.Chunk resourceChunk,
      LockExactRequestStore.Chunk requestChunk,
      int resourceOffset,
      int requestOffset,
      long request) {
    long tail = LockTypedSlots.decode(resourceChunk.conversionTails[resourceOffset]);
    if (tail < 0) resourceChunk.conversionHeads[resourceOffset] = LockTypedSlots.encode(request);
    else requests.record(tail).nextConversion[LockTypedSlots.offset(tail)] =
        LockTypedSlots.encode(request);
    requestChunk.previousConversion[requestOffset] = LockTypedSlots.encode(tail);
    resourceChunk.conversionTails[resourceOffset] = LockTypedSlots.encode(request);
  }

  private void linkOrdinaryRequest(
      LockExactResourceStore.Chunk resourceChunk,
      LockExactRequestStore.Chunk requestChunk,
      int resourceOffset,
      int requestOffset,
      long request) {
    long tail = LockTypedSlots.decode(resourceChunk.waitTails[resourceOffset]);
    if (tail < 0) resourceChunk.waitHeads[resourceOffset] = LockTypedSlots.encode(request);
    else requests.record(tail).nextResource[LockTypedSlots.offset(tail)] =
        LockTypedSlots.encode(request);
    requestChunk.previousResource[requestOffset] = LockTypedSlots.encode(tail);
    resourceChunk.waitTails[resourceOffset] = LockTypedSlots.encode(request);
    int modeOffset = LockExactResourceStore.modeOffset(
        requestChunk.modes[requestOffset], resourceOffset);
    long modeTail = LockTypedSlots.decode(resourceChunk.modeWaitTails[modeOffset]);
    if (modeTail < 0) resourceChunk.modeWaitHeads[modeOffset] = LockTypedSlots.encode(request);
    else requests.record(modeTail).nextMode[LockTypedSlots.offset(modeTail)] =
        LockTypedSlots.encode(request);
    requestChunk.previousMode[requestOffset] = LockTypedSlots.encode(modeTail);
    resourceChunk.modeWaitTails[modeOffset] = LockTypedSlots.encode(request);
  }

  // Unlink operations are delegated to a smaller helper to keep this record class shallow.
}
