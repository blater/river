package io.riverdb.engine.control;

import io.riverdb.format.control.ControlFile;

/** Caller-owned result for database control create/open. */
public final class DatabaseControlResult {
  private ControlFile controlFile;

  public ControlFile controlFile() {
    return controlFile;
  }

  public void set(ControlFile value) {
    controlFile = value;
  }

  public void reset() {
    controlFile = null;
  }
}
