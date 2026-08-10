package io.riverdb.inspect;

import io.riverdb.base.id.DatabaseIncarnation;

/** Caller-owned summary produced only after every recognized physical byte validates. */
public final class DatabaseInspectionResult {
  private DatabaseIncarnation database;
  private long physicalBytes;
  private long lastJournalSequence;
  private long lastCommitSequence;
  private int walFileCount;
  private int walRecordCount;
  private int pageFileCount;
  private int pageCount;
  private int unrecognizedEntryCount;
  private boolean available;

  public void reset() {
    database = null;
    physicalBytes = 0;
    lastJournalSequence = 0;
    lastCommitSequence = 0;
    walFileCount = 0;
    walRecordCount = 0;
    pageFileCount = 0;
    pageCount = 0;
    unrecognizedEntryCount = 0;
    available = false;
  }

  void setDatabase(DatabaseIncarnation value) {
    database = value;
  }

  void addControlBytes(long bytes) {
    physicalBytes += bytes;
  }

  void addWalFile(long bytes) {
    walFileCount++;
    physicalBytes += bytes;
  }

  void addWalRecord(long sequence, long commitSequence) {
    walRecordCount++;
    if (sequence > lastJournalSequence) {
      lastJournalSequence = sequence;
    }
    if (commitSequence > lastCommitSequence) {
      lastCommitSequence = commitSequence;
    }
  }

  void addPageFile(long bytes, int pages) {
    pageFileCount++;
    pageCount += pages;
    physicalBytes += bytes;
  }

  void addUnrecognizedEntry() {
    unrecognizedEntryCount++;
  }

  void complete() {
    available = true;
  }

  public DatabaseIncarnation database() {
    return database;
  }

  public long physicalBytes() {
    return physicalBytes;
  }

  public long lastJournalSequence() {
    return lastJournalSequence;
  }

  public long lastCommitSequence() {
    return lastCommitSequence;
  }

  public int walFileCount() {
    return walFileCount;
  }

  public int walRecordCount() {
    return walRecordCount;
  }

  public int pageFileCount() {
    return pageFileCount;
  }

  public int pageCount() {
    return pageCount;
  }

  public int unrecognizedEntryCount() {
    return unrecognizedEntryCount;
  }

  public boolean isAvailable() {
    return available;
  }
}
