package io.riverdb.platform.riverd;

/** Caller-owned transfer slot for one verified regular-file capability. */
public final class RiverFileResult {
  private RiverFile file;

  public RiverFile file() {
    return file;
  }

  public void set(RiverFile value) {
    file = value;
  }

  public void reset() {
    file = null;
  }
}
