package io.riverdb.testkit.crash;

import io.riverdb.base.error.StatusCode;

/** Caller-owned summary of a bounded crash exploration. */
public final class CrashRunReport {
  private int completedCycles;
  private int recoveredInjectedFailures;
  private int failedCycle = -1;
  private StatusCode status = StatusCode.OK;
  private StatusCode observedWorkloadStatus = StatusCode.OK;
  private StatusCode cleanupStatus = StatusCode.OK;

  public int completedCycles() {
    return completedCycles;
  }

  public int failedCycle() {
    return failedCycle;
  }

  public StatusCode status() {
    return status;
  }

  public int recoveredInjectedFailures() {
    return recoveredInjectedFailures;
  }

  public StatusCode observedWorkloadStatus() {
    return observedWorkloadStatus;
  }

  public StatusCode cleanupStatus() {
    return cleanupStatus;
  }

  void reset() {
    completedCycles = 0;
    recoveredInjectedFailures = 0;
    failedCycle = -1;
    status = StatusCode.OK;
    observedWorkloadStatus = StatusCode.OK;
    cleanupStatus = StatusCode.OK;
  }

  void completed() {
    completedCycles++;
  }

  void recoveredInjectedFailure(StatusCode workloadStatus) {
    recoveredInjectedFailures++;
    observedWorkloadStatus = workloadStatus;
  }

  void failed(int cycle, StatusCode status) {
    failedCycle = cycle;
    this.status = status;
  }

  void failedWithCleanup(
      int cycle,
      StatusCode status,
      StatusCode cleanupStatus) {
    failed(cycle, status);
    observedWorkloadStatus = status;
    this.cleanupStatus = cleanupStatus;
  }
}
