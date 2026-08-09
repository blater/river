package io.riverdb.testkit.journal;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.JournalPosition;
import io.riverdb.journal.api.JournalProvider;
import io.riverdb.journal.api.NodeIncarnation;

/** Provider mechanics needed to drive the implementation-neutral semantic contract suite. */
public interface JournalProviderHarness {
  JournalProvider provider();

  StatusCode writeThrough(long journalGeneration, long inclusiveSequence);

  default StatusCode forceThrough(
      long journalGeneration,
      long inclusiveSequence,
      ForceCompletion completion) {
    return forceThrough(journalGeneration, inclusiveSequence, completion, 0);
  }

  StatusCode forceThrough(
      long journalGeneration,
      long inclusiveSequence,
      ForceCompletion completion,
      long nowNanos);

  default StatusCode crashAndRestart(NodeIncarnation restartedNode) {
    return crashAndRestart(restartedNode, UnknownRecoveryResolution.NOT_DURABLE, 0);
  }

  StatusCode crashAndRestart(
      NodeIncarnation restartedNode,
      UnknownRecoveryResolution unknownResolution,
      long nowNanos);

  StatusCode reclaimThrough(JournalPosition requestedInclusive, long nowNanos);

  int retainedEntries();
}
