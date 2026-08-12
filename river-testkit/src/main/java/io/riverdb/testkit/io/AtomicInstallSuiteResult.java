package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;

/** Caller-owned report from the provider-neutral contract suite. */
public final class AtomicInstallSuiteResult {
  private StatusCode status = StatusCode.OK;
  private int scenario;
  private int completedScenarios;

  public StatusCode status() {
    return status;
  }

  public int scenario() {
    return scenario;
  }

  public int completedScenarios() {
    return completedScenarios;
  }

  public void set(StatusCode status, int scenario, int completedScenarios) {
    this.status = status;
    this.scenario = scenario;
    this.completedScenarios = completedScenarios;
  }

  public void reset() {
    status = StatusCode.OK;
    scenario = 0;
    completedScenarios = 0;
  }
}
