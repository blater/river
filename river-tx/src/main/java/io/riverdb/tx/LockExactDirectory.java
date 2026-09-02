package io.riverdb.tx;

import io.riverdb.tx.api.lock.LockRequest;

/** Provider-seeded directories for exact resources, transactions and holdings. */
final class LockExactDirectory {
  private final LockExactResourceStore resources;
  private final LockExactTransactionStore transactions;
  private final LockExactHoldingStore holdings;
  private final LockExactRequestStore requests;
  private final LockTupleResourceIdentity tuples;
  final LockSlotIndex resourceIndex;
  final LockSlotIndex transactionIndex;
  final LockSlotIndex holdingIndex;
  final LockSlotIndex laneIndex;

  LockExactDirectory(
      LockExactResourceStore resourceStore,
      LockExactTransactionStore transactionStore,
      LockExactHoldingStore holdingStore,
      LockExactRequestStore requestStore,
      LockSegmentArena arena,
      long seed) {
    resources = resourceStore;
    transactions = transactionStore;
    holdings = holdingStore;
    requests = requestStore;
    tuples = new LockTupleResourceIdentity();
    resourceIndex = new LockSlotIndex(arena, seed);
    transactionIndex = new LockSlotIndex(arena, Long.rotateLeft(seed, 19));
    holdingIndex = new LockSlotIndex(arena, Long.rotateLeft(seed, 41));
    laneIndex = new LockSlotIndex(arena, Long.rotateLeft(seed, 53));
  }

  long resource(LockRequest request) {
    long hash = resourceHash(request);
    for (long slot = resourceIndex.first(hash); slot >= 0; slot = resourceIndex.next(slot)) {
      LockExactResourceStore.Chunk chunk = resources.record(slot);
      int offset = LockTypedSlots.offset(slot);
      if (resourceEquals(chunk, offset, request)) return slot;
    }
    return -1;
  }

  long transaction(long id, long generation) {
    long hash = transactionHash(id, generation);
    for (long slot = transactionIndex.first(hash); slot >= 0;
        slot = transactionIndex.next(slot)) {
      LockExactTransactionStore.Chunk chunk = transactions.record(slot);
      int offset = LockTypedSlots.offset(slot);
      if (chunk.transactionIds[offset] == id
          && chunk.transactionGenerations[offset] == generation) return slot;
    }
    return -1;
  }

  long holding(long resource, long id, long generation) {
    long hash = holdingHash(resource, id, generation);
    for (long slot = holdingIndex.first(hash); slot >= 0; slot = holdingIndex.next(slot)) {
      LockExactHoldingStore.Chunk chunk = holdings.record(slot);
      int offset = LockTypedSlots.offset(slot);
      long transaction = chunk.transactions[offset];
      if (chunk.resources[offset] != resource || !transactions.occupied(transaction)
          || transactions.generation(transaction) != chunk.transactionRecordGenerations[offset]) {
        continue;
      }
      LockExactTransactionStore.Chunk txChunk = transactions.record(transaction);
      int txOffset = LockTypedSlots.offset(transaction);
      if (txChunk.transactionIds[txOffset] == id
          && txChunk.transactionGenerations[txOffset] == generation) return slot;
    }
    return -1;
  }

  long lane(long id, long generation, long laneId, long laneGeneration) {
    long hash = laneHash(id, generation, laneId, laneGeneration);
    for (long slot = laneIndex.first(hash); slot >= 0; slot = laneIndex.next(slot)) {
      LockExactRequestStore.Chunk request = requests.record(slot);
      int offset = LockTypedSlots.offset(slot);
      long transaction = request.transactions[offset];
      if (!transactions.occupied(transaction)
          || transactions.generation(transaction) != request.transactionRecordGenerations[offset]
          || request.laneIds[offset] != laneId
          || request.laneGenerations[offset] != laneGeneration) continue;
      LockExactTransactionStore.Chunk tx = transactions.record(transaction);
      int txOffset = LockTypedSlots.offset(transaction);
      if (tx.transactionIds[txOffset] == id
          && tx.transactionGenerations[txOffset] == generation) return slot;
    }
    return -1;
  }

  static long resourceHash(LockRequest request) {
    if (LockTupleRequest.tuple(request)) return LockTupleResourceHash.hash(request);
    return resourceHash(request.scope().ordinal(), request.lowerSpace(), request.lowerKey(),
        request.upperSpace(), request.upperKey());
  }
  static long resourceHash(
      long scope, long first, long second, long third, long fourth) {
    return LockSlotIndex.hash(scope, first, second, third, fourth);
  }
  static long transactionHash(long id, long generation) {
    return LockSlotIndex.hash(id ^ Long.rotateLeft(generation, 29));
  }
  static long holdingHash(long resource, long id, long generation) {
    return LockSlotIndex.hash(resource, id, generation, 0, 0);
  }
  static long laneHash(long id, long generation, long laneId, long laneGeneration) {
    return LockSlotIndex.hash(id, generation, laneId, laneGeneration, 0);
  }

  private boolean resourceEquals(
      LockExactResourceStore.Chunk chunk, int offset, LockRequest request) {
    if (chunk.scopes[offset] != request.scope().ordinal()) return false;
    if (LockTupleRequest.tuple(request)) return tuples.equal(chunk, offset, request);
    return chunk.first[offset] == request.lowerSpace()
        && chunk.second[offset] == request.lowerKey()
        && chunk.third[offset] == request.upperSpace()
        && chunk.fourth[offset] == request.upperKey();
  }
}
