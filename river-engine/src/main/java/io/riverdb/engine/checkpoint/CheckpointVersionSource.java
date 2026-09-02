package io.riverdb.engine.checkpoint;

import io.riverdb.base.error.StatusCode;

/** Borrowed, allocation-free row-version source used while installing one checkpoint. */
public interface CheckpointVersionSource {
  StatusCode readVersion(long rowId);
  long commitSequence();
  long previousRowId();
  boolean deleted();

  /** Maximum number of sorted sparse version pages returned by this capture. */
  int versionPageCountUpperBound();

  void resetVersionPages();

  /** Returns the next sorted page id, or {@code -1} after the last page. */
  long nextVersionPageId();
}
