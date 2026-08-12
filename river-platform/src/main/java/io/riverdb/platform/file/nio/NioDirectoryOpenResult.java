package io.riverdb.platform.file.nio;

/** Caller-owned result slot for opening a physical NIO directory provider. */
public final class NioDirectoryOpenResult {
  private NioDurableDirectory directory;

  public NioDurableDirectory directory() {
    return directory;
  }

  public void setDirectory(NioDurableDirectory directory) {
    this.directory = directory;
  }

  public void reset() {
    directory = null;
  }
}
