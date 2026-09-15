package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Cumulative demand and accepted-prefix result for one sealed writer cohort. */
final class IndexedPreparedCommitCohortDemand {
  private int candidateCount;
  private int acceptedCount;
  private int versionOperations;
  private boolean capacitySplit;
  private boolean memberRollbackSplit;
  private StatusCode splitStatus;

  StatusCode measure(
      IndexedPreparedLogicalCommit[] preparedCommits,
      int count,
      long availableVersionOperations) {
    reset();
    if (preparedCommits == null || count <= 0 || count > preparedCommits.length
        || availableVersionOperations < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    candidateCount = count;
    for (int index = 0; index < count; index++) {
      IndexedPreparedLogicalCommit prepared = preparedCommits[index];
      if (prepared == null || !prepared.valid()) return StatusCode.INVALID_EXTERNAL_INPUT;
      int additional = prepared.admittedVersionOperations();
      if (additional < 0) return StatusCode.RESOURCE_EXHAUSTED;
      if (additional > availableVersionOperations - versionOperations) {
        candidateCount = index;
        capacitySplit = true;
        splitStatus = StatusCode.RESOURCE_EXHAUSTED;
        break;
      }
      versionOperations += additional;
    }
    return StatusCode.OK;
  }

  void acceptMember() {
    acceptedCount++;
  }

  void split(StatusCode status) {
    capacitySplit = true;
    memberRollbackSplit = true;
    splitStatus = status;
    candidateCount = acceptedCount;
  }

  void rejectAll() {
    acceptedCount = 0;
    candidateCount = 0;
    capacitySplit = false;
    memberRollbackSplit = false;
    splitStatus = null;
  }

  void rejectHead(StatusCode status) {
    acceptedCount = 0;
    candidateCount = 0;
    capacitySplit = true;
    memberRollbackSplit = false;
    splitStatus = status;
  }

  int candidateCount() { return candidateCount; }
  int acceptedCount() { return acceptedCount; }
  int versionOperations() { return versionOperations; }
  boolean capacitySplit() { return capacitySplit; }
  boolean memberRollbackSplit() { return memberRollbackSplit; }
  StatusCode splitStatus() { return splitStatus; }

  private void reset() {
    candidateCount = 0;
    acceptedCount = 0;
    versionOperations = 0;
    capacitySplit = false;
    memberRollbackSplit = false;
    splitStatus = null;
  }
}
