package io.riverdb.wal.local;

/** Physical progress of one WAL append attempt, independent of its returned status. */
public enum LocalWalAppendDisposition {
  NOTHING_WRITTEN,
  STORAGE_MAY_HAVE_CHANGED,
  COMPLETE
}
