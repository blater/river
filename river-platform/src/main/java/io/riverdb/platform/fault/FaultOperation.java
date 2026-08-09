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
  SCHEDULE,
  RUN_TASK,
  CRASH,
  RESTART
}
