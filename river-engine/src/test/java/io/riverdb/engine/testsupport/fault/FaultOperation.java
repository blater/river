package io.riverdb.engine.testsupport.fault;

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
  DIRECTORY_CREATE,
  DIRECTORY_LIST,
  FILE_CREATE,
  FILE_RENAME,
  FILE_REMOVE,
  NAMED_TRUNCATE,
  DIRECTORY_FILE_READ,
  DIRECTORY_FILE_WRITE,
  DIRECTORY_FILE_FORCE,
  DIRECTORY_REOPEN,
  SCHEDULE,
  RUN_TASK,
  CRASH,
  RESTART
}
