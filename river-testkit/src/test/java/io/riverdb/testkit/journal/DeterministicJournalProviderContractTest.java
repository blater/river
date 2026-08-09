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
          ForceCompletion completion) {
        return provider.forceThrough(journalGeneration, inclusiveSequence, completion);
      }

      @Override
      public StatusCode crashAndRestart(NodeIncarnation restartedNode) {
        return provider.crashAndRestart(restartedNode);
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
