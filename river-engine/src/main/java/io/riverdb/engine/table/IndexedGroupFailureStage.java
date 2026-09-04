package io.riverdb.engine.table;

/** Stage at which an attempted group commit failed or was redirected. */
public enum IndexedGroupFailureStage {
  PREFLIGHT,
  ADMISSION,
  APPEND,
  FORCE,
  PUBLICATION,
  WRITER_FAILURE
}
