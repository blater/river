package io.riverdb.base.concurrent;

/**
 * Reusable thread-safe cancellation source for one bounded operation lifecycle at a time.
 * {@link #reset()} is legal only after every user of the previous lifecycle has quiesced; this
 * class deliberately does not hide operation-generation tracking or make reset race-safe.
 */
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
