package io.riverdb.platform.file.nio;

import java.util.concurrent.atomic.AtomicLong;

/** Non-allocating counters for physical I/O, handles, and explicit adapter copies. */
public final class NioIoCounters {
  private final AtomicLong readCalls = new AtomicLong();
  private final AtomicLong writeCalls = new AtomicLong();
  private final AtomicLong forceCalls = new AtomicLong();
  private final AtomicLong bytesRead = new AtomicLong();
  private final AtomicLong bytesWritten = new AtomicLong();
  private final AtomicLong handlesOpened = new AtomicLong();

  public long readCalls() {
    return readCalls.get();
  }

  public long writeCalls() {
    return writeCalls.get();
  }

  public long forceCalls() {
    return forceCalls.get();
  }

  public long bytesRead() {
    return bytesRead.get();
  }

  public long bytesWritten() {
    return bytesWritten.get();
  }

  public long handlesOpened() {
    return handlesOpened.get();
  }

  void recordRead(int bytes) {
    readCalls.incrementAndGet();
    bytesRead.addAndGet(bytes);
  }

  void recordWrite(int bytes) {
    writeCalls.incrementAndGet();
    bytesWritten.addAndGet(bytes);
  }

  void recordForce() {
    forceCalls.incrementAndGet();
  }

  void recordHandleOpened() {
    handlesOpened.incrementAndGet();
  }

}
