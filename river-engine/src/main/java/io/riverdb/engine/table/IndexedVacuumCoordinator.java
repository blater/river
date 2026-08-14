package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.wal.local.LocalWal;

/** Owns bounded vacuum admission and delegates its WAL rewrite protocol. */
final class IndexedVacuumCoordinator {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedStorePhase phase;
  private final IndexedVacuumWriter writer;

  IndexedVacuumCoordinator(
      LocalWal wal,
      IndexedTableKernel tableKernel,
      IndexedPageSet pageSet,
      IndexedStorePhase storePhase,
      IndexedWalRecovery recovery) {
    kernel = tableKernel;
    pages = pageSet;
    phase = storePhase;
    writer = new IndexedVacuumWriter(wal, kernel, recovery);
  }

  StatusCode commit(
      long transactionId,
      long commitSequence,
      long publishedCommitSequence,
      WalGeneration generation,
      IndexedVacuumResult result) {
    StatusCode status = status();
    return status.isOk()
        ? writer.commit(
            transactionId,
            commitSequence,
            publishedCommitSequence,
            generation,
            result)
        : status;
  }

  StatusCode status() {
    if (phase.operationActive()
        || phase.preparedInsertGroupActive()
        || !pages.isPresent(IndexedTableKernel.HEAP_PAGE_ID)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int retainedRows = kernel.indexedEntryCount();
    if (retainedRows < 0 || retainedRows > kernel.rowCount()) {
      return StatusCode.CORRUPTION;
    }
    if (retainedRows == kernel.rowCount()) {
      return StatusCode.CONFLICT;
    }
    int chunkCount = kernel.vacuumChunkCount();
    if (chunkCount < 0) {
      return StatusCode.CORRUPTION;
    }
    return chunkCount > 0 && chunkCount < LocalWal.MAX_PENDING_RECORDS
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  boolean failureFences() {
    return writer.failureFences();
  }

  long copiedBytes() {
    return writer.copiedBytes();
  }
}
