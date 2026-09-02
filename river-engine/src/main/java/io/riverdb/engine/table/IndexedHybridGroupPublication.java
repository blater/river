package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;

/** Installs already-compiled immutable member generations, then publishes one frontier token. */
final class IndexedHybridGroupPublication {
  private final IndexedTableStore store;
  private final IndexedPreparedCommitInstaller installer;
  private long preparedCommitSequence;

  IndexedHybridGroupPublication(
      IndexedTableStore table, IndexedTableKernel tableKernel,
      IndexedPageSet pageSet, IndexedLogicalRowIdRegistry logicalRowIdRegistry) {
    store = table;
    installer = new IndexedPreparedCommitInstaller(
        tableKernel, pageSet, logicalRowIdRegistry);
  }

  StatusCode prepare(
      IndexedRelationalWalGroupAppender wal,
      IndexedRelationalMutationBuffer[] mutations,
      long[] sequences,
      long[] rowEnds,
      int[] heapPageEnds,
      int count,
      long groupBaseRow) {
    StatusCode status = preparedCommitSequence == 0 && wal != null && wal.forced()
        ? installer.install(
            mutations, sequences, rowEnds, heapPageEnds, count, groupBaseRow,
            wal.start(), wal.end(), false)
        : StatusCode.INVALID_EXTERNAL_INPUT;
    if (!status.isOk()) {
      store.failed = true;
      return status;
    }
    preparedCommitSequence = sequences[count - 1];
    return StatusCode.OK;
  }

  StatusCode install(IndexedRelationalWalGroupAppender wal) {
    if (preparedCommitSequence <= store.lastCommitSequence || !wal.forced()) {
      return StatusCode.INVARIANT_BROKEN;
    }
    StatusCode status = wal.release();
    if (!status.isOk()) {
      store.failed = true;
      return status;
    }
    store.lastCommitSequence = preparedCommitSequence;
    preparedCommitSequence = 0;
    return StatusCode.OK;
  }

  void reset() { preparedCommitSequence = 0; }

}
