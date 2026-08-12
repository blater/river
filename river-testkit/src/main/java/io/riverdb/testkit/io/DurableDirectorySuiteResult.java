package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;

/** Caller-owned summary of a provider-neutral directory contract run. */
public final class DurableDirectorySuiteResult {
  private StatusCode status = StatusCode.OK;
  private int failedScenario;
  private int completedScenarios;

  public StatusCode status() {
    return status;
  }

  public int failedScenario() {
    return failedScenario;
  }

  public int completedScenarios() {
    return completedScenarios;
  }

  public void set(StatusCode status, int failedScenario, int completedScenarios) {
    this.status = status;
    this.failedScenario = failedScenario;
    this.completedScenarios = completedScenarios;
  }

  public void reset() {
    status = StatusCode.OK;
    failedScenario = 0;
    completedScenarios = 0;
  }
}
