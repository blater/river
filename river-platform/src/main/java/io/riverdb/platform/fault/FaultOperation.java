package io.riverdb.platform.fault;

/** Operation identity supplied to a fault hook without allocating an event object. */
public enum FaultOperation {
  OPEN,
  READ,
  WRITE,
  FORCE,
  SIZE,
  TRUNCATE,
  CLOSE,
  TEMP_CREATE,
  TEMP_WRITE,
  TEMP_FORCE,
  REPLACE,
  DIRECTORY_FORCE,
  REOPEN_VERIFY,
  SCHEDULE,
  RUN_TASK,
  CRASH,
  RESTART
}
