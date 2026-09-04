package io.riverdb.tx;

import io.riverdb.base.error.StatusCode;
import io.riverdb.tx.api.lock.LockMode;
import io.riverdb.tx.api.lock.LockScope;

/** Caller-owned bounded copy of generic deadlock diagnostics. */
public final class LockDeadlockDiagnosticsSnapshot {
  private static final StatusCode[] STATUS_CODES = StatusCode.values();
  private static final long[] NO_LONGS = new long[0];
  private static final int[] NO_INTS = new int[0];
  private static final byte[] NO_BYTES = new byte[0];
  final LockDeadlockDiagnosticsConfig config;
  final long[] epochs;
  final int[] epochSignatureCounts;
  final int[] epochVictimEventCounts;
  final long[] signatureEpochs;
  final long[] fingerprints;
  final long[] collisionGuards;
  final long[] signatureVictims;
  final long[] signatureOutcomes;
  final long[] signatureQueuedCancelled;
  final long[] signatureHoldingsReleased;
  final long[] signatureFirstSequences;
  final long[] signatureLastSequences;
  final int[] signatureExemplars;
  final long[] eventEpochs;
  final long[] eventSequences;
  final long[] eventOutcomeSequences;
  final long[] eventVictimSequences;
  final long[] eventFingerprints;
  final long[] eventTransactionIds;
  final long[] eventTransactionGenerations;
  final long[] eventStartOrders;
  final long[] eventDiagnosticTags;
  final long[] eventDiagnosticStepTags;
  final int[] eventSignatureIndexes;
  final int[] eventQueuedCancelled;
  final int[] eventHoldingsReleased;
  final byte[] eventCleanupValid;
  final byte[] eventOutcomeStatuses;
  final int[] exemplarSignatureIndexes;
  final int[] exemplarEventIndexes;
  final int[] exemplarEdgeCounts;
  final long[] edgeWaiterIds;
  final long[] edgeWaiterGenerations;
  final long[] edgeWaiterStartOrders;
  final long[] edgeWaiterTags;
  final long[] edgeWaiterStepTags;
  final long[] edgeBlockerIds;
  final long[] edgeBlockerGenerations;
  final long[] edgeBlockerStartOrders;
  final long[] edgeBlockerTags;
  final long[] edgeBlockerStepTags;
  final long[] edgeResourceNamespaces;
  final long[] edgeResourceLowerKeys;
  final long[] edgeResourceUpperNamespaces;
  final long[] edgeResourceUpperKeys;
  final long[] edgeResourceDigests;
  final long[] edgeBlockingResourceDigests;
  final long[] edgeWaiterQueueOrders;
  final long[] edgeBlockerQueueOrders;
  final byte[] edgeKinds;
  final byte[] edgePreconditions;
  final byte[] edgeScopes;
  final byte[] edgeRequestedModes;
  final byte[] edgeHeldModes;
  final byte[] edgeBlockerRequestedModes;
  final byte[] edgeWaiterQueueKinds;
  final byte[] edgeBlockerQueueKinds;
  final byte[] edgePredicateResults;
  long totalVictimSelections;
  long victimTransactionOutcomes;
  long queuedRequestsCancelled;
  long holdingsReleased;
  long selfValidationFailures;
  long fingerprintOverflows;
  long fingerprintCollisions;
  long epochOverflows;
  long victimEventOverflows;
  long exemplarOverflows;
  long cycleEdgeOverflows;
  long eventSequenceOverflows;
  long lastValidationFailureSequence;
  long lastValidationFailureEpoch;
  int epochCount;
  int signatureCount;
  int victimEventCount;
  int exemplarCount;

  public LockDeadlockDiagnosticsSnapshot(LockDeadlockDiagnosticsConfig config) {
    if (config == null) throw new IllegalArgumentException("diagnostic config is required");
    this.config = config;
    int signatures = config.signatureCapacity();
    int events = config.victimEventCapacity();
    int exemplars = config.exemplarCapacity();
    int edges = config.edgeCapacity();
    epochs = longs(config.maximumEpochs());
    epochSignatureCounts = ints(config.maximumEpochs());
    epochVictimEventCounts = ints(config.maximumEpochs());
    signatureEpochs = longs(signatures);
    fingerprints = longs(signatures);
    collisionGuards = longs(signatures);
    signatureVictims = longs(signatures);
    signatureOutcomes = longs(signatures);
    signatureQueuedCancelled = longs(signatures);
    signatureHoldingsReleased = longs(signatures);
    signatureFirstSequences = longs(signatures);
    signatureLastSequences = longs(signatures);
    signatureExemplars = ints(signatures);
    eventEpochs = longs(events);
    eventSequences = longs(events);
    eventOutcomeSequences = longs(events);
    eventVictimSequences = longs(events);
    eventFingerprints = longs(events);
    eventTransactionIds = longs(events);
    eventTransactionGenerations = longs(events);
    eventStartOrders = longs(events);
    eventDiagnosticTags = longs(events);
    eventDiagnosticStepTags = longs(events);
    eventSignatureIndexes = ints(events);
    eventQueuedCancelled = ints(events);
    eventHoldingsReleased = ints(events);
    eventCleanupValid = bytes(events);
    eventOutcomeStatuses = bytes(events);
    exemplarSignatureIndexes = ints(exemplars);
    exemplarEventIndexes = ints(exemplars);
    exemplarEdgeCounts = ints(exemplars);
    edgeWaiterIds = longs(edges);
    edgeWaiterGenerations = longs(edges);
    edgeWaiterStartOrders = longs(edges);
    edgeWaiterTags = longs(edges);
    edgeWaiterStepTags = longs(edges);
    edgeBlockerIds = longs(edges);
    edgeBlockerGenerations = longs(edges);
    edgeBlockerStartOrders = longs(edges);
    edgeBlockerTags = longs(edges);
    edgeBlockerStepTags = longs(edges);
    edgeResourceNamespaces = longs(edges);
    edgeResourceLowerKeys = longs(edges);
    edgeResourceUpperNamespaces = longs(edges);
    edgeResourceUpperKeys = longs(edges);
    edgeResourceDigests = longs(edges);
    edgeBlockingResourceDigests = longs(edges);
    edgeWaiterQueueOrders = longs(edges);
    edgeBlockerQueueOrders = longs(edges);
    edgeKinds = bytes(edges);
    edgePreconditions = bytes(edges);
    edgeScopes = bytes(edges);
    edgeRequestedModes = bytes(edges);
    edgeHeldModes = bytes(edges);
    edgeBlockerRequestedModes = bytes(edges);
    edgeWaiterQueueKinds = bytes(edges);
    edgeBlockerQueueKinds = bytes(edges);
    edgePredicateResults = bytes(edges);
  }

  public boolean enabled() { return config.enabled(); }
  public long maximumRetainedBytes() { return config.maximumRetainedBytes(); }
  public long retainedPayloadBytes() { return config.retainedPayloadBytes(); }
  public int maximumEpochs() { return config.maximumEpochs(); }
  public int signaturesPerEpoch() { return config.signaturesPerEpoch(); }
  public int victimEventsPerEpoch() { return config.victimEventsPerEpoch(); }
  public int exemplarsPerSignature() { return config.exemplarsPerSignature(); }
  public int maximumCycleEdges() { return config.maximumCycleEdges(); }
  public int fingerprintVersion() { return LockDeadlockDiagnostics.FINGERPRINT_VERSION; }
  public long totalVictimSelections() { return totalVictimSelections; }
  public long victimTransactionOutcomes() { return victimTransactionOutcomes; }
  public long queuedRequestsCancelled() { return queuedRequestsCancelled; }
  public long holdingsReleased() { return holdingsReleased; }
  public long selfValidationFailures() { return selfValidationFailures; }
  public long fingerprintOverflows() { return fingerprintOverflows; }
  public long fingerprintCollisions() { return fingerprintCollisions; }
  public long epochOverflows() { return epochOverflows; }
  public long victimEventOverflows() { return victimEventOverflows; }
  public long exemplarOverflows() { return exemplarOverflows; }
  public long cycleEdgeOverflows() { return cycleEdgeOverflows; }
  public long eventSequenceOverflows() { return eventSequenceOverflows; }
  public long lastValidationFailureSequence() { return lastValidationFailureSequence; }
  public long lastValidationFailureEpoch() { return lastValidationFailureEpoch; }
  public boolean validForDiagnosticGate() {
    if (!enabled() || selfValidationFailures != 0 || fingerprintOverflows != 0
        || fingerprintCollisions != 0 || epochOverflows != 0 || victimEventOverflows != 0
        || cycleEdgeOverflows != 0 || eventSequenceOverflows != 0
        || victimEventCount != totalVictimSelections
        || victimTransactionOutcomes != totalVictimSelections) return false;
    long signatureSelectionTotal = 0;
    long signatureOutcomeTotal = 0;
    for (int index = 0; index < signatureCount; index++) {
      signatureSelectionTotal = add(signatureSelectionTotal, signatureVictims[index]);
      signatureOutcomeTotal = add(signatureOutcomeTotal, signatureOutcomes[index]);
    }
    if (signatureSelectionTotal != totalVictimSelections
        || signatureOutcomeTotal != totalVictimSelections) return false;
    for (int index = 0; index < victimEventCount; index++) {
      if (eventCleanupValid[index] == 0 || eventOutcomeSequences[index] <= eventSequences[index]
          || eventOutcomeStatuses[index] == 0) return false;
    }
    return true;
  }

  public int epochCount() { return epochCount; }
  public long epochAt(int index) { return epochs[checked(index, epochCount)]; }
  public int signatureCount() { return signatureCount; }
  public long signatureEpochAt(int index) { return signatureEpochs[signature(index)]; }
  public long fingerprintAt(int index) { return fingerprints[signature(index)]; }
  public long collisionGuardAt(int index) { return collisionGuards[signature(index)]; }
  public long signatureVictimSelectionsAt(int index) {
    return signatureVictims[signature(index)];
  }
  public long signatureVictimOutcomesAt(int index) {
    return signatureOutcomes[signature(index)];
  }
  public long signatureQueuedRequestsCancelledAt(int index) {
    return signatureQueuedCancelled[signature(index)];
  }
  public long signatureHoldingsReleasedAt(int index) {
    return signatureHoldingsReleased[signature(index)];
  }
  public long signatureFirstEventSequenceAt(int index) {
    return signatureFirstSequences[signature(index)];
  }
  public long signatureLastEventSequenceAt(int index) {
    return signatureLastSequences[signature(index)];
  }
  public int signatureExemplarCountAt(int index) {
    return signatureExemplars[signature(index)];
  }

  public int victimEventCount() { return victimEventCount; }
  public long eventEpochAt(int index) { return eventEpochs[event(index)]; }
  public long eventSequenceAt(int index) { return eventSequences[event(index)]; }
  public long eventOutcomeSequenceAt(int index) {
    return eventOutcomeSequences[event(index)];
  }
  public long eventVictimSelectionSequenceAt(int index) {
    return eventVictimSequences[event(index)];
  }
  public long eventFingerprintAt(int index) { return eventFingerprints[event(index)]; }
  public long eventTransactionIdAt(int index) { return eventTransactionIds[event(index)]; }
  public long eventTransactionGenerationAt(int index) {
    return eventTransactionGenerations[event(index)];
  }
  public long eventTransactionStartOrderAt(int index) {
    return eventStartOrders[event(index)];
  }
  public long eventDiagnosticTagAt(int index) { return eventDiagnosticTags[event(index)]; }
  public long eventDiagnosticStepTagAt(int index) {
    return eventDiagnosticStepTags[event(index)];
  }
  public int eventSignatureIndexAt(int index) { return eventSignatureIndexes[event(index)]; }
  public int eventQueuedRequestsCancelledAt(int index) {
    return eventQueuedCancelled[event(index)];
  }
  public int eventHoldingsReleasedAt(int index) {
    return eventHoldingsReleased[event(index)];
  }
  public boolean eventCleanupValidAt(int index) { return eventCleanupValid[event(index)] != 0; }
  public StatusCode eventOutcomeStatusAt(int index) {
    int ordinal = Byte.toUnsignedInt(eventOutcomeStatuses[event(index)]) - 1;
    return ordinal < 0 ? null : STATUS_CODES[ordinal];
  }

  public int exemplarCount() { return exemplarCount; }
  public int exemplarSignatureIndexAt(int index) {
    return exemplarSignatureIndexes[exemplar(index)];
  }
  public int exemplarEventIndexAt(int index) { return exemplarEventIndexes[exemplar(index)]; }
  public int exemplarEdgeCountAt(int index) { return exemplarEdgeCounts[exemplar(index)]; }
  public int exemplarEdgeIndex(int exemplar, int edge) {
    int checkedExemplar = exemplar(exemplar);
    if (edge < 0 || edge >= exemplarEdgeCounts[checkedExemplar]) {
      throw new IndexOutOfBoundsException(edge);
    }
    return checkedExemplar * config.maximumCycleEdges() + edge;
  }

  public long edgeWaiterTransactionIdAt(int index) { return edgeWaiterIds[edge(index)]; }
  public long edgeWaiterTransactionGenerationAt(int index) {
    return edgeWaiterGenerations[edge(index)];
  }
  public long edgeWaiterStartOrderAt(int index) { return edgeWaiterStartOrders[edge(index)]; }
  public long edgeWaiterDiagnosticTagAt(int index) { return edgeWaiterTags[edge(index)]; }
  public long edgeWaiterDiagnosticStepTagAt(int index) {
    return edgeWaiterStepTags[edge(index)];
  }
  public long edgeBlockerTransactionIdAt(int index) { return edgeBlockerIds[edge(index)]; }
  public long edgeBlockerTransactionGenerationAt(int index) {
    return edgeBlockerGenerations[edge(index)];
  }
  public long edgeBlockerStartOrderAt(int index) { return edgeBlockerStartOrders[edge(index)]; }
  public long edgeBlockerDiagnosticTagAt(int index) { return edgeBlockerTags[edge(index)]; }
  public long edgeBlockerDiagnosticStepTagAt(int index) {
    return edgeBlockerStepTags[edge(index)];
  }
  public LockScope edgeResourceScopeAt(int index) {
    return LockExactTable.LOCK_SCOPES[Byte.toUnsignedInt(edgeScopes[edge(index)])];
  }
  public long edgeResourceNamespaceAt(int index) { return edgeResourceNamespaces[edge(index)]; }
  public long edgeResourceLowerKeyAt(int index) { return edgeResourceLowerKeys[edge(index)]; }
  public long edgeResourceUpperNamespaceAt(int index) {
    return edgeResourceUpperNamespaces[edge(index)];
  }
  public long edgeResourceUpperKeyAt(int index) { return edgeResourceUpperKeys[edge(index)]; }
  public long edgeResourceDigestAt(int index) { return edgeResourceDigests[edge(index)]; }
  public long edgeBlockingResourceDigestAt(int index) {
    return edgeBlockingResourceDigests[edge(index)];
  }
  public LockMode edgeRequestedModeAt(int index) {
    return LockExactTable.LOCK_MODES[Byte.toUnsignedInt(edgeRequestedModes[edge(index)])];
  }
  public LockMode edgeHeldModeAt(int index) {
    int ordinal = Byte.toUnsignedInt(edgeHeldModes[edge(index)]) - 1;
    return ordinal < 0 ? null : LockExactTable.LOCK_MODES[ordinal];
  }
  public LockMode edgeBlockerRequestedModeAt(int index) {
    int ordinal = Byte.toUnsignedInt(edgeBlockerRequestedModes[edge(index)]) - 1;
    return ordinal < 0 ? null : LockExactTable.LOCK_MODES[ordinal];
  }
  public LockQueueKind edgeWaiterQueueKindAt(int index) {
    return LockDeadlockDiagnostics.QUEUE_KINDS[
        Byte.toUnsignedInt(edgeWaiterQueueKinds[edge(index)])];
  }
  public LockQueueKind edgeBlockerQueueKindAt(int index) {
    return LockDeadlockDiagnostics.QUEUE_KINDS[
        Byte.toUnsignedInt(edgeBlockerQueueKinds[edge(index)])];
  }
  public long edgeWaiterQueueOrderAt(int index) { return edgeWaiterQueueOrders[edge(index)]; }
  public long edgeBlockerQueueOrderAt(int index) { return edgeBlockerQueueOrders[edge(index)]; }
  public LockDeadlockEdgeKind edgeKindAt(int index) {
    return LockDeadlockDiagnostics.EDGE_KINDS[Byte.toUnsignedInt(edgeKinds[edge(index)])];
  }
  public LockGrantPrecondition edgePreconditionAt(int index) {
    return LockDeadlockDiagnostics.PRECONDITIONS[
        Byte.toUnsignedInt(edgePreconditions[edge(index)])];
  }
  public boolean edgeGrantPredicateResultAt(int index) {
    return edgePredicateResults[edge(index)] != 0;
  }

  void copyFrom(LockDeadlockDiagnosticsSnapshot source) {
    if (source.config.maximumEpochs() != config.maximumEpochs()
        || source.config.signaturesPerEpoch() != config.signaturesPerEpoch()
        || source.config.victimEventsPerEpoch() != config.victimEventsPerEpoch()
        || source.config.exemplarsPerSignature() != config.exemplarsPerSignature()
        || source.config.maximumCycleEdges() != config.maximumCycleEdges()) {
      throw new IllegalArgumentException("diagnostic snapshot capacity mismatch");
    }
    totalVictimSelections = source.totalVictimSelections;
    victimTransactionOutcomes = source.victimTransactionOutcomes;
    queuedRequestsCancelled = source.queuedRequestsCancelled;
    holdingsReleased = source.holdingsReleased;
    selfValidationFailures = source.selfValidationFailures;
    fingerprintOverflows = source.fingerprintOverflows;
    fingerprintCollisions = source.fingerprintCollisions;
    epochOverflows = source.epochOverflows;
    victimEventOverflows = source.victimEventOverflows;
    exemplarOverflows = source.exemplarOverflows;
    cycleEdgeOverflows = source.cycleEdgeOverflows;
    eventSequenceOverflows = source.eventSequenceOverflows;
    lastValidationFailureSequence = source.lastValidationFailureSequence;
    lastValidationFailureEpoch = source.lastValidationFailureEpoch;
    epochCount = source.epochCount;
    signatureCount = source.signatureCount;
    victimEventCount = source.victimEventCount;
    exemplarCount = source.exemplarCount;
    copy(source.epochs, epochs);
    copy(source.epochSignatureCounts, epochSignatureCounts);
    copy(source.epochVictimEventCounts, epochVictimEventCounts);
    copy(source.signatureEpochs, signatureEpochs);
    copy(source.fingerprints, fingerprints);
    copy(source.collisionGuards, collisionGuards);
    copy(source.signatureVictims, signatureVictims);
    copy(source.signatureOutcomes, signatureOutcomes);
    copy(source.signatureQueuedCancelled, signatureQueuedCancelled);
    copy(source.signatureHoldingsReleased, signatureHoldingsReleased);
    copy(source.signatureFirstSequences, signatureFirstSequences);
    copy(source.signatureLastSequences, signatureLastSequences);
    copy(source.signatureExemplars, signatureExemplars);
    copy(source.eventEpochs, eventEpochs);
    copy(source.eventSequences, eventSequences);
    copy(source.eventOutcomeSequences, eventOutcomeSequences);
    copy(source.eventVictimSequences, eventVictimSequences);
    copy(source.eventFingerprints, eventFingerprints);
    copy(source.eventTransactionIds, eventTransactionIds);
    copy(source.eventTransactionGenerations, eventTransactionGenerations);
    copy(source.eventStartOrders, eventStartOrders);
    copy(source.eventDiagnosticTags, eventDiagnosticTags);
    copy(source.eventDiagnosticStepTags, eventDiagnosticStepTags);
    copy(source.eventSignatureIndexes, eventSignatureIndexes);
    copy(source.eventQueuedCancelled, eventQueuedCancelled);
    copy(source.eventHoldingsReleased, eventHoldingsReleased);
    copy(source.eventCleanupValid, eventCleanupValid);
    copy(source.eventOutcomeStatuses, eventOutcomeStatuses);
    copy(source.exemplarSignatureIndexes, exemplarSignatureIndexes);
    copy(source.exemplarEventIndexes, exemplarEventIndexes);
    copy(source.exemplarEdgeCounts, exemplarEdgeCounts);
    copy(source.edgeWaiterIds, edgeWaiterIds);
    copy(source.edgeWaiterGenerations, edgeWaiterGenerations);
    copy(source.edgeWaiterStartOrders, edgeWaiterStartOrders);
    copy(source.edgeWaiterTags, edgeWaiterTags);
    copy(source.edgeWaiterStepTags, edgeWaiterStepTags);
    copy(source.edgeBlockerIds, edgeBlockerIds);
    copy(source.edgeBlockerGenerations, edgeBlockerGenerations);
    copy(source.edgeBlockerStartOrders, edgeBlockerStartOrders);
    copy(source.edgeBlockerTags, edgeBlockerTags);
    copy(source.edgeBlockerStepTags, edgeBlockerStepTags);
    copy(source.edgeResourceNamespaces, edgeResourceNamespaces);
    copy(source.edgeResourceLowerKeys, edgeResourceLowerKeys);
    copy(source.edgeResourceUpperNamespaces, edgeResourceUpperNamespaces);
    copy(source.edgeResourceUpperKeys, edgeResourceUpperKeys);
    copy(source.edgeResourceDigests, edgeResourceDigests);
    copy(source.edgeBlockingResourceDigests, edgeBlockingResourceDigests);
    copy(source.edgeWaiterQueueOrders, edgeWaiterQueueOrders);
    copy(source.edgeBlockerQueueOrders, edgeBlockerQueueOrders);
    copy(source.edgeKinds, edgeKinds);
    copy(source.edgePreconditions, edgePreconditions);
    copy(source.edgeScopes, edgeScopes);
    copy(source.edgeRequestedModes, edgeRequestedModes);
    copy(source.edgeHeldModes, edgeHeldModes);
    copy(source.edgeBlockerRequestedModes, edgeBlockerRequestedModes);
    copy(source.edgeWaiterQueueKinds, edgeWaiterQueueKinds);
    copy(source.edgeBlockerQueueKinds, edgeBlockerQueueKinds);
    copy(source.edgePredicateResults, edgePredicateResults);
  }

  boolean compatible(LockDeadlockDiagnosticsConfig other) {
    return other != null
        && other.maximumRetainedBytes() == config.maximumRetainedBytes()
        && other.retainedPayloadBytes() == config.retainedPayloadBytes()
        && other.maximumEpochs() == config.maximumEpochs()
        && other.signaturesPerEpoch() == config.signaturesPerEpoch()
        && other.victimEventsPerEpoch() == config.victimEventsPerEpoch()
        && other.exemplarsPerSignature() == config.exemplarsPerSignature()
        && other.maximumCycleEdges() == config.maximumCycleEdges();
  }

  private int signature(int index) { return checked(index, signatureCount); }
  private int event(int index) { return checked(index, victimEventCount); }
  private int exemplar(int index) { return checked(index, exemplarCount); }
  private int edge(int index) {
    return checked(index, edgeWaiterIds.length);
  }
  private static int checked(int index, int count) {
    if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
    return index;
  }
  private static void copy(long[] source, long[] target) {
    System.arraycopy(source, 0, target, 0, source.length);
  }
  private static void copy(int[] source, int[] target) {
    System.arraycopy(source, 0, target, 0, source.length);
  }
  private static void copy(byte[] source, byte[] target) {
    System.arraycopy(source, 0, target, 0, source.length);
  }
  private static long add(long value, long delta) {
    return delta > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + delta;
  }
  private static long[] longs(int capacity) {
    return capacity == 0 ? NO_LONGS : new long[capacity];
  }
  private static int[] ints(int capacity) {
    return capacity == 0 ? NO_INTS : new int[capacity];
  }
  private static byte[] bytes(int capacity) {
    return capacity == 0 ? NO_BYTES : new byte[capacity];
  }
}
