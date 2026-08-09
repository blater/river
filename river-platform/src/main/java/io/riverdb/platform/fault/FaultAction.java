package io.riverdb.platform.fault;

/** Actions understood by deterministic test providers. Production injectors always choose NONE. */
public enum FaultAction {
  NONE,
  CRASH,
  SHORT_READ,
  SHORT_WRITE,
  PARTIAL_WRITE,
  FORCE_FAILURE,
  DISK_FULL,
  TORN_WRITE,
  CORRUPT_READ,
  CANCEL,
  RESTART
}
