package io.riverdb.tx;

/** Commit frontier sampled while the transaction publication barrier is held. */
public interface CommitSequenceSource {
  long currentCommitSequence();
}
