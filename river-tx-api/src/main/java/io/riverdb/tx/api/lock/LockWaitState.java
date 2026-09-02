package io.riverdb.tx.api.lock;

/** Authenticated execution-lane request lifecycle. */
public enum LockWaitState {
  IDLE,
  QUEUED,
  GRANTED,
  TIMED_OUT,
  CANCELLED,
  DEADLOCK,
  FAILED
}
