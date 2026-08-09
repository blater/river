package io.riverdb.base.concurrent;

import io.riverdb.base.error.StatusCode;
import java.util.concurrent.atomic.AtomicInteger;

/** Optional diagnostic guard for use-after-close and duplicate-close detection. */
public final class CloseGuard {
  private static final int OPEN = 0;
  private static final int CLOSED = 1;
  private static final CloseGuard DISABLED = new CloseGuard(false);

  private final boolean enabled;
  private final AtomicInteger state = new AtomicInteger(OPEN);

  private CloseGuard(boolean enabled) {
    this.enabled = enabled;
  }

  public static CloseGuard enabled() {
    return new CloseGuard(true);
  }

  public static CloseGuard disabled() {
    return DISABLED;
  }

  public StatusCode checkOpen() {
    return !enabled || state.get() == OPEN ? StatusCode.OK : StatusCode.CLOSED;
  }

  public StatusCode close() {
    if (!enabled) {
      return StatusCode.OK;
    }
    return state.compareAndSet(OPEN, CLOSED) ? StatusCode.OK : StatusCode.CLOSED;
  }

  public boolean isClosed() {
    return enabled && state.get() == CLOSED;
  }
}
