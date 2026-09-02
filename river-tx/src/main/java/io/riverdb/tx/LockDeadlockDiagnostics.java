package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import java.util.concurrent.atomic.AtomicLong;

/** Fixed-storage deadlock aggregation and exemplars; invoked only when a cycle is found. */
final class LockDeadlockDiagnostics {
  static final int FINGERPRINT_VERSION = 1;
  static final LockDeadlockEdgeKind[] EDGE_KINDS = LockDeadlockEdgeKind.values();
  static final LockGrantPrecondition[] PRECONDITIONS = LockGrantPrecondition.values();
  static final LockQueueKind[] QUEUE_KINDS = LockQueueKind.values();
  private static final AtomicLong SERVER_EVENT_SEQUENCE = new AtomicLong();
  private static final long HASH_OFFSET = 0xcbf29ce484222325L;
  private static final long HASH_PRIME = 0x100000001b3L;

  private final LockExactTable table;
  private final LockExactCycleValidator validator;
  private final LockDeadlockDiagnosticsConfig config;
  private final LockDeadlockDiagnosticsSnapshot state;
  private final long[] cycleRequests;
  private final long[] cycleBlockers;
  private final long[] cycleBlockingResources;
  private final long[] cycleShape;
  private final long[] cycleGuardShape;
  private final byte[] cycleKinds;
  private final byte[] cyclePreconditions;

  LockDeadlockDiagnostics(LockExactTable owner, LockDeadlockDiagnosticsConfig configuration) {
    table = owner;
    validator = new LockExactCycleValidator(owner);
    config = configuration;
    state = new LockDeadlockDiagnosticsSnapshot(configuration);
    cycleRequests = new long[configuration.maximumCycleEdges()];
    cycleBlockers = new long[configuration.maximumCycleEdges()];
    cycleBlockingResources = new long[configuration.maximumCycleEdges()];
    cycleShape = new long[configuration.maximumCycleEdges()];
    cycleGuardShape = new long[configuration.maximumCycleEdges()];
    cycleKinds = new byte[configuration.maximumCycleEdges()];
    cyclePreconditions = new byte[configuration.maximumCycleEdges()];
  }

  boolean prepareSelection(
      long ancestor,
      long current,
      LockExactBlockerCursor backEdge,
      long victim,
      long victimSelectionSequence) {
    long epoch = metricsEpoch(victim);
    long eventSequence = nextEventSequence();
    if (eventSequence <= 0) state.eventSequenceOverflows = increment(state.eventSequenceOverflows);
    int edgeCount = edgeCount(ancestor, current);
    if (!selfValid(ancestor, current, backEdge)) {
      state.selfValidationFailures = increment(state.selfValidationFailures);
      state.lastValidationFailureSequence = eventSequence;
      state.lastValidationFailureEpoch = epoch;
      return false;
    }
    if (!config.enabled()) return true;
    state.totalVictimSelections = increment(state.totalVictimSelections);
    if (edgeCount > config.maximumCycleEdges()) {
      state.cycleEdgeOverflows = increment(state.cycleEdgeOverflows);
      int event = admitEvent(epoch, eventSequence, victimSelectionSequence, 0, -1, victim);
      bindSelection(victim, -1, event);
      return true;
    }
    gather(ancestor, current, backEdge, edgeCount);
    int rotation = canonicalRotation(edgeCount);
    long fingerprint = fingerprint(cycleShape, edgeCount, rotation, HASH_OFFSET);
    long collisionGuard = fingerprint(
        cycleGuardShape, edgeCount, rotation, HASH_OFFSET ^ 0x9e3779b97f4a7c15L);
    int signature = admitSignature(epoch, fingerprint, collisionGuard, eventSequence);
    int event = admitEvent(epoch, eventSequence, victimSelectionSequence,
        fingerprint, signature, victim);
    if (signature >= 0) admitExemplar(signature, event, edgeCount, rotation);
    bindSelection(victim, signature, event);
    return true;
  }

  void completeCleanup(long victim, int queuedCancelled, int released, boolean cleanupValid) {
    if (!config.enabled()) return;
    state.queuedRequestsCancelled = add(state.queuedRequestsCancelled, queuedCancelled);
    state.holdingsReleased = add(state.holdingsReleased, released);
    LockExactTransactionStore.Chunk transactions = table.state.transactions.record(victim);
    int offset = LockTypedSlots.offset(victim);
    int signature = decodeIndex(transactions.selectedSignatureIndexes[offset]);
    int event = decodeIndex(transactions.selectedEventIndexes[offset]);
    if (signature >= 0) {
      state.signatureQueuedCancelled[signature] = add(
          state.signatureQueuedCancelled[signature], queuedCancelled);
      state.signatureHoldingsReleased[signature] = add(
          state.signatureHoldingsReleased[signature], released);
    }
    if (event >= 0) {
      state.eventQueuedCancelled[event] = queuedCancelled;
      state.eventHoldingsReleased[event] = released;
      state.eventCleanupValid[event] = cleanupValid ? (byte) 1 : 0;
    }
  }

  void transactionOutcome(long transaction, StatusCode status) {
    if (!config.enabled() || !table.state.transactions.occupied(transaction)) return;
    LockExactTransactionStore.Chunk transactions = table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    int signature = decodeIndex(transactions.selectedSignatureIndexes[offset]);
    int event = decodeIndex(transactions.selectedEventIndexes[offset]);
    if (signature < 0 && event < 0) return;
    state.victimTransactionOutcomes = increment(state.victimTransactionOutcomes);
    if (signature >= 0) {
      state.signatureOutcomes[signature] = increment(state.signatureOutcomes[signature]);
    }
    if (event >= 0) {
      long sequence = nextEventSequence();
      if (sequence <= 0) state.eventSequenceOverflows = increment(state.eventSequenceOverflows);
      state.eventOutcomeSequences[event] = sequence;
      state.eventOutcomeStatuses[event] = (byte) (status.ordinal() + 1);
    }
  }

  void snapshot(LockDeadlockDiagnosticsSnapshot target) { target.copyFrom(state); }

  boolean selfValidEdge(
      long waiter, long request, long blocker, long blockingResource,
      LockDeadlockEdgeKind kind, LockGrantPrecondition precondition) {
    return validator.validEdge(waiter, request, blocker, blockingResource,
        (byte) kind.ordinal(), (byte) precondition.ordinal());
  }

  int admitSignatureForTest(long epoch, long fingerprint, long guard) {
    return admitSignature(epoch, fingerprint, guard, nextEventSequence());
  }

  private boolean selfValid(
      long ancestor, long current, LockExactBlockerCursor backEdge) {
    if (!validEdge(current, ancestor, backEdge.edgeRequest(), backEdge.edgeBlockerRecord(),
        backEdge.edgeBlockingResource(), backEdge.edgeKind(), backEdge.edgePrecondition())) {
      return false;
    }
    long child = current;
    while (child != ancestor) {
      if (!table.state.transactions.occupied(child)) return false;
      LockExactTransactionStore.Chunk transactions = table.state.transactions.record(child);
      int offset = LockTypedSlots.offset(child);
      long parent = LockTypedSlots.decode(transactions.parents[offset]);
      if (parent < 0 || !validEdge(parent, child, transactions.parentRequests[offset],
          transactions.parentBlockerRecords[offset],
          transactions.parentBlockingResources[offset],
          transactions.parentEdgeKinds[offset], transactions.parentPreconditions[offset])) {
        return false;
      }
      child = parent;
    }
    return true;
  }

  private boolean validEdge(
      long waiter,
      long expectedBlocker,
      long request,
      long blocker,
      long blockingResource,
      byte kind,
      byte precondition) {
    return validator.validEdge(waiter, request, blocker, blockingResource, kind, precondition)
        && validator.blockerTransaction(blocker, kind) == expectedBlocker;
  }

  private int edgeCount(long ancestor, long current) {
    int count = 1;
    for (long cursor = current; cursor != ancestor; cursor = parent(cursor)) {
      if (count == Integer.MAX_VALUE || cursor < 0) return Integer.MAX_VALUE;
      count++;
    }
    return count;
  }

  private void gather(
      long ancestor, long current, LockExactBlockerCursor backEdge, int count) {
    int index = count - 1;
    setCycleEdge(index, backEdge.edgeRequest(), backEdge.edgeBlockerRecord(),
        backEdge.edgeBlockingResource(), backEdge.edgeKind(), backEdge.edgePrecondition());
    long child = current;
    while (child != ancestor) {
      LockExactTransactionStore.Chunk transactions = table.state.transactions.record(child);
      int offset = LockTypedSlots.offset(child);
      setCycleEdge(--index, transactions.parentRequests[offset],
          transactions.parentBlockerRecords[offset],
          transactions.parentBlockingResources[offset],
          transactions.parentEdgeKinds[offset], transactions.parentPreconditions[offset]);
      child = LockTypedSlots.decode(transactions.parents[offset]);
    }
  }

  private void setCycleEdge(
      int index, long request, long blocker, long blockingResource,
      byte kind, byte precondition) {
    cycleRequests[index] = request;
    cycleBlockers[index] = blocker;
    cycleBlockingResources[index] = blockingResource;
    cycleKinds[index] = kind;
    cyclePreconditions[index] = precondition;
    cycleShape[index] = edgeShape(
        request, blocker, blockingResource, kind, precondition, false);
    cycleGuardShape[index] = edgeShape(
        request, blocker, blockingResource, kind, precondition, true);
  }

  private long edgeShape(
      long request, long blocker, long blockingResource,
      byte kind, byte precondition, boolean guard) {
    LockExactRequestStore.Chunk requests = table.state.requests.record(request);
    int requestOffset = LockTypedSlots.offset(request);
    long requestedResource = requests.resources[requestOffset];
    LockExactResourceStore.Chunk resources = table.state.resources.record(requestedResource);
    LockExactResourceStore.Chunk blocking = table.state.resources.record(blockingResource);
    int resourceOffset = LockTypedSlots.offset(requestedResource);
    int blockingOffset = LockTypedSlots.offset(blockingResource);
    long hash = guard ? 0x6a09e667f3bcc909L : 0xbb67ae8584caa73bL;
    hash = mix(hash, kind);
    hash = mix(hash, precondition);
    hash = mix(hash, resources.scopes[resourceOffset]);
    hash = mix(hash, namespace(resources, resourceOffset));
    hash = mix(hash, upperNamespace(resources, resourceOffset));
    hash = mix(hash, blocking.scopes[blockingOffset]);
    hash = mix(hash, namespace(blocking, blockingOffset));
    hash = mix(hash, requests.modes[requestOffset]);
    hash = mix(hash, waiterQueueKind(request).ordinal());
    hash = mix(hash, blockerQueueKind(kind, blocker).ordinal());
    if (kind == LockDeadlockEdgeKind.ACTIVE_OWNER.ordinal()) {
      LockExactHoldingStore.Chunk holdings = table.state.holdings.record(blocker);
      hash = mix(hash, holdings.modes[LockTypedSlots.offset(blocker)]);
    }
    return avalanche(hash);
  }

  private int canonicalRotation(int count) {
    int best = 0;
    for (int candidate = 1; candidate < count; candidate++) {
      if (rotationBefore(candidate, best, count)) best = candidate;
    }
    return best;
  }

  private boolean rotationBefore(int left, int right, int count) {
    for (int index = 0; index < count; index++) {
      int compared = Long.compareUnsigned(
          cycleShape[(left + index) % count], cycleShape[(right + index) % count]);
      if (compared != 0) return compared < 0;
      compared = Long.compareUnsigned(
          cycleGuardShape[(left + index) % count], cycleGuardShape[(right + index) % count]);
      if (compared != 0) return compared < 0;
    }
    return false;
  }

  private int admitSignature(long epoch, long fingerprint, long guard, long sequence) {
    int epochIndex = epochIndex(epoch);
    if (epochIndex < 0) {
      state.fingerprintOverflows = increment(state.fingerprintOverflows);
      return -1;
    }
    for (int index = 0; index < state.signatureCount; index++) {
      if (state.signatureEpochs[index] != epoch) continue;
      if (state.fingerprints[index] != fingerprint) continue;
      if (state.collisionGuards[index] != guard) {
        state.fingerprintCollisions = increment(state.fingerprintCollisions);
        state.fingerprintOverflows = increment(state.fingerprintOverflows);
        return -1;
      }
      state.signatureVictims[index] = increment(state.signatureVictims[index]);
      state.signatureLastSequences[index] = sequence;
      return index;
    }
    if (state.epochSignatureCounts[epochIndex] == config.signaturesPerEpoch()) {
      state.fingerprintOverflows = increment(state.fingerprintOverflows);
      return -1;
    }
    int empty = state.signatureCount++;
    state.signatureEpochs[empty] = epoch;
    state.fingerprints[empty] = fingerprint;
    state.collisionGuards[empty] = guard;
    state.signatureVictims[empty] = 1;
    state.signatureFirstSequences[empty] = sequence;
    state.signatureLastSequences[empty] = sequence;
    state.epochSignatureCounts[epochIndex]++;
    return empty;
  }

  private int epochIndex(long epoch) {
    for (int index = 0; index < state.epochCount; index++) {
      if (state.epochs[index] == epoch) return index;
    }
    if (state.epochCount == config.maximumEpochs()) {
      state.epochOverflows = increment(state.epochOverflows);
      return -1;
    }
    int admitted = state.epochCount++;
    state.epochs[admitted] = epoch;
    return admitted;
  }

  private int admitEvent(
      long epoch, long sequence, long victimSequence,
      long fingerprint, int signature, long victim) {
    int epochIndex = existingEpochIndex(epoch);
    if (epochIndex < 0 && state.epochCount < config.maximumEpochs()) {
      epochIndex = epochIndex(epoch);
    }
    if (epochIndex < 0) {
      state.victimEventOverflows = increment(state.victimEventOverflows);
      return -1;
    }
    if (state.epochVictimEventCounts[epochIndex] == config.victimEventsPerEpoch()
        || sequence <= 0) {
      state.victimEventOverflows = increment(state.victimEventOverflows);
      return -1;
    }
    int event = state.victimEventCount++;
    LockExactTransactionStore.Chunk transactions = table.state.transactions.record(victim);
    int offset = LockTypedSlots.offset(victim);
    state.eventEpochs[event] = epoch;
    state.eventSequences[event] = sequence;
    state.eventVictimSequences[event] = victimSequence;
    state.eventFingerprints[event] = fingerprint;
    state.eventTransactionIds[event] = transactions.transactionIds[offset];
    state.eventTransactionGenerations[event] = transactions.transactionGenerations[offset];
    state.eventStartOrders[event] = transactions.startOrders[offset];
    state.eventDiagnosticTags[event] = transactions.diagnosticTags[offset];
    state.eventSignatureIndexes[event] = signature;
    state.epochVictimEventCounts[epochIndex]++;
    return event;
  }

  private void admitExemplar(int signature, int event, int edgeCount, int rotation) {
    int count = state.signatureExemplars[signature];
    if (count == config.exemplarsPerSignature()) {
      state.exemplarOverflows = increment(state.exemplarOverflows);
      return;
    }
    int exemplar = state.exemplarCount++;
    state.signatureExemplars[signature] = count + 1;
    state.exemplarSignatureIndexes[exemplar] = signature;
    state.exemplarEventIndexes[exemplar] = event;
    state.exemplarEdgeCounts[exemplar] = edgeCount;
    int edgeBase = exemplar * config.maximumCycleEdges();
    for (int index = 0; index < edgeCount; index++) {
      int source = (rotation + index) % edgeCount;
      captureEdge(edgeBase + index, cycleRequests[source], cycleBlockers[source],
          cycleBlockingResources[source], cycleKinds[source], cyclePreconditions[source]);
    }
  }

  private void captureEdge(
      int target, long request, long blocker, long blockingResource,
      byte kind, byte precondition) {
    LockExactRequestStore.Chunk requests = table.state.requests.record(request);
    int requestOffset = LockTypedSlots.offset(request);
    long waiter = requests.transactions[requestOffset];
    long blockerTransaction = validator.blockerTransaction(blocker, kind);
    captureTransaction(target, waiter, true);
    captureTransaction(target, blockerTransaction, false);
    long resource = requests.resources[requestOffset];
    LockExactResourceStore.Chunk resources = table.state.resources.record(resource);
    int resourceOffset = LockTypedSlots.offset(resource);
    state.edgeScopes[target] = resources.scopes[resourceOffset];
    state.edgeResourceNamespaces[target] = namespace(resources, resourceOffset);
    state.edgeResourceLowerKeys[target] = resources.second[resourceOffset];
    state.edgeResourceUpperNamespaces[target] = upperNamespace(resources, resourceOffset);
    state.edgeResourceUpperKeys[target] = resources.fourth[resourceOffset];
    state.edgeResourceDigests[target] = resources.hashes[resourceOffset];
    LockExactResourceStore.Chunk blocking = table.state.resources.record(blockingResource);
    state.edgeBlockingResourceDigests[target] =
        blocking.hashes[LockTypedSlots.offset(blockingResource)];
    state.edgeRequestedModes[target] = requests.modes[requestOffset];
    state.edgeHeldModes[target] = 0;
    if (kind == LockDeadlockEdgeKind.ACTIVE_OWNER.ordinal()) {
      LockExactHoldingStore.Chunk holdings = table.state.holdings.record(blocker);
      state.edgeHeldModes[target] = (byte) (holdings.modes[LockTypedSlots.offset(blocker)] + 1);
    }
    state.edgeWaiterQueueKinds[target] = (byte) waiterQueueKind(request).ordinal();
    state.edgeBlockerQueueKinds[target] = (byte) blockerQueueKind(kind, blocker).ordinal();
    state.edgeWaiterQueueOrders[target] = requests.referenceGenerations[requestOffset];
    if (kind != LockDeadlockEdgeKind.ACTIVE_OWNER.ordinal()) {
      LockExactRequestStore.Chunk blockers = table.state.requests.record(blocker);
      state.edgeBlockerQueueOrders[target] =
          blockers.referenceGenerations[LockTypedSlots.offset(blocker)];
    }
    state.edgeKinds[target] = kind;
    state.edgePreconditions[target] = precondition;
    state.edgePredicateResults[target] = 0;
  }

  private void captureTransaction(int target, long transaction, boolean waiter) {
    LockExactTransactionStore.Chunk transactions = table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    if (waiter) {
      state.edgeWaiterIds[target] = transactions.transactionIds[offset];
      state.edgeWaiterGenerations[target] = transactions.transactionGenerations[offset];
      state.edgeWaiterStartOrders[target] = transactions.startOrders[offset];
      state.edgeWaiterTags[target] = transactions.diagnosticTags[offset];
    } else {
      state.edgeBlockerIds[target] = transactions.transactionIds[offset];
      state.edgeBlockerGenerations[target] = transactions.transactionGenerations[offset];
      state.edgeBlockerStartOrders[target] = transactions.startOrders[offset];
      state.edgeBlockerTags[target] = transactions.diagnosticTags[offset];
    }
  }

  private void bindSelection(long victim, int signature, int event) {
    LockExactTransactionStore.Chunk transactions = table.state.transactions.record(victim);
    int offset = LockTypedSlots.offset(victim);
    transactions.selectedSignatureIndexes[offset] = encodeIndex(signature);
    transactions.selectedEventIndexes[offset] = encodeIndex(event);
  }

  private long metricsEpoch(long transaction) {
    return table.state.transactions.record(transaction)
        .metricsEpochs[LockTypedSlots.offset(transaction)];
  }

  private long parent(long transaction) {
    return LockTypedSlots.decode(table.state.transactions.record(transaction)
        .parents[LockTypedSlots.offset(transaction)]);
  }

  private int existingEpochIndex(long epoch) {
    for (int index = 0; index < state.epochCount; index++) {
      if (state.epochs[index] == epoch) return index;
    }
    return -1;
  }

  private LockQueueKind waiterQueueKind(long request) {
    return table.state.requests.conversion(request)
        ? LockQueueKind.CONVERSION : LockQueueKind.ORDINARY;
  }

  private LockQueueKind blockerQueueKind(byte kind, long blocker) {
    return kind == LockDeadlockEdgeKind.ACTIVE_OWNER.ordinal()
        ? LockQueueKind.ACTIVE_OWNER
        : kind == LockDeadlockEdgeKind.CONVERSION_PRIORITY.ordinal()
            ? LockQueueKind.CONVERSION
            : table.state.requests.conversion(blocker)
                ? LockQueueKind.CONVERSION : LockQueueKind.ORDINARY;
  }

  private static long namespace(LockExactResourceStore.Chunk resources, int offset) {
    return LockExactResourceStore.tupleScope(resources.scopes[offset])
        ? resources.tupleNamespaces[offset] : resources.first[offset];
  }

  private static long upperNamespace(LockExactResourceStore.Chunk resources, int offset) {
    return LockExactResourceStore.tupleScope(resources.scopes[offset])
        ? resources.tupleNamespaces[offset] : resources.third[offset];
  }

  private static long fingerprint(long[] shape, int count, int rotation, long seed) {
    long hash = mix(mix(seed, FINGERPRINT_VERSION), count);
    for (int index = 0; index < count; index++) {
      hash = mix(hash, shape[(rotation + index) % count]);
    }
    return avalanche(hash);
  }

  private static long mix(long hash, long value) {
    hash ^= value;
    return hash * HASH_PRIME;
  }

  private static long avalanche(long value) {
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdL;
    value ^= value >>> 33;
    value *= 0xc4ceb9fe1a85ec53L;
    return value ^ value >>> 33;
  }

  private static long nextEventSequence() {
    while (true) {
      long current = SERVER_EVENT_SEQUENCE.get();
      if (current == Long.MAX_VALUE) return -1;
      if (SERVER_EVENT_SEQUENCE.compareAndSet(current, current + 1)) return current + 1;
    }
  }

  private static long encodeIndex(int index) { return index < 0 ? 0 : (long) index + 1; }
  private static int decodeIndex(long encoded) { return encoded == 0 ? -1 : (int) (encoded - 1); }
  private static long increment(long value) {
    return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1;
  }
  private static long add(long value, long delta) {
    return delta > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + delta;
  }
}
