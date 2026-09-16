package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.wal.local.LocalWalReadResult;

/** Retains and publishes one contiguous grouped relational WAL operation. */
final class IndexedRelationalWalRecovery {
  private final IndexedRelationalMutationBuffer mutations =
      new IndexedRelationalMutationBuffer(
          IndexedRelationalMutationBuffer.MAX_MUTATIONS,
          IndexedRelationalMutationBuffer.MAX_INDEX_DESCRIPTORS,
          Integer.MAX_VALUE);
  private final IndexedRelationalWalDecoder decoder =
      new IndexedRelationalWalDecoder(mutations);
  private final IndexedRelationalWalReplay replay;
  private long recordStart;
  private long firstJournalSequence;
  private long nextRecordStart;

  IndexedRelationalWalRecovery(IndexedRelationalWalReplay walReplay) {
    replay = walReplay;
  }

  boolean active() {
    return decoder.active();
  }

  StatusCode apply(
      long start,
      LocalWalReadResult record,
      long publishedCommitSequence,
      long coveredCommitSequence,
      long oldestVisibleCommitSequence,
      boolean recovery) {
    if (!validRecord(start, record, publishedCommitSequence, coveredCommitSequence)) {
      discard();
      return StatusCode.CORRUPTION;
    }
    int decision = record.header().decisionCode();
    long commitSequence = record.header().commitSequence();
    if (!decoder.active()) {
      recordStart = start;
      firstJournalSequence = record.header().journalSequence();
    }
    StatusCode status = decoder.decode(
        record.payload(), record.header().transactionId(), decision);
    if (!status.isOk()) {
      recordStart = 0;
      return status == StatusCode.RESOURCE_EXHAUSTED ? status : StatusCode.CORRUPTION;
    }
    if (!decoder.complete()) {
      nextRecordStart = record.nextOffset();
      return StatusCode.OK;
    }
    return completeRecord(
        record, commitSequence, publishedCommitSequence, coveredCommitSequence,
        oldestVisibleCommitSequence, recovery);
  }

  private boolean validRecord(
      long start, LocalWalReadResult record,
      long publishedCommitSequence, long coveredCommitSequence) {
    int decision = record.header().decisionCode();
    long commitSequence = record.header().commitSequence();
    return (decision == 0 || decision == 1)
        && (decision != 0 || commitSequence == 0)
        && (decision != 1 || commitSequence > 0)
        && (decision != 1 || commitSequence >= publishedCommitSequence
            || commitSequence <= coveredCommitSequence)
        && (!decoder.active() || start == nextRecordStart);
  }

  private StatusCode completeRecord(
      LocalWalReadResult record, long commitSequence,
      long publishedCommitSequence, long coveredCommitSequence,
      long oldestVisibleCommitSequence, boolean recovery) {
    if (commitSequence <= coveredCommitSequence
        || commitSequence == publishedCommitSequence) {
      discard();
      return StatusCode.OK;
    }
    StatusCode status = replay == null ? StatusCode.FEATURE_NOT_SUPPORTED : replay.apply(
        mutations, recordStart, record.recordEnd(), commitSequence,
        oldestVisibleCommitSequence, recovery);
    discard();
    return status;
  }

  void discard() {
    decoder.reset();
    recordStart = 0;
    firstJournalSequence = 0;
    nextRecordStart = 0;
  }

  long recordStart() {
    return recordStart;
  }

  long firstJournalSequence() {
    return firstJournalSequence;
  }
}
