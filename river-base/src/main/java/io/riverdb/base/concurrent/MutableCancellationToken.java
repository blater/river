package io.riverdb.base.concurrent;

/** Reusable thread-safe cancellation source for one bounded operation lifecycle at a time. */
public final class MutableCancellationToken implements CancellationToken {
  private volatile boolean cancellationRequested;

  public void cancel() {
    cancellationRequested = true;
  }

  public void reset() {
    cancellationRequested = false;
  }

  @Override
  public boolean isCancellationRequested() {
    return cancellationRequested;
  }
}
