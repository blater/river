package io.riverdb.platform.file;

/** Caller-owned result slot used when opening a durable file. */
public final class OpenFileResult {
  private DurableFile file;

  public DurableFile file() {
    return file;
  }

  public void setFile(DurableFile file) {
    this.file = file;
  }

  public void reset() {
    file = null;
  }
}
