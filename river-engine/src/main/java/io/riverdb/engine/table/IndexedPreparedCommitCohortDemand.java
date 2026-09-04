package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Exact structural demand measured from one sealed commit-writer cohort. */
final class IndexedPreparedCommitCohortDemand {
  private int versionOperations;

  StatusCode measure(
      IndexedPreparedLogicalCommit[] preparedCommits,
      int count) {
    versionOperations = 0;
    if (preparedCommits == null || count <= 0 || count > preparedCommits.length) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < count; index++) {
      IndexedPreparedLogicalCommit prepared = preparedCommits[index];
      if (prepared == null || !prepared.valid()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int additional = prepared.admittedVersionOperations();
      if (additional < 0 || versionOperations > Integer.MAX_VALUE - additional) {
        return StatusCode.RESOURCE_EXHAUSTED;
      }
      versionOperations += additional;
    }
    return StatusCode.OK;
  }

  int versionOperations() { return versionOperations; }
}
