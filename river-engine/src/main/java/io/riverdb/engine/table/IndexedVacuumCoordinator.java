package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.wal.local.LocalWal;

/** Owns vacuum admission and delegates its bounded-batch WAL rewrite protocol. */
final class IndexedVacuumCoordinator {
  private final IndexedTableKernel kernel;
  private final IndexedPageSet pages;
  private final IndexedStorePhase phase;
  private final IndexedVacuumWriter writer;
  private final IndexedVacuumPublicationAdmission publicationAdmission;
  private final IndexedCountResult count = new IndexedCountResult();

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
    publicationAdmission = new IndexedVacuumPublicationAdmission(pages);
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
    if (unavailable()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = kernel.indexedEntryCount(count);
    if (!status.isOk()) return status;
    long retainedRows = count.value();
    if (retainedRows > kernel.rowCount()) return StatusCode.CORRUPTION;
    if (retainedRows == kernel.rowCount()) {
      return StatusCode.CONFLICT;
    }
    status = publicationAdmission.admit();
    if (!status.isOk()) return status;
    status = kernel.vacuumChunkCount(count);
    if (!status.isOk()) return status;
    long chunkCount = count.value();
    return chunkCount > 0 && chunkCount <= Integer.MAX_VALUE
        ? StatusCode.OK : StatusCode.RESOURCE_EXHAUSTED;
  }

  private boolean unavailable() {
    return phase.operationActive()
        || phase.commitGroupActive()
        || !pages.isPresent(IndexedTableKernel.HEAP_PAGE_ID);
  }

  boolean failureFences() {
    return writer.failureFences();
  }

  long copiedBytes() {
    return writer.copiedBytes();
  }
}
