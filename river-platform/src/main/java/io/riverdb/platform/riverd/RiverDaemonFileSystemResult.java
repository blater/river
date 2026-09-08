package io.riverdb.platform.riverd;

/** Caller-owned result slot for the platform filesystem selected for this runtime. */
public final class RiverDaemonFileSystemResult {
  private RiverDaemonFileSystem fileSystem;

  public RiverDaemonFileSystem fileSystem() {
    return fileSystem;
  }

  public void set(RiverDaemonFileSystem value) {
    fileSystem = value;
  }

  public void reset() {
    fileSystem = null;
  }
}
