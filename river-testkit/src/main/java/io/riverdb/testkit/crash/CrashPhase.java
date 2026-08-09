package io.riverdb.testkit.crash;

/** Observable bounded phases in one crash/recovery exploration cycle. */
public enum CrashPhase {
  INITIAL_OPEN,
  WORKLOAD,
  CRASH,
  RESTART,
  REOPEN,
  VERIFY,
  CLOSE,
  COMPLETE
}
