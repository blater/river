package io.riverdb.testkit.journal;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.JournalPosition;
import io.riverdb.journal.api.JournalProvider;
import io.riverdb.journal.api.NodeIncarnation;

final class DeterministicJournalProviderContractTest extends JournalProviderContractTest {
  @Override
  protected JournalProviderHarness openHarness(
      int entryCapacity,
      int maxEntryBytes,
      int retentionLeaseCapacity,
      long maxLeaseDurationNanos) {
    DeterministicJournalProvider provider = new DeterministicJournalProvider(
        DATABASE,
        NODE,
        JOURNAL_GENERATION,
        7,
        entryCapacity,
        maxEntryBytes,
        retentionLeaseCapacity,
        maxLeaseDurationNanos,
        new FatalStateFence());
    return harness(provider);
  }

  @Override
  protected JournalProviderHarness openHarnessWithOutcomePolicy(
      int entryCapacity,
      int maxEntryBytes,
      int outcomeCapacity,
      long outcomeRetentionNanos) {
    DeterministicJournalProvider provider = new DeterministicJournalProvider(
        DATABASE,
        NODE,
        JOURNAL_GENERATION,
        7,
        entryCapacity,
        maxEntryBytes,
        4,
        1_000_000_000L,
        outcomeCapacity,
        outcomeRetentionNanos,
        new FatalStateFence());
    return harness(provider);
  }

  private JournalProviderHarness harness(DeterministicJournalProvider provider) {
    return new JournalProviderHarness() {
      @Override
      public JournalProvider provider() {
        return provider;
      }

      @Override
      public StatusCode writeThrough(long journalGeneration, long inclusiveSequence) {
        return provider.writeThrough(journalGeneration, inclusiveSequence);
      }

      @Override
      public StatusCode forceThrough(
          long journalGeneration,
          long inclusiveSequence,
          ForceCompletion completion,
          long nowNanos) {
        return provider.forceThrough(
            journalGeneration, inclusiveSequence, completion, nowNanos);
      }

      @Override
      public StatusCode crashAndRestart(
          NodeIncarnation restartedNode,
          UnknownRecoveryResolution unknownResolution,
          long nowNanos) {
        return provider.crashAndRestart(restartedNode, unknownResolution, nowNanos);
      }

      @Override
      public StatusCode reclaimThrough(
          JournalPosition requestedInclusive,
          long nowNanos) {
        return provider.reclaimThrough(requestedInclusive, nowNanos);
      }

      @Override
      public int retainedEntries() {
        return provider.retainedEntries();
      }
    };
  }
}
