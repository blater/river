package io.riverdb.testkit.io;

import io.riverdb.base.error.StatusCode;

/** Caller-owned bounded-driver result shared by fake and future provider contract tests. */
public final class AtomicInstallDriveResult {
  private StatusCode status = StatusCode.OK;
  private int advances;

  public StatusCode status() {
    return status;
  }

  public int advances() {
    return advances;
  }

  public void set(StatusCode status, int advances) {
    this.status = status;
    this.advances = advances;
  }

  public void reset() {
    status = StatusCode.OK;
    advances = 0;
  }
}
