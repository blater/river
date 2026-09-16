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
  private final LockDeadlockDiagnosticsCycleWorkspace cycle;

  LockDeadlockDiagnostics(LockExactTable owner, LockDeadlockDiagnosticsConfig configuration) {
    table = owner;
    validator = configuration.enabled() ? new LockExactCycleValidator(owner) : null;
    config = configuration;
    state = new LockDeadlockDiagnosticsSnapshot(configuration);
    cycle = new LockDeadlockDiagnosticsCycleWorkspace(configuration.maximumCycleEdges());
  }

  boolean prepareSelection(
      long ancestor,
      long current,
      LockExactBlockerCursor backEdge,
      long victim,
      long victimSelectionSequence) {
    if (!config.enabled()) {
      state.counters.totalVictimSelections = increment(state.counters.totalVictimSelections);
      bindSelection(victim, -1, -1);
      return true;
    }
    long epoch = metricsEpoch(victim);
    long eventSequence = nextEventSequence();
    if (eventSequence <= 0) state.counters.eventSequenceOverflows = increment(state.counters.eventSequenceOverflows);
    int edgeCount = edgeCount(ancestor, current);
    if (!selfValid(ancestor, current, backEdge)) {
      state.counters.selfValidationFailures = increment(state.counters.selfValidationFailures);
      state.counters.lastValidationFailureSequence = eventSequence;
      state.counters.lastValidationFailureEpoch = epoch;
      return false;
    }
    state.counters.totalVictimSelections = increment(state.counters.totalVictimSelections);
    if (edgeCount > config.maximumCycleEdges()) {
      state.counters.cycleEdgeOverflows = increment(state.counters.cycleEdgeOverflows);
      int event = admitEvent(epoch, eventSequence, victimSelectionSequence, 0, -1, victim);
      bindSelection(victim, -1, event);
      return true;
    }
    gather(ancestor, current, backEdge, edgeCount);
    int rotation = canonicalRotation(edgeCount);
    long fingerprint = fingerprint(cycle.shape, edgeCount, rotation, HASH_OFFSET);
    long collisionGuard = fingerprint(
        cycle.guardShape, edgeCount, rotation, HASH_OFFSET ^ 0x9e3779b97f4a7c15L);
    int signature = admitSignature(epoch, fingerprint, collisionGuard, eventSequence);
    int event = admitEvent(epoch, eventSequence, victimSelectionSequence,
        fingerprint, signature, victim);
    if (signature >= 0) admitExemplar(signature, event, edgeCount, rotation);
    bindSelection(victim, signature, event);
    return true;
  }

  void completeCleanup(long victim, int queuedCancelled, int released, boolean cleanupValid) {
    state.counters.queuedRequestsCancelled = add(state.counters.queuedRequestsCancelled, queuedCancelled);
    state.counters.holdingsReleased = add(state.counters.holdingsReleased, released);
    LockExactTransactionStore.Chunk transactions = table.state.transactions.record(victim);
    int offset = LockTypedSlots.offset(victim);
    int signature = decodeIndex(transactions.selectedSignatureIndexes[offset]);
    int event = decodeIndex(transactions.selectedEventIndexes[offset]);
    if (signature >= 0) {
      state.signatures.queuedCancelled[signature] = add(
          state.signatures.queuedCancelled[signature], queuedCancelled);
      state.signatures.holdingsReleased[signature] = add(
          state.signatures.holdingsReleased[signature], released);
    }
    if (event >= 0) {
      state.events.queuedCancelled[event] = queuedCancelled;
      state.events.holdingsReleased[event] = released;
      state.events.cleanupValid[event] = cleanupValid ? (byte) 1 : 0;
    }
  }

  void transactionOutcome(long transaction, StatusCode status) {
    if (!table.state.transactions.occupied(transaction)) return;
    LockExactTransactionStore.Chunk transactions = table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    long encodedSignature = transactions.selectedSignatureIndexes[offset];
    long encodedEvent = transactions.selectedEventIndexes[offset];
    if (encodedSignature == 0 && encodedEvent == 0) return;
    int signature = decodeIndex(encodedSignature);
    int event = decodeIndex(encodedEvent);
    state.counters.victimTransactionOutcomes = increment(state.counters.victimTransactionOutcomes);
    if (signature >= 0) {
      state.signatures.outcomes[signature] = increment(state.signatures.outcomes[signature]);
    }
    if (event >= 0) {
      long sequence = nextEventSequence();
      if (sequence <= 0) state.counters.eventSequenceOverflows = increment(state.counters.eventSequenceOverflows);
      state.events.outcomeSequences[event] = sequence;
      state.events.outcomeStatuses[event] = (byte) (status.ordinal() + 1);
    }
  }

  void snapshot(LockDeadlockDiagnosticsSnapshot target) { target.copyFrom(state); }

  boolean selfValidEdge(
      long waiter, long request, long blocker, long blockingResource,
      LockDeadlockEdgeKind kind, LockGrantPrecondition precondition) {
    return validator != null && validator.validEdge(waiter, request, blocker, blockingResource,
        (byte) kind.ordinal(), (byte) precondition.ordinal());
  }

  int admitSignature(long epoch, long fingerprint, long guard) {
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
    cycle.requests[index] = request;
    cycle.blockers[index] = blocker;
    cycle.blockingResources[index] = blockingResource;
    cycle.kinds[index] = kind;
    cycle.preconditions[index] = precondition;
    cycle.shape[index] = edgeShape(
        request, blocker, blockingResource, kind, precondition, false);
    cycle.guardShape[index] = edgeShape(
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
    } else {
      LockExactRequestStore.Chunk blockers = table.state.requests.record(blocker);
      hash = mix(hash, blockers.modes[LockTypedSlots.offset(blocker)]);
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
          cycle.shape[(left + index) % count], cycle.shape[(right + index) % count]);
      if (compared != 0) return compared < 0;
      compared = Long.compareUnsigned(
          cycle.guardShape[(left + index) % count], cycle.guardShape[(right + index) % count]);
      if (compared != 0) return compared < 0;
    }
    return false;
  }

  private int admitSignature(long epoch, long fingerprint, long guard, long sequence) {
    int epochIndex = epochIndex(epoch);
    if (epochIndex < 0) {
      state.counters.fingerprintOverflows = increment(state.counters.fingerprintOverflows);
      return -1;
    }
    for (int index = 0; index < state.counters.signatureCount; index++) {
      if (state.signatures.epochs[index] != epoch) continue;
      if (state.signatures.fingerprints[index] != fingerprint) continue;
      if (state.signatures.collisionGuards[index] != guard) {
        state.counters.fingerprintCollisions = increment(state.counters.fingerprintCollisions);
        state.counters.fingerprintOverflows = increment(state.counters.fingerprintOverflows);
        return -1;
      }
      state.signatures.victims[index] = increment(state.signatures.victims[index]);
      state.signatures.lastSequences[index] = sequence;
      return index;
    }
    if (state.counters.epochSignatureCounts[epochIndex] == config.signaturesPerEpoch()) {
      state.counters.fingerprintOverflows = increment(state.counters.fingerprintOverflows);
      return -1;
    }
    int empty = state.counters.signatureCount++;
    state.signatures.epochs[empty] = epoch;
    state.signatures.fingerprints[empty] = fingerprint;
    state.signatures.collisionGuards[empty] = guard;
    state.signatures.victims[empty] = 1;
    state.signatures.firstSequences[empty] = sequence;
    state.signatures.lastSequences[empty] = sequence;
    state.counters.epochSignatureCounts[epochIndex]++;
    return empty;
  }

  private int epochIndex(long epoch) {
    for (int index = 0; index < state.counters.epochCount; index++) {
      if (state.counters.epochs[index] == epoch) return index;
    }
    if (state.counters.epochCount == config.maximumEpochs()) {
      state.counters.epochOverflows = increment(state.counters.epochOverflows);
      return -1;
    }
    int admitted = state.counters.epochCount++;
    state.counters.epochs[admitted] = epoch;
    return admitted;
  }

  private int admitEvent(
      long epoch, long sequence, long victimSequence,
      long fingerprint, int signature, long victim) {
    int epochIndex = existingEpochIndex(epoch);
    if (epochIndex < 0 && state.counters.epochCount < config.maximumEpochs()) {
      epochIndex = epochIndex(epoch);
    }
    if (epochIndex < 0) {
      state.counters.victimEventOverflows = increment(state.counters.victimEventOverflows);
      return -1;
    }
    if (state.counters.epochVictimEventCounts[epochIndex] == config.victimEventsPerEpoch()
        || sequence <= 0) {
      state.counters.victimEventOverflows = increment(state.counters.victimEventOverflows);
      return -1;
    }
    int event = state.counters.victimEventCount++;
    LockExactTransactionStore.Chunk transactions = table.state.transactions.record(victim);
    int offset = LockTypedSlots.offset(victim);
    state.events.epochs[event] = epoch;
    state.events.sequences[event] = sequence;
    state.events.victimSequences[event] = victimSequence;
    state.events.fingerprints[event] = fingerprint;
    state.events.transactionIds[event] = transactions.transactionIds[offset];
    state.events.transactionGenerations[event] = transactions.transactionGenerations[offset];
    state.events.startOrders[event] = transactions.startOrders[offset];
    state.events.diagnosticTags[event] = transactions.diagnosticTags[offset];
    state.events.diagnosticStepTags[event] = transactions.diagnosticStepTags[offset];
    state.events.signatureIndexes[event] = signature;
    state.counters.epochVictimEventCounts[epochIndex]++;
    return event;
  }

  private void admitExemplar(int signature, int event, int edgeCount, int rotation) {
    if (config.exemplarsPerSignature() == 0) return;
    int count = state.signatures.exemplars[signature];
    if (count == config.exemplarsPerSignature()) {
      state.counters.exemplarOverflows = increment(state.counters.exemplarOverflows);
      return;
    }
    int exemplar = state.counters.exemplarCount++;
    state.signatures.exemplars[signature] = count + 1;
    state.exemplars.signatureIndexes[exemplar] = signature;
    state.exemplars.eventIndexes[exemplar] = event;
    state.exemplars.edgeCounts[exemplar] = edgeCount;
    int edgeBase = exemplar * config.maximumCycleEdges();
    for (int index = 0; index < edgeCount; index++) {
      int source = (rotation + index) % edgeCount;
      captureEdge(edgeBase + index, cycle.requests[source], cycle.blockers[source],
          cycle.blockingResources[source], cycle.kinds[source], cycle.preconditions[source]);
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
    state.edges.scopes[target] = resources.scopes[resourceOffset];
    state.edges.resourceNamespaces[target] = namespace(resources, resourceOffset);
    state.edges.resourceLowerKeys[target] = resources.second[resourceOffset];
    state.edges.resourceUpperNamespaces[target] = upperNamespace(resources, resourceOffset);
    state.edges.resourceUpperKeys[target] = resources.fourth[resourceOffset];
    state.edges.resourceDigests[target] = resources.hashes[resourceOffset];
    LockExactResourceStore.Chunk blocking = table.state.resources.record(blockingResource);
    state.edges.blockingResourceDigests[target] =
        blocking.hashes[LockTypedSlots.offset(blockingResource)];
    state.edges.requestedModes[target] = requests.modes[requestOffset];
    state.edges.heldModes[target] = 0;
    state.edges.blockerRequestedModes[target] = 0;
    if (kind == LockDeadlockEdgeKind.ACTIVE_OWNER.ordinal()) {
      LockExactHoldingStore.Chunk holdings = table.state.holdings.record(blocker);
      state.edges.heldModes[target] = (byte) (holdings.modes[LockTypedSlots.offset(blocker)] + 1);
    } else {
      LockExactRequestStore.Chunk blockers = table.state.requests.record(blocker);
      state.edges.blockerRequestedModes[target] =
          (byte) (blockers.modes[LockTypedSlots.offset(blocker)] + 1);
    }
    state.edges.waiterQueueKinds[target] = (byte) waiterQueueKind(request).ordinal();
    state.edges.blockerQueueKinds[target] = (byte) blockerQueueKind(kind, blocker).ordinal();
    state.edges.waiterQueueOrders[target] = requests.referenceGenerations[requestOffset];
    if (kind != LockDeadlockEdgeKind.ACTIVE_OWNER.ordinal()) {
      LockExactRequestStore.Chunk blockers = table.state.requests.record(blocker);
      state.edges.blockerQueueOrders[target] =
          blockers.referenceGenerations[LockTypedSlots.offset(blocker)];
    }
    state.edges.kinds[target] = kind;
    state.edges.preconditions[target] = precondition;
    state.edges.predicateResults[target] = 0;
  }

  private void captureTransaction(int target, long transaction, boolean waiter) {
    LockExactTransactionStore.Chunk transactions = table.state.transactions.record(transaction);
    int offset = LockTypedSlots.offset(transaction);
    if (waiter) {
      state.edges.waiterIds[target] = transactions.transactionIds[offset];
      state.edges.waiterGenerations[target] = transactions.transactionGenerations[offset];
      state.edges.waiterStartOrders[target] = transactions.startOrders[offset];
      state.edges.waiterTags[target] = transactions.diagnosticTags[offset];
      state.edges.waiterStepTags[target] = transactions.diagnosticStepTags[offset];
    } else {
      state.edges.blockerIds[target] = transactions.transactionIds[offset];
      state.edges.blockerGenerations[target] = transactions.transactionGenerations[offset];
      state.edges.blockerStartOrders[target] = transactions.startOrders[offset];
      state.edges.blockerTags[target] = transactions.diagnosticTags[offset];
      state.edges.blockerStepTags[target] = transactions.diagnosticStepTags[offset];
    }
  }

  private void bindSelection(long victim, int signature, int event) {
    LockExactTransactionStore.Chunk transactions = table.state.transactions.record(victim);
    int offset = LockTypedSlots.offset(victim);
    transactions.selectedSignatureIndexes[offset] = encodeIndex(signature);
    transactions.selectedEventIndexes[offset] = signature < 0 && event < 0
        ? -1 : encodeIndex(event);
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
    for (int index = 0; index < state.counters.epochCount; index++) {
      if (state.counters.epochs[index] == epoch) return index;
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
  private static int decodeIndex(long encoded) { return encoded <= 0 ? -1 : (int) (encoded - 1); }
  private static long increment(long value) {
    return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1;
  }
  private static long add(long value, long delta) {
    return delta > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + delta;
  }
}
