package io.riverdb.wal.local;

import io.riverdb.base.error.StatusCode;

/** Validates one follower against the primary before it joins fixed membership. */
final class LocalWalQuorumMembership {
  private LocalWalQuorumMembership() {}

  static StatusCode validate(LocalWal primary, LocalWal follower) {
    if (follower == null || follower == primary || follower.hasDurableQuorum()
        || follower.hasOpenLogicalStream()
        || !primary.databaseIncarnation().equals(follower.databaseIncarnation())
        || !primary.walGeneration().equals(follower.walGeneration())
        || primary.tailEnd() != follower.tailEnd()
        || primary.durableEnd() != follower.durableEnd()
        || primary.nextJournalSequence() != follower.nextJournalSequence()
        || primary.currentCommitSequence() != follower.currentCommitSequence()) {
      return StatusCode.CONFLICT;
    }
    return primary.appendState().groupDigest() == follower.appendState().groupDigest()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }
}
