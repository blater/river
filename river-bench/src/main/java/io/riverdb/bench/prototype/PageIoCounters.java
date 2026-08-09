package io.riverdb.bench.prototype;

/** Mutable I/O counters for a single disposable page mechanism. */
public final class PageIoCounters {
  long readBytes;
  long writtenBytes;
  long copiedBytes;
  long forceCalls;
  long failures;

  public long readBytes() {
    return readBytes;
  }

  public long writtenBytes() {
    return writtenBytes;
  }

  public long copiedBytes() {
    return copiedBytes;
  }

  public long forceCalls() {
    return forceCalls;
  }

  public long failures() {
    return failures;
  }
}
