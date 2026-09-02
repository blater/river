package io.riverdb.engine;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.checkpoint.CheckpointControlStore;
import io.riverdb.engine.checkpoint.CheckpointState;
import io.riverdb.engine.runtime.DatabaseResourceGovernor;
import io.riverdb.engine.table.IndexedTable;
import io.riverdb.engine.table.IndexedTableStore;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.wal.local.LocalWal;

/** Translates allocation failure while publishing an acquired embedded database. */
final class EmbeddedDatabaseConstruction {
  private EmbeddedDatabaseConstruction() {}

  static StatusCode construct(
      NioDurableDirectory directory,
      NioDurableDirectory[] followerDirectories,
      LocalWal wal,
      LocalWal[] followerWals,
      IndexedTableStore store,
      IndexedTable table,
      int maximumActiveTransactions,
      long lockWaitTimeoutNanos,
      CheckpointControlStore checkpointControl,
      CheckpointState checkpointState,
      long checkpointId,
      DatabaseResourceGovernor resourceGovernor,
      EmbeddedDatabaseOpenResult result) {
    try {
      result.set(new EmbeddedDatabase(
          directory, followerDirectories, wal, followerWals, store, table,
          maximumActiveTransactions, lockWaitTimeoutNanos,
          checkpointControl, checkpointId, resourceGovernor));
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      if (table != null) table.close();
      else if (store != null) store.close();
      checkpointState.close();
      closeFollowers(followerWals, followerDirectories);
      if (wal != null) wal.close();
      if (directory != null) directory.close();
      if (resourceGovernor != null) resourceGovernor.close();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private static void closeFollowers(
      LocalWal[] followerWals, NioDurableDirectory[] followerDirectories) {
    if (followerWals != null) {
      for (LocalWal follower : followerWals) if (follower != null) follower.close();
    }
    if (followerDirectories != null) {
      for (NioDurableDirectory follower : followerDirectories) {
        if (follower != null) follower.close();
      }
    }
  }
}
