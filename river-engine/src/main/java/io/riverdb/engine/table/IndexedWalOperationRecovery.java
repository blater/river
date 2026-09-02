package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.wal.local.LocalWalReadResult;
import java.nio.ByteBuffer;

/** Dispatches one validated format-1002 indexed-table WAL operation. */
final class IndexedWalOperationRecovery {
  private final IndexedTableKernel kernel;
  private final IndexedPageWalRecovery pages;
  private final IndexedVacuumWalRecovery vacuum;

  IndexedWalOperationRecovery(
      IndexedPageSet pageSet, IndexedTableKernel table, DatabaseIncarnation database,
      IndexedStorePhase phase) {
    kernel = table;
    pages = new IndexedPageWalRecovery(pageSet, table, database);
    vacuum = new IndexedVacuumWalRecovery(pageSet, table, phase);
  }

  StatusCode apply(
      long start, LocalWalReadResult record, WalGeneration generation, long published) {
    ByteBuffer payload = record.payload();
    if (record.header().payloadBytes() < IndexedWalCodec.PAGE_OPERATION_HEADER_BYTES
        || !IndexedWalCodec.hasCommonHeader(payload)) return StatusCode.CORRUPTION;
    int operation = IndexedWalCodec.operationType(payload);
    if (vacuum.active() && operation != IndexedWalCodec.OPERATION_TYPE_VACUUM_CHUNK
        && operation != IndexedWalCodec.OPERATION_TYPE_VACUUM_COMMIT) {
      return StatusCode.CORRUPTION;
    }
    int decision = record.header().decisionCode();
    long commit = record.header().commitSequence();
    if (operation == IndexedWalCodec.OPERATION_TYPE_VACUUM_CHUNK
        || operation == IndexedWalCodec.OPERATION_TYPE_VACUUM_COMMIT) {
      return vacuum.apply(operation, decision, payload, start, record, commit, published);
    }
    if (decision != 1) return StatusCode.CORRUPTION;
    return operation == IndexedWalCodec.OPERATION_TYPE_PAGE_IMAGES
        ? pages.apply(payload, start, record.nextOffset(), commit, generation)
        : StatusCode.CORRUPTION;
  }

  boolean vacuumActive() { return vacuum.active(); }
  StatusCode cancelVacuum() { return vacuum.cancel(); }
  long vacuumRecordStart() { return vacuum.recordStart(); }
  long vacuumFirstJournalSequence() { return vacuum.firstJournalSequence(); }
}
