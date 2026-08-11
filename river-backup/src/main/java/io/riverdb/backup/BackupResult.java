package io.riverdb.backup;

import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;

/** Caller-owned outcome of one complete offline backup or restore. */
public final class BackupResult {
  private DatabaseIncarnation database;
  private WalGeneration walGeneration;
  private int fileCount;
  private long totalBytes;
  private boolean complete;

  public void reset() {
    database = null;
    walGeneration = null;
    fileCount = 0;
    totalBytes = 0;
    complete = false;
  }

  void complete(
      DatabaseIncarnation databaseIncarnation,
      WalGeneration generation,
      int files,
      long bytes) {
    database = databaseIncarnation;
    walGeneration = generation;
    fileCount = files;
    totalBytes = bytes;
    complete = true;
  }

  public DatabaseIncarnation database() {
    return database;
  }

  public WalGeneration walGeneration() {
    return walGeneration;
  }

  public int fileCount() {
    return fileCount;
  }

  public long totalBytes() {
    return totalBytes;
  }

  public boolean isComplete() {
    return complete;
  }
}
