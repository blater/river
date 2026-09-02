package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;

/** Caller-owned outcome distinguishing a locked current row from a skipped stale candidate. */
public final class RelationalLockedCandidateResult {
  private boolean locked;

  public boolean isLocked() { return locked; }

  public void reset() { locked = false; }

  StatusCode publishLocked() {
    locked = true;
    return StatusCode.OK;
  }
}
