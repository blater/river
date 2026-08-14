package io.riverdb.engine;

import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.checkpoint.CheckpointControlStore;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.engine.control.DatabaseControlResult;
import io.riverdb.engine.control.DatabaseControlStore;
import io.riverdb.engine.table.IndexedTable;
import io.riverdb.engine.table.IndexedTableOpenResult;
import io.riverdb.engine.table.IndexedTableStore;
import io.riverdb.engine.table.IndexedTableStoreOpenResult;
import io.riverdb.format.control.ControlFile;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalOpenResult;
import java.nio.file.Path;

/** Owns resources acquired while creating or reopening an embedded database. */
final class EmbeddedDatabaseOpener {
  private static final int MAXIMUM_ACTIVE_TRANSACTIONS = 1024;
  private static final int MAXIMUM_FOLLOWERS = 6;
  private static final NioDurableDirectory[] NO_FOLLOWER_DIRECTORIES =
      new NioDurableDirectory[0];
  private static final LocalWal[] NO_FOLLOWER_WALS = new LocalWal[0];

  private final Path directoryPath;
  private final DatabaseIncarnation database;
  private final WalGeneration generation;
  private final int maximumActiveTransactions;
  private final boolean create;
  private final Path[] followerDirectoryPaths;
  private final int requiredDurableNodes;
  private final boolean replicated;
  private final DatabaseControlResult databaseControl = new DatabaseControlResult();
  private final CheckpointControlStore checkpointControl = new CheckpointControlStore();
  private final CheckpointState checkpointState = new CheckpointState();
  private final IndexedTableStoreOpenResult storeResult = new IndexedTableStoreOpenResult();
  private final IndexedTableOpenResult tableResult = new IndexedTableOpenResult();

  private NioDurableDirectory directory;
  private NioDurableDirectory[] followerDirectories;
  private LocalWal wal;
  private LocalWal[] followerWals;
  private boolean checkpointAvailable;

  private EmbeddedDatabaseOpener(
      Path requestedDirectory,
      DatabaseIncarnation requestedDatabase,
      WalGeneration requestedGeneration,
      int requestedMaximumTransactions,
      boolean requestedCreate,
      Path[] requestedFollowerPaths,
      int requestedDurableNodes) {
    directoryPath = requestedDirectory;
    database = requestedDatabase;
    generation = requestedGeneration;
    maximumActiveTransactions = requestedMaximumTransactions;
    create = requestedCreate;
    followerDirectoryPaths = requestedFollowerPaths;
    requiredDurableNodes = requestedDurableNodes;
    replicated = requestedFollowerPaths != null;
  }

  static StatusCode open(
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      boolean create,
      Path[] followerDirectoryPaths,
      int requiredDurableNodes,
      EmbeddedDatabaseOpenResult result) {
    boolean replicated = followerDirectoryPaths != null;
    if (!validRequest(
        directoryPath,
        database,
        generation,
        maximumActiveTransactions,
        followerDirectoryPaths,
        requiredDurableNodes,
        replicated)
        || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    return new EmbeddedDatabaseOpener(
        directoryPath,
        database,
        generation,
        maximumActiveTransactions,
        create,
        followerDirectoryPaths,
        requiredDurableNodes).open(result);
  }

  private static boolean validRequest(
      Path directoryPath,
      DatabaseIncarnation database,
      WalGeneration generation,
      int maximumActiveTransactions,
      Path[] followerDirectoryPaths,
      int requiredDurableNodes,
      boolean replicated) {
    if (directoryPath == null
        || database == null
        || !database.isValid()
        || generation == null
        || !generation.isValid()
        || maximumActiveTransactions <= 0
        || maximumActiveTransactions > MAXIMUM_ACTIVE_TRANSACTIONS) {
      return false;
    }
    if (!replicated) {
      return requiredDurableNodes == 1;
    }
    return followerDirectoryPaths.length > 0
        && followerDirectoryPaths.length <= MAXIMUM_FOLLOWERS
        && requiredDurableNodes >= 2
        && requiredDurableNodes <= followerDirectoryPaths.length + 1;
  }

  private StatusCode open(EmbeddedDatabaseOpenResult result) {
    StatusCode status = openDirectory();
    if (status.isOk()) {
      status = validateAuthority();
    }
    if (status.isOk()) {
      status = openWal();
    }
    if (status.isOk()) {
      status = openFollowers();
    }
    if (status.isOk()) {
      status = openStore();
    }
    if (status.isOk()) {
      status = openTable();
    }
    if (status.isOk()) {
      status = createControlFile();
    }
    if (!status.isOk()) {
      closeAcquiredResources();
      return status;
    }
    result.set(new EmbeddedDatabase(
        directory,
        followerDirectories,
        wal,
        followerWals,
        storeResult.store(),
        tableResult.table(),
        maximumActiveTransactions,
        checkpointControl,
        checkpointAvailable ? checkpointState.checkpointId() : 0));
    return StatusCode.OK;
  }

  private StatusCode openDirectory() {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    StatusCode status = NioDurableDirectory.openExisting(
        directoryPath, new FatalStateFence(), new NioIoCounters(), 16, result);
    if (status.isOk()) {
      directory = result.directory();
    }
    return status;
  }

  private StatusCode validateAuthority() {
    StatusCode controlStatus = DatabaseControlStore.open(directory, databaseControl);
    StatusCode checkpointStatus = checkpointControl.read(directory, checkpointState);
    if (create) {
      return validateNewAuthority(controlStatus, checkpointStatus);
    }
    return validateExistingAuthority(controlStatus, checkpointStatus);
  }

  private static StatusCode validateNewAuthority(
      StatusCode controlStatus,
      StatusCode checkpointStatus) {
    if (controlStatus.isOk()) {
      return StatusCode.CONFLICT;
    }
    if (controlStatus != StatusCode.CONFLICT) {
      return controlStatus;
    }
    if (checkpointStatus.isOk()) {
      return StatusCode.CONFLICT;
    }
    return checkpointStatus == StatusCode.CONFLICT ? StatusCode.OK : checkpointStatus;
  }

  private StatusCode validateExistingAuthority(
      StatusCode controlStatus,
      StatusCode checkpointStatus) {
    if (!controlStatus.isOk()) {
      return controlStatus;
    }
    ControlFile control = databaseControl.controlFile();
    if (!database.equals(control.databaseIncarnation())
        || !generation.equals(control.walGeneration())) {
      return StatusCode.FENCED;
    }
    if (checkpointStatus.isOk()) {
      if (!database.equals(checkpointState.database())) {
        return StatusCode.FENCED;
      }
      checkpointAvailable = true;
      return StatusCode.OK;
    }
    return checkpointStatus == StatusCode.CONFLICT ? StatusCode.OK : checkpointStatus;
  }

  private StatusCode openWal() {
    LocalWalOpenResult result = new LocalWalOpenResult();
    StatusCode status;
    if (create) {
      status = LocalWal.create(directory, database, generation, result);
    } else if (checkpointAvailable) {
      status = LocalWal.openExistingNamed(
          directory,
          LocalWal.generationFileName(checkpointState.walGeneration()),
          database,
          checkpointState.walGeneration(),
          result);
    } else {
      status = LocalWal.openExisting(directory, database, generation, result);
    }
    if (!status.isOk()) {
      return checkpointAvailable && status == StatusCode.CONFLICT
          ? StatusCode.CORRUPTION : status;
    }
    wal = result.wal();
    if (!checkpointAvailable) {
      return StatusCode.OK;
    }
    return wal.adoptCheckpointState(
        checkpointState.commitSequence(), checkpointState.maximumTransactionId());
  }

  private StatusCode openFollowers() {
    if (!replicated) {
      followerDirectories = NO_FOLLOWER_DIRECTORIES;
      followerWals = NO_FOLLOWER_WALS;
      return StatusCode.OK;
    }
    followerDirectories = new NioDurableDirectory[followerDirectoryPaths.length];
    followerWals = new LocalWal[followerDirectoryPaths.length];
    for (int index = 0; index < followerWals.length; index++) {
      StatusCode status = openFollower(index);
      if (!status.isOk()) {
        return status;
      }
    }
    return wal.enableDurableQuorum(followerWals, requiredDurableNodes);
  }

  private StatusCode openFollower(int index) {
    Path followerPath = followerDirectoryPaths[index];
    if (followerPath == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    NioDirectoryOpenResult directoryResult = new NioDirectoryOpenResult();
    StatusCode status = NioDurableDirectory.openExisting(
        followerPath, new FatalStateFence(), new NioIoCounters(), 8, directoryResult);
    if (!status.isOk()) {
      return status;
    }
    NioDurableDirectory followerDirectory = directoryResult.directory();
    followerDirectories[index] = followerDirectory;
    if (duplicatesExistingDirectory(followerDirectory, index)) {
      return StatusCode.CONFLICT;
    }
    LocalWalOpenResult walResult = new LocalWalOpenResult();
    status = create
        ? LocalWal.create(followerDirectory, database, generation, walResult)
        : LocalWal.openExisting(followerDirectory, database, generation, walResult);
    if (status.isOk()) {
      followerWals[index] = walResult.wal();
    }
    return status;
  }

  private boolean duplicatesExistingDirectory(NioDurableDirectory candidate, int count) {
    if (directory.root().equals(candidate.root())) {
      return true;
    }
    for (int index = 0; index < count; index++) {
      if (followerDirectories[index].root().equals(candidate.root())) {
        return true;
      }
    }
    return false;
  }

  private StatusCode openStore() {
    if (create) {
      return IndexedTableStore.create(directory, wal, database, generation, storeResult);
    }
    if (checkpointAvailable) {
      return IndexedTableStore.openCheckpoint(
          directory, wal, database, checkpointState, storeResult);
    }
    return IndexedTableStore.openExisting(directory, wal, database, generation, storeResult);
  }

  private StatusCode openTable() {
    return create
        ? IndexedTable.create(storeResult.store(), tableResult)
        : IndexedTable.open(storeResult.store(), tableResult);
  }

  private StatusCode createControlFile() {
    if (!create) {
      return StatusCode.OK;
    }
    return DatabaseControlStore.create(
        directory, new ControlFile(database, generation), databaseControl);
  }

  private void closeAcquiredResources() {
    IndexedTable table = tableResult.table();
    if (table != null) {
      table.close();
    } else if (storeResult.store() != null) {
      storeResult.store().close();
    }
    closeFollowers();
    if (wal != null) {
      wal.close();
    }
    if (directory != null) {
      directory.close();
    }
  }

  private void closeFollowers() {
    if (followerWals != null) {
      for (LocalWal followerWal : followerWals) {
        if (followerWal != null) {
          followerWal.close();
        }
      }
    }
    if (followerDirectories != null) {
      for (NioDurableDirectory followerDirectory : followerDirectories) {
        if (followerDirectory != null) {
          followerDirectory.close();
        }
      }
    }
  }
}
