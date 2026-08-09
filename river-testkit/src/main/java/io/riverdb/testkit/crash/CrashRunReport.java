package io.riverdb.testkit.crash;

import io.riverdb.base.error.StatusCode;

/** Caller-owned summary of a bounded crash exploration. */
public final class CrashRunReport {
  private int completedCycles;
  private int failedCycle = -1;
  private StatusCode status = StatusCode.OK;

  public int completedCycles() {
    return completedCycles;
  }

  public int failedCycle() {
    return failedCycle;
  }

  public StatusCode status() {
    return status;
  }

  void reset() {
    completedCycles = 0;
    failedCycle = -1;
    status = StatusCode.OK;
  }

  void completed() {
    completedCycles++;
  }

  void failed(int cycle, StatusCode status) {
    failedCycle = cycle;
    this.status = status;
  }
}
