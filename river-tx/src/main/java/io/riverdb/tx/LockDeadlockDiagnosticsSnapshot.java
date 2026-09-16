package io.riverdb.tx;

/** Caller-owned bounded copy composed from the diagnostic data owners. */
public final class LockDeadlockDiagnosticsSnapshot {
  public static final int FINGERPRINT_VERSION = 1;
  final LockDeadlockDiagnosticsConfig config;
  final LockDeadlockSnapshotCounters counters;
  final LockDeadlockSnapshotSignatures signatures;
  final LockDeadlockSnapshotEvents events;
  final LockDeadlockSnapshotExemplars exemplars;
  final LockDeadlockSnapshotEdges edges;

  public LockDeadlockDiagnosticsSnapshot(LockDeadlockDiagnosticsConfig configuration) {
    if (configuration == null) throw new IllegalArgumentException("diagnostic config is required");
    config = configuration;
    counters = new LockDeadlockSnapshotCounters(config.maximumEpochs());
    signatures = new LockDeadlockSnapshotSignatures(config.signatureCapacity(), counters);
    events = new LockDeadlockSnapshotEvents(config.victimEventCapacity(), counters);
    exemplars = new LockDeadlockSnapshotExemplars(
        config.exemplarCapacity(), config.maximumCycleEdges(), counters);
    edges = new LockDeadlockSnapshotEdges(config.edgeCapacity());
  }

  public LockDeadlockSnapshotCounters counters() { return counters; }
  public LockDeadlockDiagnosticsConfig config() { return config; }
  public LockDeadlockSnapshotSignatures signatures() { return signatures; }
  public LockDeadlockSnapshotEvents events() { return events; }
  public LockDeadlockSnapshotExemplars exemplars() { return exemplars; }
  public LockDeadlockSnapshotEdges edges() { return edges; }

  public boolean validForDiagnosticGate() {
    if (!config.enabled() || counters.selfValidationFailures != 0
        || counters.fingerprintOverflows != 0 || counters.fingerprintCollisions != 0
        || counters.epochOverflows != 0 || counters.victimEventOverflows != 0
        || counters.cycleEdgeOverflows != 0 || counters.eventSequenceOverflows != 0
        || counters.victimEventCount != counters.totalVictimSelections
        || counters.victimTransactionOutcomes != counters.totalVictimSelections) return false;
    if (!validSignatureTotals()) return false;
    for (int index = 0; index < counters.victimEventCount; index++) {
      if (events.cleanupValid[index] == 0 || events.outcomeSequences[index] <= events.sequences[index]
          || events.outcomeStatuses[index] == 0) return false;
    }
    return true;
  }

  private boolean validSignatureTotals() {
    long selections = 0;
    long outcomes = 0;
    for (int index = 0; index < counters.signatureCount; index++) {
      selections = add(selections, signatures.victims[index]);
      outcomes = add(outcomes, signatures.outcomes[index]);
    }
    return selections == counters.totalVictimSelections
        && outcomes == counters.totalVictimSelections;
  }

  void copyFrom(LockDeadlockDiagnosticsSnapshot source) {
    if (!sameDimensions(source.config)) {
      throw new IllegalArgumentException("diagnostic snapshot capacity mismatch");
    }
    counters.copyFrom(source.counters);
    signatures.copyFrom(source.signatures);
    events.copyFrom(source.events);
    exemplars.copyFrom(source.exemplars);
    edges.copyFrom(source.edges);
  }

  boolean compatible(LockDeadlockDiagnosticsConfig other) {
    return other != null
        && other.maximumRetainedBytes() == config.maximumRetainedBytes()
        && other.retainedPayloadBytes() == config.retainedPayloadBytes()
        && sameDimensions(other);
  }

  private boolean sameDimensions(LockDeadlockDiagnosticsConfig other) {
    return other.maximumEpochs() == config.maximumEpochs()
        && other.signaturesPerEpoch() == config.signaturesPerEpoch()
        && other.victimEventsPerEpoch() == config.victimEventsPerEpoch()
        && other.exemplarsPerSignature() == config.exemplarsPerSignature()
        && other.maximumCycleEdges() == config.maximumCycleEdges();
  }

  static long add(long value, long delta) {
    return delta > Long.MAX_VALUE - value ? Long.MAX_VALUE : value + delta;
  }
}
