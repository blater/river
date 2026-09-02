package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.format.wal.WalFileHeaderCodec;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalReadResult;

/** Owns validation and application of indexed WAL records during replay and forced vacuum. */
final class IndexedWalRecovery {
  private final LocalWal wal;
  private final IndexedRelationalWalRecovery relational;
  private final IndexedWalOperationRecovery indexed;
  private final LocalWalReadResult walReadResult = new LocalWalReadResult();
  private long recoveredCommitSequence;
  private long coveredCommitSequence;

  IndexedWalRecovery(
      LocalWal localWal,
      IndexedPageSet pageSet,
      IndexedTableKernel tableKernel,
      DatabaseIncarnation databaseIncarnation,
      IndexedStorePhase storePhase) {
    this(
        localWal, pageSet, tableKernel, databaseIncarnation, storePhase,
        new IndexedLogicalRowIdRegistry());
  }

  IndexedWalRecovery(
      LocalWal localWal,
      IndexedPageSet pageSet,
      IndexedTableKernel tableKernel,
      DatabaseIncarnation databaseIncarnation,
      IndexedStorePhase storePhase,
      IndexedLogicalRowIdRegistry logicalRowIds) {
    this(
        localWal,
        pageSet,
        tableKernel,
        databaseIncarnation,
        storePhase,
        new IndexedRelationalWalApplier(tableKernel, pageSet, logicalRowIds));
  }

  IndexedWalRecovery(
      LocalWal localWal,
      IndexedPageSet pageSet,
      IndexedTableKernel tableKernel,
      DatabaseIncarnation databaseIncarnation,
      IndexedStorePhase storePhase,
      IndexedRelationalWalReplay replay) {
    wal = localWal;
    relational = new IndexedRelationalWalRecovery(replay);
    indexed = new IndexedWalOperationRecovery(
        pageSet, tableKernel, databaseIncarnation, storePhase);
  }

  StatusCode recover(
      WalGeneration generation,
      boolean baseLoaded,
      long initialCommitSequence) {
    recoveredCommitSequence = initialCommitSequence;
    coveredCommitSequence = initialCommitSequence;
    try {
      long offset = WalFileHeaderCodec.HEADER_BYTES;
      while (offset < wal.tailEnd()) {
        StatusCode status = wal.read(offset, walReadResult);
        if (!status.isOk()) {
          return status;
        }
        int recordKind = IndexedWalRecordKinds.classify(walReadResult);
        if (relational.active() && recordKind != IndexedWalRecordKinds.RELATIONAL) {
          relational.discard();
          return StatusCode.CORRUPTION;
        }
        if (indexed.vacuumActive()
            && recordKind != IndexedWalRecordKinds.INDEXED) {
          return StatusCode.CORRUPTION;
        }
        if (recordKind < 0) return StatusCode.CORRUPTION;
        if (recordKind != IndexedWalRecordKinds.OTHER) {
          status = applyRecoveredRecord(offset, walReadResult, generation);
          if (!status.isOk()) {
            return status;
          }
        }
        offset = walReadResult.nextOffset();
      }
      StatusCode tailStatus;
      if (relational.active() && indexed.vacuumActive()) {
        return StatusCode.CORRUPTION;
      }
      if (relational.active()) {
        tailStatus = wal.truncateDecisionlessRecoveredSuffix(
            relational.recordStart(), relational.firstJournalSequence());
        if (!tailStatus.isOk()) return tailStatus;
      } else if (indexed.vacuumActive()) {
        tailStatus = wal.truncateDecisionlessRecoveredSuffix(
            indexed.vacuumRecordStart(), indexed.vacuumFirstJournalSequence());
        if (!tailStatus.isOk()) return tailStatus;
        tailStatus = cancelVacuumOperation();
        if (!tailStatus.isOk()) return tailStatus;
      } else {
        tailStatus = wal.completeRecovery();
        if (!tailStatus.isOk()) return tailStatus;
      }
      relational.discard();
      if (indexed.vacuumActive()) {
        StatusCode status = cancelVacuumOperation();
        if (!status.isOk()) return status;
      }
      return recoveredCommitSequence > initialCommitSequence || baseLoaded
          ? StatusCode.OK : StatusCode.CORRUPTION;
    } finally {
      relational.discard();
      coveredCommitSequence = 0;
    }
  }

  long recoveredCommitSequence() {
    return recoveredCommitSequence;
  }

  StatusCode applyOperation(
      long recordStart,
      LocalWalReadResult record,
      WalGeneration generation,
      long publishedCommitSequence,
      long oldestVisibleCommitSequence) {
    int recordKind = IndexedWalRecordKinds.classify(record);
    if (relational.active() && recordKind != IndexedWalRecordKinds.RELATIONAL) {
      relational.discard();
      return StatusCode.CORRUPTION;
    }
    if (indexed.vacuumActive() && recordKind != IndexedWalRecordKinds.INDEXED) {
      return StatusCode.CORRUPTION;
    }
    if (recordKind == IndexedWalRecordKinds.RELATIONAL) {
      return relational.apply(
          recordStart, record, publishedCommitSequence, coveredCommitSequence,
          oldestVisibleCommitSequence, false);
    }
    return recordKind == IndexedWalRecordKinds.INDEXED
        ? indexed.apply(recordStart, record, generation, publishedCommitSequence)
        : StatusCode.CORRUPTION;
  }

  StatusCode cancelVacuumOperation() {
    return indexed.cancelVacuum();
  }

  StatusCode applyRecoveredRecord(
      long offset,
      LocalWalReadResult record,
      WalGeneration generation) {
    int decisionCode = record.header().decisionCode();
    long commitSequence = record.header().commitSequence();
    if (decisionCode != 0 && decisionCode != 1) {
      return StatusCode.CORRUPTION;
    }
    if (decisionCode == 1 && commitSequence <= recoveredCommitSequence
        && (IndexedWalRecordKinds.classify(record) != IndexedWalRecordKinds.RELATIONAL
            || commitSequence > coveredCommitSequence)) {
      return StatusCode.CORRUPTION;
    }
    StatusCode status = applyRecoveredOperation(
        offset, record, generation, recoveredCommitSequence);
    if (status.isOk() && decisionCode == 1 && commitSequence > recoveredCommitSequence) {
      recoveredCommitSequence = commitSequence;
    }
    return status;
  }

  private StatusCode applyRecoveredOperation(
      long recordStart,
      LocalWalReadResult record,
      WalGeneration generation,
      long publishedCommitSequence) {
    int recordKind = IndexedWalRecordKinds.classify(record);
    if (recordKind == IndexedWalRecordKinds.RELATIONAL) {
      return relational.apply(
          recordStart, record, publishedCommitSequence, coveredCommitSequence,
          Long.MAX_VALUE, true);
    }
    return recordKind == IndexedWalRecordKinds.INDEXED
        ? indexed.apply(recordStart, record, generation, publishedCommitSequence)
        : StatusCode.CORRUPTION;
  }

}
