package io.riverdb.wal.local;

/** Caller-owned output for opening a local WAL. */
public final class LocalWalOpenResult {
  private LocalWal wal;

  public LocalWal wal() {
    return wal;
  }

  public void set(LocalWal value) {
    wal = value;
  }

  public void reset() {
    wal = null;
  }
}
