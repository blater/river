package io.riverdb.testkit.journal;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.JournalPosition;
import io.riverdb.journal.api.JournalProvider;
import io.riverdb.journal.api.NodeIncarnation;

/** Provider mechanics needed to drive the implementation-neutral semantic contract suite. */
public interface JournalProviderHarness {
  JournalProvider provider();

  StatusCode writeThrough(long journalGeneration, long inclusiveSequence);

  StatusCode forceThrough(
      long journalGeneration,
      long inclusiveSequence,
      ForceCompletion completion);

  StatusCode crashAndRestart(NodeIncarnation restartedNode);

  StatusCode reclaimThrough(JournalPosition requestedInclusive, long nowNanos);

  int retainedEntries();
}
