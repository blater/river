package io.riverdb.platform.riverd;

/** Caller-owned transfer slot for one verified directory capability. */
public final class RiverDirectoryResult {
  private RiverDirectory directory;

  public RiverDirectory directory() {
    return directory;
  }

  public void set(RiverDirectory value) {
    directory = value;
  }

  public void reset() {
    directory = null;
  }
}
