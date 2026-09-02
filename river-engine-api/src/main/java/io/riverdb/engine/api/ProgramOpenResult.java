package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Caller-owned metadata for one session-owned transaction-program handle. */
public final class ProgramOpenResult {
  private long handle;
  private int argumentSlots;

  public void reset() {
    handle = 0;
    argumentSlots = 0;
  }

  public StatusCode complete(long programHandle, int requiredArgumentSlots) {
    if (programHandle <= 0 || requiredArgumentSlots < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    handle = programHandle;
    argumentSlots = requiredArgumentSlots;
    return StatusCode.OK;
  }

  public long handle() { return handle; }
  public int requiredArgumentSlots() { return argumentSlots; }
}
