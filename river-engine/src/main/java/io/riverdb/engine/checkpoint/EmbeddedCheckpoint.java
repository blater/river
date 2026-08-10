package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.page.IndexedPageStore;
import io.riverdb.engine.table.IndexedTable;
import io.riverdb.engine.table.IndexedVacuumResult;
import io.riverdb.platform.file.DirectoryOperationResult;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.tx.TransactionCommitParticipant;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import io.riverdb.format.wal.WalFileHeaderCodec;

/** Quiescent checkpoint participant that installs a stable page base before truncating WAL. */
public final class EmbeddedCheckpoint implements TransactionCommitParticipant {
  private final TransactionManager manager;
  private final DurableDirectory directory;
  private final LocalWal wal;
  private final IndexedPageStore store;
  private final IndexedTable table;
  private final CheckpointControlStore control;
  private final CheckpointState state = new CheckpointState();
  private final IndexedVacuumResult vacuumResult = new IndexedVacuumResult();
  private final TransactionOutcome outcome = new TransactionOutcome();
  private final DirectoryOperationResult directoryResult = new DirectoryOperationResult();
  private long lastCheckpointId;
  private long committedSequence;
  private CheckpointResult activeResult;
  private boolean fenced;

  public EmbeddedCheckpoint(
      TransactionManager transactionManager,
      DurableDirectory durableDirectory,
      LocalWal localWal,
      IndexedPageStore pageStore,
      IndexedTable indexedTable,
      CheckpointControlStore checkpointControl,
      long checkpointId) {
    manager = transactionManager;
    directory = durableDirectory;
    wal = localWal;
    store = pageStore;
    table = indexedTable;
    control = checkpointControl;
    lastCheckpointId = checkpointId;
  }

  public StatusCode run(CheckpointResult result) {
    if (result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    if (fenced) {
      return StatusCode.FENCED;
    }
    committedSequence = 0;
    vacuumResult.reset();
    outcome.reset();
    activeResult = result;
    StatusCode status = manager.commitMaintenance(this, outcome);
    activeResult = null;
    return status;
  }

  public boolean isFenced() {
    return fenced;
  }

  @Override
  public StatusCode commit(long transactionId) {
    if (activeResult == null || lastCheckpointId == Long.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    long previousGeneration = wal.walGeneration().value();
    if (previousGeneration == Long.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    String previousFileName = wal.fileName();
    WalGeneration nextGeneration = WalGeneration.of(previousGeneration + 1);
    String nextFileName = LocalWal.generationFileName(nextGeneration);
    long nextCheckpointId = lastCheckpointId + 1;

    StatusCode status = table.vacuumPreflight();
    boolean vacuumed = false;
    if (status.isOk()) {
      status = table.vacuum(transactionId, vacuumResult);
      vacuumed = status.isOk();
    } else if (status == StatusCode.CONFLICT
        || status == StatusCode.RESOURCE_EXHAUSTED) {
      status = StatusCode.OK;
    }
    if (!status.isOk()) {
      return status;
    }
    committedSequence = table.currentCommitSequence();
    if (committedSequence <= 0) {
      fenced = true;
      return StatusCode.INVARIANT_BROKEN;
    }
    long previousBytes = wal.tailEnd();
    status = table.flush();
    if (!status.isOk()) {
      fenced = vacuumed;
      return status;
    }
    status = removeStaleTarget(nextFileName, nextGeneration);
    if (!status.isOk()) {
      fenced = vacuumed;
      return status;
    }
    status = store.rebaseForCheckpoint(nextGeneration);
    if (!status.isOk()) {
      fenced = true;
      return status;
    }
    status = wal.rotate(directory, nextFileName, nextGeneration, transactionId);
    if (!status.isOk()) {
      fenced = true;
      return status;
    }
    status = store.captureCheckpointState(
        state, nextCheckpointId, wal.maximumTransactionId());
    if (status.isOk()) {
      status = control.install(directory, state);
    }
    if (!status.isOk()) {
      fenced = true;
      return status;
    }
    lastCheckpointId = nextCheckpointId;
    boolean retained = !removeObsoleteFile(previousFileName).isOk();
    if (lastCheckpointId > 1) {
      StatusCode baseCleanup = removeObsoleteFile(
          IndexedPageStore.checkpointFileName(WalGeneration.of(previousGeneration)));
      retained |= !baseCleanup.isOk();
    }
    activeResult.set(
        nextCheckpointId,
        previousGeneration,
        nextGeneration.value(),
        previousBytes,
        wal.tailEnd(),
        committedSequence,
        state.pageCount(),
        state.rowCount(),
        vacuumed ? vacuumResult.rowsReclaimed() : 0,
        retained);
    return StatusCode.OK;
  }

  @Override
  public long committedSequence() {
    return committedSequence;
  }

  private StatusCode removeStaleTarget(
      String nextFileName,
      WalGeneration nextGeneration) {
    LocalWalOpenResult opened = new LocalWalOpenResult();
    StatusCode status = LocalWal.openExistingNamed(
        directory,
        nextFileName,
        wal.databaseIncarnation(),
        nextGeneration,
        opened);
    if (status == StatusCode.CONFLICT) {
      return StatusCode.OK;
    }
    if (!status.isOk()) {
      return status;
    }
    LocalWal stale = opened.wal();
    if (stale.tailEnd() != WalFileHeaderCodec.HEADER_BYTES) {
      stale.close();
      return StatusCode.CORRUPTION;
    }
    status = stale.close();
    if (status.isOk()) {
      status = directory.remove(nextFileName, directoryResult);
    }
    if (status.isOk()) {
      status = directory.force(directoryResult);
    }
    return status;
  }

  private StatusCode removeObsoleteFile(String fileName) {
    StatusCode status = directory.remove(fileName, directoryResult);
    if (status == StatusCode.CONFLICT) {
      return StatusCode.OK;
    }
    if (status.isOk()) {
      status = directory.force(directoryResult);
    }
    return status;
  }
}
